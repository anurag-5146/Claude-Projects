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
private const val PREVIEW_WIDTH = 1920
private const val PREVIEW_HEIGHT = 1080

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
        zslBuffer.clear()
        _cameraState.value = CameraState.Closed
    }

    // ── Preview ───────────────────────────────────────────────────────────────

    suspend fun startPreview(surfaceTexture: SurfaceTexture) {
        val device = cameraDevice ?: return

        surfaceTexture.setDefaultBufferSize(PREVIEW_WIDTH, PREVIEW_HEIGHT)
        val surface = Surface(surfaceTexture).also { previewSurface = it }

        jpegReader = ImageReader.newInstance(4032, 3024, ImageFormat.JPEG, 2)
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
            applyParams(this)
            set(CaptureRequest.JPEG_QUALITY, JPEG_QUALITY.toByte())
            set(CaptureRequest.JPEG_ORIENTATION, 90)
            set(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE,
                CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_ON)
        }.build()

        session.capture(request, stillCaptureCallback, cameraHandler)
    }

    // ── Parameter Control ─────────────────────────────────────────────────────

    fun updateParams(update: CaptureParameters.() -> CaptureParameters) {
        params = params.update()
        sendRepeatingPreview()
    }

    // ── Internal Helpers ──────────────────────────────────────────────────────

    private fun buildPreviewRequest(surface: Surface): CaptureRequest {
        return (cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)).apply {
            addTarget(surface)
            applyParams(this)
        }.build()
    }

    private fun applyParams(builder: CaptureRequest.Builder) = with(builder) {
        if (params.isAutoExposure) {
            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        } else {
            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            set(CaptureRequest.SENSOR_SENSITIVITY, params.iso)
            set(CaptureRequest.SENSOR_EXPOSURE_TIME, params.shutterSpeedNs)
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
        set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
        set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
        set(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE,
            CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY)
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
            ?: android.util.Range(100, 3200)
        val exposureRange = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            ?: android.util.Range(1_000_000L, 1_000_000_000L)
        val maxZoom = chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.upper ?: 1f
        val sensorSize = chars.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
            ?: android.util.Size(4032, 3024)

        _capabilities.value = CameraCapabilities(
            supportedIsoRange = isoRange,
            supportedExposureRange = exposureRange,
            supportsRaw = supportsRaw,
            supportsZsl = supportsZsl,
            maxZoom = maxZoom,
            sensorSize = sensorSize
        )
    }

    private fun findBackCamera(): String =
        cameraManager.cameraIdList.first { id ->
            cameraManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        }
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
            baseParams = CaptureParameters()
        )
        return burstCtrl.block(onFrameCaptured)
    }
}

sealed class CaptureOutcome {
    data class Success(val result: TotalCaptureResult, val latencyMs: Long) : CaptureOutcome()
    data class Failure(val reason: String) : CaptureOutcome()
}
