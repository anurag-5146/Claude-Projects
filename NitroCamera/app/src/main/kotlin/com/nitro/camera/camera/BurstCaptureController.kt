package com.nitro.camera.camera

import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.util.Log
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.ByteBuffer

private const val TAG = "BurstCapture"
private const val CAPTURE_TIMEOUT_MS = 15_000L

/**
 * Manages multi-frame burst sequences for HDR and Night Mode.
 * Shares the camera session/device already established by Camera2Controller.
 */
class BurstCaptureController(
    private val device: CameraDevice,
    private val session: CameraCaptureSession,
    private val handler: Handler,
    private val baseParams: CaptureParameters
) {
    // ── HDR Burst ─────────────────────────────────────────────────────────────

    /**
     * Captures N bracketed exposures for HDR merge.
     * Returns JPEG byte arrays in the order they were captured.
     * evStops: exposure stops relative to base (e.g. -2f, -1f, 0f, 1f, 2f).
     */
    suspend fun captureHdrBurst(
        evStops: List<Float> = listOf(-2f, -1f, 0f, 1f, 2f)
    ): List<ByteArray> {
        val frameChannel = Channel<ByteArray>(evStops.size)
        val reader = makeJpegReader(evStops.size + 2)

        reader.setOnImageAvailableListener({ r ->
            r.acquireLatestImage()?.use { image ->
                val buf: ByteBuffer = image.planes[0].buffer
                val bytes = ByteArray(buf.remaining()).also { buf.get(it) }
                frameChannel.trySend(bytes)
            }
        }, handler)

        val requests = evStops.map { ev ->
            device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(reader.surface)
                applyBaseParams()
                // Manual exposure with EV offset
                val tNs = (baseParams.shutterSpeedNs * Math.pow(2.0, ev.toDouble())).toLong()
                    .coerceIn(1_000_000L, 4_000_000_000L)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                set(CaptureRequest.SENSOR_EXPOSURE_TIME, tNs)
                set(CaptureRequest.SENSOR_SENSITIVITY, baseParams.iso)
                set(CaptureRequest.JPEG_QUALITY, 97)
            }.build()
        }

        session.captureBurst(requests, null, handler)
        return collectFrames(frameChannel, evStops.size).also { reader.close() }
    }

    // ── Night Burst ───────────────────────────────────────────────────────────

    /**
     * Captures [frameCount] frames for night-mode stacking.
     * Uses auto-exposure with a long shutter floor so the camera adapts
     * while we stack enough photons.
     */
    suspend fun captureNightBurst(frameCount: Int = 15): List<ByteArray> {
        val frameChannel = Channel<ByteArray>(frameCount + 2)
        val reader = makeJpegReader(frameCount + 2)

        reader.setOnImageAvailableListener({ r ->
            r.acquireLatestImage()?.use { image ->
                val buf = image.planes[0].buffer
                val bytes = ByteArray(buf.remaining()).also { buf.get(it) }
                frameChannel.trySend(bytes)
            }
        }, handler)

        val nightRequest = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
            addTarget(reader.surface)
            // Let AE run but clamp minimum shutter to 1/15s so each frame has enough light
            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, android.util.Range(5, 15))
            set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF)
            set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF)
            set(CaptureRequest.JPEG_QUALITY, 97)
        }.build()

        repeat(frameCount) {
            session.capture(nightRequest, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureFailed(s: CameraCaptureSession, r: CaptureRequest, f: CaptureFailure) {
                    Log.w(TAG, "Night frame failed: ${f.reason}")
                    frameChannel.trySend(ByteArray(0))
                }
            }, handler)
        }

        return collectFrames(frameChannel, frameCount)
            .filter { it.isNotEmpty() }
            .also { reader.close() }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun makeJpegReader(maxImages: Int) =
        ImageReader.newInstance(4032, 3024, ImageFormat.JPEG, maxImages)

    private fun CaptureRequest.Builder.applyBaseParams() {
        set(CaptureRequest.CONTROL_AWB_MODE, baseParams.awbMode)
        set(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE,
            CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY)
    }

    private suspend fun collectFrames(channel: Channel<ByteArray>, count: Int): List<ByteArray> {
        val frames = mutableListOf<ByteArray>()
        repeat(count) {
            val frame = withTimeoutOrNull(CAPTURE_TIMEOUT_MS) { channel.receive() }
            if (frame != null) frames.add(frame) else Log.w(TAG, "Frame $it timed out")
        }
        return frames
    }
}
