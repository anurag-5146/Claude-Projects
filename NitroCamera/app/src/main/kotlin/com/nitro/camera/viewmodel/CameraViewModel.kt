package com.nitro.camera.viewmodel

import android.app.Application
import android.graphics.SurfaceTexture
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nitro.camera.camera.*
import com.nitro.camera.processing.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class UiState(
    val cameraState: CameraState = CameraState.Closed,
    val params: CaptureParameters = CaptureParameters(),
    val metrics: FrameMetrics = FrameMetrics(),
    val capabilities: CameraCapabilities = CameraCapabilities(),
    val captureMode: CaptureMode = CaptureMode.PHOTO,
    val processingState: ProcessingState = ProcessingState.Idle,
    val showHistogram: Boolean = true,
    val showFocusPeaking: Boolean = false,
    val showZebraStripes: Boolean = false
)

enum class CaptureMode { PHOTO, HDR, NIGHT, VIDEO }

sealed class ProcessingState {
    object Idle : ProcessingState()
    data class Capturing(val current: Int, val total: Int, val label: String) : ProcessingState()
    data class Processing(val progress: Float, val stage: String) : ProcessingState()
    data class Done(val savedUri: String, val latencyMs: Long) : ProcessingState()
    data class Error(val message: String) : ProcessingState()
}

class CameraViewModel(app: Application) : AndroidViewModel(app) {

    val controller = Camera2Controller(app, viewModelScope)
    private val processor = ImageProcessor(app)

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch { controller.cameraState.collect { s -> _ui.update { it.copy(cameraState = s) } } }
        viewModelScope.launch { controller.frameMetrics.collect { m -> _ui.update { it.copy(metrics = m) } } }
        viewModelScope.launch { controller.capabilities.collect { c -> _ui.update { it.copy(capabilities = c) } } }
        viewModelScope.launch {
            controller.captureResultChannel.receiveAsFlow().collect { outcome ->
                when (outcome) {
                    is CaptureOutcome.Success -> _ui.update {
                        it.copy(processingState = ProcessingState.Done("", outcome.latencyMs))
                    }
                    is CaptureOutcome.Failure -> _ui.update {
                        it.copy(processingState = ProcessingState.Error(outcome.reason))
                    }
                }
            }
        }
    }

    fun startCamera(surfaceTexture: SurfaceTexture) = viewModelScope.launch {
        controller.open()
        controller.startPreview(surfaceTexture)
    }

    // ── Capture dispatch ──────────────────────────────────────────────────────

    fun capture() {
        if (_ui.value.processingState !is ProcessingState.Idle) return
        when (_ui.value.captureMode) {
            CaptureMode.PHOTO -> capturePhoto()
            CaptureMode.HDR   -> captureHdr()
            CaptureMode.NIGHT -> captureNight()
            CaptureMode.VIDEO -> { /* Phase 4 */ }
        }
    }

    private fun capturePhoto() {
        _ui.update { it.copy(processingState = ProcessingState.Capturing(1, 1, "Capturing…")) }
        controller.capturePhoto()
    }

    private fun captureHdr() = viewModelScope.launch {
        val evStops = listOf(-2f, -1f, 0f, 1f, 2f)
        _ui.update { it.copy(processingState = ProcessingState.Capturing(0, evStops.size, "HDR burst…")) }

        val burst = controller.burstCapture(
            onFrameCaptured = { idx ->
                _ui.update { it.copy(processingState = ProcessingState.Capturing(idx + 1, evStops.size, "HDR burst…")) }
            }
        ) { it.captureHdrBurst(evStops) }

        if (burst.isEmpty()) {
            _ui.update { it.copy(processingState = ProcessingState.Error("HDR capture failed")) }
            return@launch
        }

        _ui.update { it.copy(processingState = ProcessingState.Processing(0f, "Aligning frames…")) }
        val t0 = System.currentTimeMillis()

        val bitmap = HdrProcessor.process(burst) { progress ->
            val stage = when {
                progress < 0.3f -> "Aligning frames…"
                progress < 0.6f -> "Merging exposures…"
                else -> "Tonemapping…"
            }
            _ui.update { it.copy(processingState = ProcessingState.Processing(progress, stage)) }
        }

        if (bitmap == null) {
            _ui.update { it.copy(processingState = ProcessingState.Error("HDR merge failed")) }
            return@launch
        }

        val uri = processor.saveBitmap(bitmap, "NITRO_HDR")
        _ui.update { it.copy(processingState = ProcessingState.Done(uri, System.currentTimeMillis() - t0)) }
    }

    private fun captureNight() = viewModelScope.launch {
        val frameCount = 15
        _ui.update { it.copy(processingState = ProcessingState.Capturing(0, frameCount, "Night burst…")) }

        val burst = controller.burstCapture(
            onFrameCaptured = { idx ->
                _ui.update { it.copy(processingState = ProcessingState.Capturing(idx + 1, frameCount, "Night burst…")) }
            }
        ) { it.captureNightBurst(frameCount) }

        if (burst.isEmpty()) {
            _ui.update { it.copy(processingState = ProcessingState.Error("Night capture failed")) }
            return@launch
        }

        _ui.update { it.copy(processingState = ProcessingState.Processing(0f, "Aligning frames…")) }
        val t0 = System.currentTimeMillis()

        val bitmap = NightModeProcessor.process(burst) { progress ->
            val stage = when {
                progress < 0.4f -> "Aligning frames…"
                progress < 0.7f -> "Stacking frames…"
                else -> "Sharpening…"
            }
            _ui.update { it.copy(processingState = ProcessingState.Processing(progress, stage)) }
        }

        if (bitmap == null) {
            _ui.update { it.copy(processingState = ProcessingState.Error("Night merge failed")) }
            return@launch
        }

        val uri = processor.saveBitmap(bitmap, "NITRO_NIGHT")
        _ui.update { it.copy(processingState = ProcessingState.Done(uri, System.currentTimeMillis() - t0)) }
    }

    fun dismissResult() = _ui.update { it.copy(processingState = ProcessingState.Idle) }

    // ── Parameter control ─────────────────────────────────────────────────────

    fun setAutoMode(auto: Boolean) {
        controller.updateParams { copy(isAutoExposure = auto, isAutoFocus = auto, isAutoWhiteBalance = auto) }
        _ui.update { it.copy(params = it.params.copy(isAutoExposure = auto, isAutoFocus = auto, isAutoWhiteBalance = auto)) }
    }
    fun setISO(iso: Int) { controller.updateParams { copy(iso = iso, isAutoExposure = false) }; _ui.update { it.copy(params = it.params.copy(iso = iso, isAutoExposure = false)) } }
    fun setShutterSpeed(ns: Long) { controller.updateParams { copy(shutterSpeedNs = ns, isAutoExposure = false) }; _ui.update { it.copy(params = it.params.copy(shutterSpeedNs = ns, isAutoExposure = false)) } }
    fun setFocusDistance(d: Float) { controller.updateParams { copy(focusDistance = d, isAutoFocus = false) }; _ui.update { it.copy(params = it.params.copy(focusDistance = d, isAutoFocus = false)) } }
    fun setWhiteBalance(mode: Int) { controller.updateParams { copy(awbMode = mode) }; _ui.update { it.copy(params = it.params.copy(awbMode = mode)) } }
    fun setZoom(ratio: Float) {
        val clamped = ratio.coerceIn(1f, _ui.value.capabilities.maxZoom)
        controller.updateParams { copy(zoomRatio = clamped) }
        _ui.update { it.copy(params = it.params.copy(zoomRatio = clamped)) }
    }
    fun setCaptureMode(mode: CaptureMode) = _ui.update { it.copy(captureMode = mode) }
    fun toggleHistogram() = _ui.update { it.copy(showHistogram = !it.showHistogram) }
    fun toggleFocusPeaking() = _ui.update { it.copy(showFocusPeaking = !it.showFocusPeaking) }
    fun toggleZebraStripes() = _ui.update { it.copy(showZebraStripes = !it.showZebraStripes) }

    override fun onCleared() { controller.close(); super.onCleared() }
}
