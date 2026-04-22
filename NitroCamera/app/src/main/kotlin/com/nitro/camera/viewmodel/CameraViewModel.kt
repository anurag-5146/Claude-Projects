package com.nitro.camera.viewmodel

import android.app.Application
import android.graphics.SurfaceTexture
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nitro.camera.camera.*
import com.nitro.camera.processing.*
import com.nitro.camera.processing.LogProfile
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class UiState(
    val cameraState: CameraState = CameraState.Closed,
    val params: CaptureParameters = CaptureParameters(),
    val metrics: FrameMetrics = FrameMetrics(),
    val capabilities: CameraCapabilities = CameraCapabilities(),
    val captureMode: CaptureMode = CaptureMode.PHOTO,
    val processingState: ProcessingState = ProcessingState.Idle,
    val detectedScene: Scene = Scene.GENERAL,
    val sceneConfidence: Float = 0f,
    val portraitBlurRadius: Float = 20f,
    val showHistogram: Boolean = true,
    val showFocusPeaking: Boolean = false,
    val showZebraStripes: Boolean = false,
    // Phase 4 — real-time analysis
    val liveHistogram: FloatArray = FloatArray(256),
    val focusMask: android.graphics.Bitmap? = null,
    val zebraFraction: Float = 0f,
    // Phase 4 — video
    val videoState: VideoState = VideoState.Idle,
    val videoLogProfile: LogProfile.Profile = LogProfile.Profile.CINE_D
)

enum class CaptureMode { PHOTO, HDR, NIGHT, PORTRAIT, VIDEO }

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
    private val portraitProcessor = PortraitProcessor(app).also { it.init() }
    private val superResProcessor = SuperResProcessor(app).also { it.init() }
    val videoController = VideoController(app)

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch { controller.cameraState.collect { s -> _ui.update { it.copy(cameraState = s) } } }
        viewModelScope.launch { controller.frameMetrics.collect { m -> _ui.update { it.copy(metrics = m) } } }
        viewModelScope.launch { controller.capabilities.collect { c -> _ui.update { it.copy(capabilities = c) } } }
        viewModelScope.launch { videoController.videoState.collect { v -> _ui.update { it.copy(videoState = v) } } }
        viewModelScope.launch {
            controller.previewAnalyzer.result.collect { r ->
                _ui.update { it.copy(liveHistogram = r.histogram, focusMask = r.focusMask, zebraFraction = r.zebraPixelsFraction) }
            }
        }
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

    fun startCamera(surfaceTexture: SurfaceTexture, surfaceWidth: Int, surfaceHeight: Int) = viewModelScope.launch {
        controller.open()
        controller.startPreview(surfaceTexture, surfaceWidth, surfaceHeight)
    }

    fun setActionMode(enabled: Boolean) {
        controller.updateParams { copy(actionMode = enabled) }
        _ui.update { it.copy(params = it.params.copy(actionMode = enabled)) }
    }

    // ── Capture dispatch ──────────────────────────────────────────────────────

    fun capture() {
        if (_ui.value.processingState !is ProcessingState.Idle) return
        when (_ui.value.captureMode) {
            CaptureMode.PHOTO    -> capturePhoto()
            CaptureMode.HDR      -> captureHdr()
            CaptureMode.NIGHT    -> captureNight()
            CaptureMode.PORTRAIT -> capturePortrait()
            CaptureMode.VIDEO    -> Unit // Phase 4
        }
    }

    private fun capturePhoto() {
        _ui.update { it.copy(processingState = ProcessingState.Capturing(1, 1, "Capturing…")) }
        controller.capturePhoto()
    }

    private fun capturePortrait() = viewModelScope.launch {
        _ui.update { it.copy(processingState = ProcessingState.Capturing(1, 1, "Capturing…")) }

        // Collect one JPEG via the existing burst infrastructure (1 frame)
        val burst = controller.burstCapture { captureHdrBurst(listOf(0f)) }
        val jpegBytes = burst.firstOrNull()
        if (jpegBytes == null) {
            _ui.update { it.copy(processingState = ProcessingState.Error("Portrait capture failed")) }
            return@launch
        }

        _ui.update { it.copy(processingState = ProcessingState.Processing(0.1f, "Detecting subject…")) }
        val t0 = System.currentTimeMillis()

        val bitmap = jpegBytes.decodeJpegToBitmap()
        if (bitmap == null) {
            _ui.update { it.copy(processingState = ProcessingState.Error("Decode failed")) }
            return@launch
        }

        // Classify scene first to get colour science params
        val (scene, _) = SceneClassifier.classify(bitmap)
        val sceneParams = SceneClassifier.paramsFor(scene)

        // Portrait bokeh
        _ui.update { it.copy(processingState = ProcessingState.Processing(0.2f, "Rendering bokeh…")) }
        val blurRadius = _ui.value.portraitBlurRadius
        val bokehBitmap = portraitProcessor.process(bitmap, blurRadius) { p ->
            _ui.update { it.copy(processingState = ProcessingState.Processing(0.2f + p * 0.5f, "Rendering bokeh…")) }
        }

        // Color science pass
        _ui.update { it.copy(processingState = ProcessingState.Processing(0.75f, "Color science…")) }
        val finalBitmap = ColorScience.process(bokehBitmap, sceneParams)

        val uri = processor.saveBitmap(finalBitmap, "NITRO_PORTRAIT")
        _ui.update { it.copy(processingState = ProcessingState.Done(uri, System.currentTimeMillis() - t0)) }
    }

    private fun captureHdr() = viewModelScope.launch {
        val evStops = listOf(-2f, -1f, 0f, 1f, 2f)
        _ui.update { it.copy(processingState = ProcessingState.Capturing(0, evStops.size, "HDR burst…")) }

        val burst = controller.burstCapture(
            onFrameCaptured = { idx ->
                _ui.update { it.copy(processingState = ProcessingState.Capturing(idx + 1, evStops.size, "HDR burst…")) }
            }
        ) { it.captureHdrBurst(evStops) }

        if (burst.isEmpty()) { _ui.update { it.copy(processingState = ProcessingState.Error("HDR capture failed")) }; return@launch }

        val t0 = System.currentTimeMillis()
        val bitmap = HdrProcessor.process(burst) { p ->
            _ui.update { it.copy(processingState = ProcessingState.Processing(p, when {
                p < 0.3f -> "Aligning frames…"; p < 0.6f -> "Merging exposures…"; else -> "Tonemapping…"
            })) }
        } ?: run { _ui.update { it.copy(processingState = ProcessingState.Error("HDR merge failed")) }; return@launch }

        // Apply color science
        val (scene, _) = SceneClassifier.classify(bitmap)
        val finalBitmap = ColorScience.process(bitmap, SceneClassifier.paramsFor(scene))

        val uri = processor.saveBitmap(finalBitmap, "NITRO_HDR")
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

        if (burst.isEmpty()) { _ui.update { it.copy(processingState = ProcessingState.Error("Night capture failed")) }; return@launch }

        val t0 = System.currentTimeMillis()
        val bitmap = NightModeProcessor.process(burst) { p ->
            _ui.update { it.copy(processingState = ProcessingState.Processing(p, when {
                p < 0.4f -> "Aligning frames…"; p < 0.7f -> "Stacking frames…"; else -> "Sharpening…"
            })) }
        } ?: run { _ui.update { it.copy(processingState = ProcessingState.Error("Night merge failed")) }; return@launch }

        val nightParams = SceneClassifier.paramsFor(Scene.NIGHT)
        val finalBitmap = ColorScience.process(bitmap, nightParams)

        val uri = processor.saveBitmap(finalBitmap, "NITRO_NIGHT")
        _ui.update { it.copy(processingState = ProcessingState.Done(uri, System.currentTimeMillis() - t0)) }
    }

    // ── Scene detection ───────────────────────────────────────────────────────

    fun analyzeScene(bitmap: android.graphics.Bitmap) = viewModelScope.launch {
        val (scene, confidence) = SceneClassifier.classify(bitmap)
        _ui.update { it.copy(detectedScene = scene, sceneConfidence = confidence) }
        // Auto-switch to portrait mode if scene confidently detected
        if (scene == Scene.PORTRAIT && confidence > 0.7f && _ui.value.captureMode == CaptureMode.PHOTO) {
            _ui.update { it.copy(captureMode = CaptureMode.PORTRAIT) }
        }
    }

    // ── Controls ──────────────────────────────────────────────────────────────

    fun dismissResult() = _ui.update { it.copy(processingState = ProcessingState.Idle) }
    fun setPortraitBlur(r: Float) = _ui.update { it.copy(portraitBlurRadius = r) }

    // ── Video controls ────────────────────────────────────────────────────────

    fun toggleRecording() {
        when (_ui.value.videoState) {
            is VideoState.Idle, is VideoState.Saved -> startRecording()
            is VideoState.Recording -> videoController.stopRecording()
            is VideoState.Paused -> videoController.resumeRecording()
        }
    }

    fun pauseRecording() = videoController.pauseRecording()

    private fun startRecording() {
        val surface = videoController.prepareSurface()
        // Rebuild session with recording surface included
        viewModelScope.launch {
            controller.startPreviewWithExtras(listOf(surface))
            videoController.startRecording()
        }
    }

    fun setLogProfile(profile: LogProfile.Profile) {
        videoController.logProfile = profile
        _ui.update { it.copy(videoLogProfile = profile) }
    }
    fun setAutoMode(auto: Boolean) {
        controller.updateParams { copy(isAutoExposure = auto, isAutoFocus = auto, isAutoWhiteBalance = auto) }
        _ui.update { it.copy(params = it.params.copy(isAutoExposure = auto, isAutoFocus = auto, isAutoWhiteBalance = auto)) }
    }
    fun setISO(iso: Int) { controller.updateParams { copy(iso = iso, isAutoExposure = false) }; _ui.update { it.copy(params = it.params.copy(iso = iso, isAutoExposure = false)) } }
    fun setShutterSpeed(ns: Long) { controller.updateParams { copy(shutterSpeedNs = ns, isAutoExposure = false) }; _ui.update { it.copy(params = it.params.copy(shutterSpeedNs = ns, isAutoExposure = false)) } }
    fun setFocusDistance(d: Float) { controller.updateParams { copy(focusDistance = d, isAutoFocus = false) }; _ui.update { it.copy(params = it.params.copy(focusDistance = d, isAutoFocus = false)) } }
    fun setWhiteBalance(mode: Int) { controller.updateParams { copy(awbMode = mode) }; _ui.update { it.copy(params = it.params.copy(awbMode = mode)) } }
    fun setZoom(ratio: Float) {
        val c = ratio.coerceIn(1f, _ui.value.capabilities.maxZoom)
        controller.updateParams { copy(zoomRatio = c) }
        _ui.update { it.copy(params = it.params.copy(zoomRatio = c)) }
    }
    fun setCaptureMode(mode: CaptureMode) = _ui.update { it.copy(captureMode = mode) }
    fun toggleHistogram() = _ui.update { it.copy(showHistogram = !it.showHistogram) }
    fun toggleFocusPeaking() = _ui.update { it.copy(showFocusPeaking = !it.showFocusPeaking) }
    fun toggleZebraStripes() = _ui.update { it.copy(showZebraStripes = !it.showZebraStripes) }

    override fun onCleared() {
        controller.close()
        portraitProcessor.close()
        superResProcessor.close()
        videoController.release()
        super.onCleared()
    }
}
