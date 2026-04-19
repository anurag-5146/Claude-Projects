package com.nitro.camera.viewmodel

import android.app.Application
import android.graphics.SurfaceTexture
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nitro.camera.camera.*
import com.nitro.camera.processing.ImageProcessor
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class UiState(
    val cameraState: CameraState = CameraState.Closed,
    val params: CaptureParameters = CaptureParameters(),
    val metrics: FrameMetrics = FrameMetrics(),
    val capabilities: CameraCapabilities = CameraCapabilities(),
    val isCapturing: Boolean = false,
    val lastCaptureLatencyMs: Long = 0,
    val captureMessage: String? = null,
    val showHistogram: Boolean = true,
    val showFocusPeaking: Boolean = false,
    val showZebraStripes: Boolean = false,
    val captureMode: CaptureMode = CaptureMode.PHOTO
)

enum class CaptureMode { PHOTO, VIDEO, NIGHT, HDR }

class CameraViewModel(app: Application) : AndroidViewModel(app) {

    val controller = Camera2Controller(app, viewModelScope)
    private val processor = ImageProcessor(app)

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            controller.cameraState.collect { state ->
                _ui.update { it.copy(cameraState = state) }
            }
        }
        viewModelScope.launch {
            controller.frameMetrics.collect { metrics ->
                _ui.update { it.copy(metrics = metrics) }
            }
        }
        viewModelScope.launch {
            controller.capabilities.collect { caps ->
                _ui.update { it.copy(capabilities = caps) }
            }
        }
        viewModelScope.launch {
            controller.captureResultChannel.receiveAsFlow().collect { outcome ->
                when (outcome) {
                    is CaptureOutcome.Success -> _ui.update {
                        it.copy(
                            isCapturing = false,
                            lastCaptureLatencyMs = outcome.latencyMs,
                            captureMessage = "Captured in ${outcome.latencyMs}ms"
                        )
                    }
                    is CaptureOutcome.Failure -> _ui.update {
                        it.copy(isCapturing = false, captureMessage = outcome.reason)
                    }
                }
            }
        }
    }

    fun startCamera(surfaceTexture: SurfaceTexture) = viewModelScope.launch {
        controller.open()
        controller.startPreview(surfaceTexture)
    }

    fun capture() {
        if (_ui.value.isCapturing) return
        _ui.update { it.copy(isCapturing = true, captureMessage = null) }
        controller.capturePhoto()
    }

    fun setAutoMode(auto: Boolean) {
        controller.updateParams { copy(isAutoExposure = auto, isAutoFocus = auto, isAutoWhiteBalance = auto) }
        _ui.update { it.copy(params = it.params.copy(isAutoExposure = auto, isAutoFocus = auto, isAutoWhiteBalance = auto)) }
    }

    fun setISO(iso: Int) {
        controller.updateParams { copy(iso = iso, isAutoExposure = false) }
        _ui.update { it.copy(params = it.params.copy(iso = iso, isAutoExposure = false)) }
    }

    fun setShutterSpeed(ns: Long) {
        controller.updateParams { copy(shutterSpeedNs = ns, isAutoExposure = false) }
        _ui.update { it.copy(params = it.params.copy(shutterSpeedNs = ns, isAutoExposure = false)) }
    }

    fun setFocusDistance(distance: Float) {
        controller.updateParams { copy(focusDistance = distance, isAutoFocus = false) }
        _ui.update { it.copy(params = it.params.copy(focusDistance = distance, isAutoFocus = false)) }
    }

    fun setWhiteBalance(mode: Int) {
        controller.updateParams { copy(awbMode = mode) }
        _ui.update { it.copy(params = it.params.copy(awbMode = mode)) }
    }

    fun setZoom(ratio: Float) {
        controller.updateParams { copy(zoomRatio = ratio.coerceIn(1f, _ui.value.capabilities.maxZoom)) }
        _ui.update { it.copy(params = it.params.copy(zoomRatio = ratio)) }
    }

    fun setCaptureMode(mode: CaptureMode) = _ui.update { it.copy(captureMode = mode) }
    fun toggleHistogram() = _ui.update { it.copy(showHistogram = !it.showHistogram) }
    fun toggleFocusPeaking() = _ui.update { it.copy(showFocusPeaking = !it.showFocusPeaking) }
    fun toggleZebraStripes() = _ui.update { it.copy(showZebraStripes = !it.showZebraStripes) }

    override fun onCleared() {
        controller.close()
        super.onCleared()
    }
}
