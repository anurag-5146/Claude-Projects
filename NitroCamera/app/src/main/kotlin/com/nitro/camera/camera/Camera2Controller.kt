package com.nitro.camera.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "Camera2Controller"
private const val JPEG_QUALITY = 97
// ACTION mode: max shutter time so fast motion (e.g. fan blades) is frozen.
private const val ACTION_MAX_SHUTTER_NS = 1_000_000_000L / 1000L  // 1/1000 s

class Camera2Controller(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewSurface: Surface? = null
    private var jpegReader: ImageReader? = null
    private var rawReader: ImageReader? = null

    private val cameraThread = HandlerThread("CameraThread").also { it.start() }
    private val cameraHandler = Handler(cameraThread.looper)

    private val zslBuffer = ZSLRingBuffer(capacity = 3)

    private val _cameraState = MutableStateFlow<CameraState>(CameraState.Closed)
    val cameraState: StateFlow<CameraState> = _cameraState.asStateFlow()

    private val _frameMetrics = MutableStateFlow(FrameMetrics())
    val frameMetrics: StateFlow<FrameMetrics> = _frameMetrics.asStateFlow()

    private val _capabilities = MutableStateFlow(CameraCapabilities())
    val capabilities: StateFlow<CameraCapabilities> = _capabilities.asStateFlow()

    val captureResultChannel = Channel<CaptureOutcome>(Channel.BUFFERED)

    private var params = CaptureParameters()
    private var captureStartTimeMs = 0L
    val previewAnalyzer = PreviewAnalyzer(scope, cameraHandler)

    // View (SurfaceTexture) dimensions — fed from the Composable so we can
    // pick a preview size with matching aspect ratio.
    private var viewWidth = 0
    private var viewHeight = 0

    // ── Open / Close ──────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    suspend fun open(cameraId: String = findBackCamera()) {
        _cameraState.value = CameraState.Opening
        runCatching {
            loadCapabilities(cameraId)
            cameraDevice = openCameraDevice(cameraId)
            _cameraState.value = CameraState.Ready
        }.onFailure {
            _cameraState.value = CameraState.Error(it.message ?: "Failed to open camera")
            Log.e(TAG, "Camera open failed", it)
        }
    }

    fun close() {
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
        jpegReader?.close()
        jpegReader = null
        rawReader?.close()
        rawReader = null
        previewAnalyzer.close()
        zslBuffer.clear()
        _cameraState.value = CameraState.Closed
    }

    // ── Preview ───────────────────────────────────────────────────────────────

    suspend fun startPreview(
        surfaceTexture: SurfaceTexture,
        surfaceWidth: Int,
        surfaceHeight: Int,
        cameraId: String = findBackCamera()
    ) {
        val device = cameraDevice ?: return
        viewWidth = surfaceWidth
        viewHeight = surfaceHeight

        // Choose the best preview size matching the view aspect ratio.
        val previewSize = choosePreviewSize(cameraId, surfaceWidth, surfaceHeight)
        surfaceTexture.setDefaultBufferSize(previewSize.width, previewSize.height)
        val surface = Surface(surfaceTexture).also { previewSurface = it }

        _capabilities.value = _capabilities.value.copy(previewSize = previewSize)

        // JPEG at full sensor size (or max available JPEG size).
        val jpegSize = chooseJpegSize(cameraId)
        jpegReader = ImageReader.newInstance(jpegSize.width, jpegSize.height, ImageFormat.JPEG, 2)

        // Wire JPEG listener so PHOTO mode capture saves the JPEG
        jpegReader!!.setOnImageAvailableListener({ reader ->
            reader.acquireLatestImage()?.use { image ->
                val planes = image.planes
                val buffer = planes[0].buffer
                val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
                scope.launch {
                    Log.d(TAG, "JPEG captured: ${bytes.size} bytes")
                    captureResultChannel.send(CaptureOutcome.JpegCaptured(bytes))
                }
            }
        }, cameraHandler)

        rawReader = if (_capabilities.value.supportsRaw) {
            ImageReader.newInstance(
                _capabilities.value.sensorSize.width,
                _capabilities.value.sensorSize.height,
                ImageFormat.RAW_SENSOR, 2
            )
        } else null

        val outputs = buildList {
            add(OutputConfiguration(surface))
            add(OutputConfiguration(jpegReader!!.surface))
            rawReader?.let { add(OutputConfiguration(it.surface)) }
            add(OutputConfiguration(previewAnalyzer.imageReader.surface))
        }

        captureSession = createCaptureSession(device, outputs)
        sendRepeatingPreview()
    }

    /** Re-opens the capture session with additional surfaces (e.g. MediaRecorder for video). */
    suspend fun startPreviewWithExtras(extraSurfaces: List<Surface>) {
        val device = cameraDevice ?: return
        val surface = previewSurface ?: return
        captureSession?.close()

        val outputs = buildList {
            add(OutputConfiguration(surface))
            jpegReader?.let { add(OutputConfiguration(it.surface)) }
            rawReader?.let { add(OutputConfiguration(it.surface)) }
            add(OutputConfiguration(previewAnalyzer.imageReader.surface))
            extraSurfaces.forEach { add(OutputConfiguration(it)) }
        }
        captureSession = createCaptureSession(device, outputs)
        sendRepeatingPreview()
    }

    private fun sendRepeatingPreview() {
        val session = captureSession ?: return
        val surface = previewSurface ?: return
        val request = buildPreviewRequest(surface)
        session.setRepeatingRequest(request, previewCaptureCallback, cameraHandler)
    }

    // ── Capture ───────────────────────────────────────────────────────────────

    fun capturePhoto() {
        val session = captureSession ?: return
        val jpeg = jpegReader ?: return

        captureStartTimeMs = System.currentTimeMillis()

        val surfaces = buildList {
            previewSurface?.let { add(it) }
            add(jpeg.surface)
            rawReader?.let { add(it.surface) }
        }

        val request = (cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            ?: return).apply {
            surfaces.forEach { addTarget(it) }
            applyParams(this, forStillCapture = true)
            set(CaptureRequest.JPEG_QUALITY, JPEG_QUALITY.toByte())
            set(CaptureRequest.JPEG_ORIENTATION, 90)
            set(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE,
                CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_ON)
        }.build()

        session.capture(request, stillCaptureCallback, cameraHandler)
    }

    // ── Parameter Control ─────────────────────────────────────────────────────

    fun updateParams(update: CaptureParameters.() -> CaptureParameters) {
        val next = params.update()
        // Skip rebuild when nothing actually changed (prevents stutter on sliders).
        if (next == params) return
        params = next
        sendRepeatingPreview()
    }

    // ── Internal Helpers ──────────────────────────────────────────────────────

    private fun buildPreviewRequest(surface: Surface): CaptureRequest {
        return (cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)).apply {
            addTarget(surface)
            addTarget(previewAnalyzer.imageReader.surface)
            applyParams(this, forStillCapture = false)
            // Ask the device for the highest stable FPS range for smooth preview.
            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, _capabilities.value.previewFpsRange)
        }.build()
    }

    private fun applyParams(builder: CaptureRequest.Builder, forStillCapture: Boolean) = with(builder) {
        // Auto vs manual exposure (ACTION mode caps shutter inside AE).
        when {
            params.actionMode && params.isAutoExposure -> {
                // Let AE run, but clamp max shutter time so motion is frozen.
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                // FPS range implicitly caps min exposure; also request the Action scene.
                val high = _capabilities.value.previewFpsRange.upper.coerceAtLeast(30)
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(high, high))
                set(CaptureRequest.CONTROL_SCENE_MODE, CaptureRequest.CONTROL_SCENE_MODE_SPORTS)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_USE_SCENE_MODE)
            }
            params.isAutoExposure -> {
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            }
            else -> {
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                val shutter = if (params.actionMode)
                    minOf(params.shutterSpeedNs, ACTION_MAX_SHUTTER_NS)
                else params.shutterSpeedNs
                set(CaptureRequest.SENSOR_SENSITIVITY, params.iso)
                set(CaptureRequest.SENSOR_EXPOSURE_TIME, shutter)
            }
        }

        if (params.isAutoWhiteBalance) {
            set(CaptureRequest.CONTROL_AWB_MODE, params.awbMode)
        } else {
            set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)
        }

        if (params.isAutoFocus) {
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        } else {
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            set(CaptureRequest.LENS_FOCUS_DISTANCE, params.focusDistance)
        }

        set(CaptureRequest.CONTROL_ZOOM_RATIO, params.zoomRatio)

        // ── CRITICAL PERF FIX ──
        // HIGH_QUALITY modes are for the final still only — on preview they
        // crush frame rate and add 50–150 ms of processing latency per frame.
        val ispQuality = if (forStillCapture)
            CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY
        else
            CaptureRequest.NOISE_REDUCTION_MODE_FAST
        set(CaptureRequest.NOISE_REDUCTION_MODE, ispQuality)

        val edgeQuality = if (forStillCapture)
            CaptureRequest.EDGE_MODE_HIGH_QUALITY
        else
            CaptureRequest.EDGE_MODE_FAST
        set(CaptureRequest.EDGE_MODE, edgeQuality)

        val aberrationQuality = if (forStillCapture)
            CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY
        else
            CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_FAST
        set(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE, aberrationQuality)
    }

    private val previewCaptureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            val iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0
            val exposure = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L
            val focus = result.get(CaptureResult.LENS_FOCUS_DISTANCE) ?: 0f

            _frameMetrics.value = FrameMetrics(
                iso = iso,
                shutterSpeedNs = exposure,
                focusDistance = focus
            )
        }
    }

    private val stillCaptureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            val latency = System.currentTimeMillis() - captureStartTimeMs
            scope.launch {
                captureResultChannel.send(CaptureOutcome.Success(result, latency))
            }
        }

        override fun onCaptureFailed(
            session: CameraCaptureSession,
            request: CaptureRequest,
            failure: CaptureFailure
        ) {
            scope.launch {
                captureResultChannel.send(CaptureOutcome.Failure("Capture failed: ${failure.reason}"))
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun openCameraDevice(cameraId: String): CameraDevice =
        suspendCancellableCoroutine { cont ->
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) = cont.resume(camera)
                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cont.resumeWithException(RuntimeException("Camera disconnected"))
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    cont.resumeWithException(RuntimeException("Camera error: $error"))
                }
            }, cameraHandler)
        }

    private suspend fun createCaptureSession(
        device: CameraDevice,
        outputs: List<OutputConfiguration>
    ): CameraCaptureSession = suspendCancellableCoroutine { cont ->
        val config = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR,
            outputs,
            { it.run() },
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) = cont.resume(session)
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    cont.resumeWithException(RuntimeException("Session configuration failed"))
                }
            }
        )
        device.createCaptureSession(config)
    }

    private fun loadCapabilities(cameraId: String) {
        val chars = cameraManager.getCameraCharacteristics(cameraId)
        val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
        val supportsRaw = CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW in caps.toList()
        val supportsZsl = CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_PRIVATE_REPROCESSING in caps.toList()

        val isoRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            ?: Range(100, 3200)
        val exposureRange = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            ?: Range(1_000_000L, 1_000_000_000L)
        val maxZoom = chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.upper ?: 1f
        val sensorSize = chars.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
            ?: Size(4032, 3024)

        val fpsRange = pickBestFpsRange(chars)

        _capabilities.value = _capabilities.value.copy(
            supportedIsoRange = isoRange,
            supportedExposureRange = exposureRange,
            supportsRaw = supportsRaw,
            supportsZsl = supportsZsl,
            maxZoom = maxZoom,
            sensorSize = sensorSize,
            previewFpsRange = fpsRange
        )
    }

    /** Highest stable FPS range (prefer [60,60] > [30,60] > [30,30]). */
    private fun pickBestFpsRange(chars: CameraCharacteristics): Range<Int> {
        val ranges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?: return Range(30, 30)
        // Prefer fixed high: (60,60), (120,120); otherwise top-of-range.
        val fixed = ranges.filter { it.lower == it.upper }.maxByOrNull { it.upper }
        val variable = ranges.maxByOrNull { it.upper }
        val pick = when {
            fixed != null && fixed.upper >= 60 -> fixed
            variable != null && variable.upper >= 60 -> variable
            else -> ranges.maxByOrNull { it.upper } ?: Range(30, 30)
        }
        Log.d(TAG, "Chose FPS range $pick (available: ${ranges.toList()})")
        return pick
    }

    /** Largest preview size that matches the view aspect ratio and fits within 1920x1440. */
    private fun choosePreviewSize(cameraId: String, viewW: Int, viewH: Int): Size {
        val chars = cameraManager.getCameraCharacteristics(cameraId)
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return Size(1920, 1080)
        val candidates = map.getOutputSizes(SurfaceTexture::class.java) ?: return Size(1920, 1080)

        val targetAspect = if (viewW > 0 && viewH > 0) {
            // Device is portrait, but camera sizes are reported landscape.
            maxOf(viewW, viewH).toFloat() / minOf(viewW, viewH).toFloat()
        } else {
            16f / 9f
        }

        // Filter to sizes within a reasonable preview budget and closest aspect.
        val maxArea = 1920 * 1440
        val best = candidates
            .filter { it.width * it.height <= maxArea }
            .minByOrNull { s ->
                val sAspect = s.width.toFloat() / s.height.toFloat()
                val aspectDiff = kotlin.math.abs(sAspect - targetAspect)
                // Primary key aspect, secondary key: -area (prefer larger).
                aspectDiff * 10_000f + (maxArea - s.width * s.height) / maxArea.toFloat()
            } ?: Size(1920, 1080)
        Log.d(TAG, "Chose preview size $best for view ${viewW}x$viewH (target aspect $targetAspect)")
        return best
    }

    private fun chooseJpegSize(cameraId: String): Size {
        val chars = cameraManager.getCameraCharacteristics(cameraId)
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return Size(4032, 3024)
        val sizes = map.getOutputSizes(ImageFormat.JPEG) ?: return Size(4032, 3024)
        return sizes.maxByOrNull { it.width.toLong() * it.height.toLong() } ?: Size(4032, 3024)
    }

    private fun findBackCamera(): String =
        cameraManager.cameraIdList.first { id ->
            cameraManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        }

    // ── Burst capture gateway ─────────────────────────────────────────────────

    /**
     * Opens a BurstCaptureController on the live session/device and runs [block].
     * [onFrameCaptured] is called (0-indexed) after each frame arrives so the
     * UI can update a progress counter.
     */
    suspend fun burstCapture(
        onFrameCaptured: (Int) -> Unit = {},
        block: suspend BurstCaptureController.(onFrameCaptured: (Int) -> Unit) -> List<ByteArray>
    ): List<ByteArray> {
        val dev = cameraDevice ?: return emptyList()
        val sess = captureSession ?: return emptyList()
        val burstCtrl = BurstCaptureController(
            device = dev,
            session = sess,
            handler = cameraHandler,
            baseParams = params
        )
        return burstCtrl.block(onFrameCaptured)
    }
}

sealed class CaptureOutcome {
    data class Success(val result: TotalCaptureResult, val latencyMs: Long) : CaptureOutcome()
    data class Failure(val reason: String) : CaptureOutcome()
    data class JpegCaptured(val bytes: ByteArray) : CaptureOutcome() {
        override fun equals(other: Any?) = this === other
        override fun hashCode() = bytes.contentHashCode()
    }
}
