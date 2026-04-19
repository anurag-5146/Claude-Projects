package com.nitro.camera.camera

import android.hardware.camera2.CaptureResult

sealed class CameraState {
    object Closed : CameraState()
    object Opening : CameraState()
    object Ready : CameraState()
    data class Error(val message: String) : CameraState()
}

data class CaptureParameters(
    val iso: Int = 100,
    val shutterSpeedNs: Long = 1_000_000_000L / 60,
    val awbMode: Int = android.hardware.camera2.CameraMetadata.CONTROL_AWB_MODE_AUTO,
    val focusDistance: Float = 0f,
    val isAutoExposure: Boolean = true,
    val isAutoFocus: Boolean = true,
    val isAutoWhiteBalance: Boolean = true,
    val zoomRatio: Float = 1f
)

data class FrameMetrics(
    val iso: Int = 0,
    val shutterSpeedNs: Long = 0,
    val focusDistance: Float = 0f,
    val exposureCompensation: Float = 0f,
    val captureLatencyMs: Long = 0
)

data class CameraCapabilities(
    val supportedIsoRange: android.util.Range<Int> = android.util.Range(100, 3200),
    val supportedExposureRange: android.util.Range<Long> = android.util.Range(1_000_000L, 1_000_000_000L),
    val supportsRaw: Boolean = false,
    val supportsZsl: Boolean = false,
    val maxZoom: Float = 1f,
    val sensorSize: android.util.Size = android.util.Size(0, 0)
)
