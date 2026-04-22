package com.nitro.camera.camera

import android.content.ContentValues
import android.content.Context
import android.hardware.camera2.*
import android.media.MediaRecorder
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import com.nitro.camera.processing.LogProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "VideoController"

sealed class VideoState {
    object Idle : VideoState()
    data class Recording(val startTimeMs: Long, val filePath: String) : VideoState()
    data class Paused(val filePath: String) : VideoState()
    data class Saved(val filePath: String) : VideoState()
}

/**
 * Manages Camera2 video recording with LOG tone curve support.
 *
 * Features:
 *  - 4K/1080p HEVC (H.265) at 50 Mbps — high quality, half the H.264 file size
 *  - LOG profile selection: Standard / Cine-D / S-Log3 (applied via TONEMAP_CURVE)
 *  - Start / pause / stop lifecycle tied to Camera2 session
 */
class VideoController(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var recordingSurface: Surface? = null
    private var currentFilePath: String? = null

    private val _videoState = MutableStateFlow<VideoState>(VideoState.Idle)
    val videoState: StateFlow<VideoState> = _videoState.asStateFlow()

    var logProfile: LogProfile.Profile = LogProfile.Profile.CINE_D

    // ── Surfaces ──────────────────────────────────────────────────────────────

    fun prepareSurface(width: Int = 3840, height: Int = 2160): Surface {
        val filePath = createOutputFilePath()
        currentFilePath = filePath

        recorder = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        ).apply {
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setVideoEncoder(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                MediaRecorder.VideoEncoder.HEVC else MediaRecorder.VideoEncoder.H264)
            setVideoSize(width, height)
            setVideoFrameRate(30)
            setVideoEncodingBitRate(50_000_000)
            setOutputFile(filePath)
            prepare()
        }

        recordingSurface = recorder!!.surface
        return recordingSurface!!
    }

    // ── Recording lifecycle ───────────────────────────────────────────────────

    fun startRecording() {
        recorder?.start()
        _videoState.value = VideoState.Recording(System.currentTimeMillis(), currentFilePath!!)
        Log.d(TAG, "Recording started → $currentFilePath")
    }

    fun pauseRecording() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            recorder?.pause()
            _videoState.value = VideoState.Paused(currentFilePath!!)
        }
    }

    fun resumeRecording() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            recorder?.resume()
            val startMs = (_videoState.value as? VideoState.Paused)?.let { System.currentTimeMillis() }
                ?: System.currentTimeMillis()
            _videoState.value = VideoState.Recording(startMs, currentFilePath!!)
        }
    }

    fun stopRecording() {
        runCatching { recorder?.stop() }.onFailure { Log.w(TAG, "Stop failed: ${it.message}") }
        recorder?.release(); recorder = null
        recordingSurface?.release(); recordingSurface = null
        val path = currentFilePath ?: ""
        if (path.isNotEmpty()) addToMediaStore(path)
        _videoState.value = VideoState.Saved(path)
        Log.d(TAG, "Recording saved → $path")
    }

    // ── Camera2 request builder ───────────────────────────────────────────────

    fun buildRecordRequest(
        device: CameraDevice,
        previewSurface: Surface,
        params: CaptureParameters
    ): CaptureRequest =
        device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
            addTarget(previewSurface)
            recordingSurface?.let { addTarget(it) }

            // Apply LOG tone curve
            set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE)
            set(CaptureRequest.TONEMAP_CURVE, LogProfile.tonemapCurve(logProfile))

            // Stabilisation
            set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON)

            // Exposure
            if (params.isAutoExposure) {
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, android.util.Range(30, 30))
            } else {
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                set(CaptureRequest.SENSOR_EXPOSURE_TIME, params.shutterSpeedNs)
                set(CaptureRequest.SENSOR_SENSITIVITY, params.iso)
            }
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
        }.build()

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun createOutputFilePath(): String {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)!!
            .also { it.mkdirs() }
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return "${dir.absolutePath}/NITRO_VID_$ts.mp4"
    }

    private fun addToMediaStore(filePath: String) {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, filePath.substringAfterLast("/"))
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.DATA, filePath)
        }
        context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
    }

    fun isRecording() = _videoState.value is VideoState.Recording
    fun isPaused() = _videoState.value is VideoState.Paused

    fun release() {
        runCatching { if (isRecording() || isPaused()) stopRecording() }
        recorder?.release(); recorder = null
        recordingSurface?.release(); recordingSurface = null
    }
}
