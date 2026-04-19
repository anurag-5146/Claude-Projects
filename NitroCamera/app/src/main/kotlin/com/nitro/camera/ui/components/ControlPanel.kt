package com.nitro.camera.ui.components

import android.hardware.camera2.CameraMetadata
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nitro.camera.camera.CameraCapabilities
import com.nitro.camera.camera.CaptureParameters
import com.nitro.camera.camera.FrameMetrics
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt

@Composable
fun ControlPanel(
    params: CaptureParameters,
    metrics: FrameMetrics,
    capabilities: CameraCapabilities,
    onAutoToggle: (Boolean) -> Unit,
    onIsoChange: (Int) -> Unit,
    onShutterChange: (Long) -> Unit,
    onFocusChange: (Float) -> Unit,
    onWbChange: (Int) -> Unit,
    onZoomChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Live metrics readout
        MetricsBar(metrics = metrics, params = params)

        // Auto / Manual toggle
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("AUTO", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Switch(
                checked = params.isAutoExposure,
                onCheckedChange = onAutoToggle,
                colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFFFFD700))
            )
        }

        if (!params.isAutoExposure) {
            // ISO dial
            DialRow(
                label = "ISO",
                displayValue = params.iso.toString(),
                onDrag = { delta ->
                    val step = if (delta > 0) 1.1f else 0.9f
                    val newIso = (params.iso * step)
                        .roundToInt()
                        .coerceIn(capabilities.supportedIsoRange.lower, capabilities.supportedIsoRange.upper)
                    onIsoChange(snapToIsoStep(newIso))
                }
            )

            // Shutter speed dial
            DialRow(
                label = "SS",
                displayValue = formatShutter(params.shutterSpeedNs),
                onDrag = { delta ->
                    val factor = if (delta > 0) 1.2f else 0.8f
                    val newNs = (params.shutterSpeedNs * factor)
                        .toLong()
                        .coerceIn(capabilities.supportedExposureRange.lower, capabilities.supportedExposureRange.upper)
                    onShutterChange(newNs)
                }
            )
        }

        if (!params.isAutoFocus) {
            // Focus distance dial
            DialRow(
                label = "FOCUS",
                displayValue = if (params.focusDistance == 0f) "∞" else "%.2fm".format(1f / params.focusDistance),
                onDrag = { delta ->
                    val newFocus = (params.focusDistance + delta * 0.01f).coerceIn(0f, 10f)
                    onFocusChange(newFocus)
                }
            )
        }

        // White balance chips
        WbSelector(currentMode = params.awbMode, onModeSelected = onWbChange)

        // Zoom slider
        if (capabilities.maxZoom > 1f) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("ZOOM", color = Color.White, fontSize = 11.sp)
                Slider(
                    value = params.zoomRatio,
                    onValueChange = onZoomChange,
                    valueRange = 1f..capabilities.maxZoom,
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(thumbColor = Color(0xFFFFD700), activeTrackColor = Color(0xFFFFD700))
                )
                Text("%.1fx".format(params.zoomRatio), color = Color.White, fontSize = 11.sp, modifier = Modifier.width(36.dp))
            }
        }
    }
}

@Composable
private fun MetricsBar(metrics: FrameMetrics, params: CaptureParameters) {
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth()
    ) {
        MetricChip("ISO ${if (params.isAutoExposure) metrics.iso else params.iso}")
        MetricChip(formatShutter(if (params.isAutoExposure) metrics.shutterSpeedNs else params.shutterSpeedNs))
        MetricChip(if (metrics.focusDistance == 0f) "∞" else "%.1fm".format(1f / metrics.focusDistance.coerceAtLeast(0.001f)))
        if (metrics.captureLatencyMs > 0) MetricChip("${metrics.captureLatencyMs}ms")
    }
}

@Composable
private fun MetricChip(text: String) {
    Text(
        text = text,
        color = Color(0xFFFFD700),
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

@Composable
private fun DialRow(label: String, displayValue: String, onDrag: (Float) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(Unit) {
                detectHorizontalDragGestures { _, dragAmount ->
                    onDrag(dragAmount)
                }
            }
    ) {
        Text(label, color = Color.Gray, fontSize = 11.sp, modifier = Modifier.width(48.dp))
        Text(
            displayValue,
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold
        )
        Text("← drag →", color = Color.Gray, fontSize = 10.sp)
    }
}

@Composable
private fun WbSelector(currentMode: Int, onModeSelected: (Int) -> Unit) {
    val modes = listOf(
        "AUTO" to CameraMetadata.CONTROL_AWB_MODE_AUTO,
        "DAY" to CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT,
        "CLOUD" to CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT,
        "SHADE" to CameraMetadata.CONTROL_AWB_MODE_SHADE,
        "TUNGSTEN" to CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT,
        "FLUOR" to CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT
    )
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        modes.forEach { (label, mode) ->
            val selected = currentMode == mode
            TextButton(
                onClick = { onModeSelected(mode) },
                colors = ButtonDefaults.textButtonColors(
                    contentColor = if (selected) Color(0xFFFFD700) else Color.Gray
                ),
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                modifier = Modifier.height(28.dp)
            ) {
                Text(label, fontSize = 10.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
            }
        }
    }
}

private fun formatShutter(ns: Long): String {
    if (ns <= 0) return "–"
    val seconds = ns / 1_000_000_000.0
    return if (seconds >= 1) "%.1fs".format(seconds)
    else "1/${(1.0 / seconds).roundToInt()}"
}

private fun snapToIsoStep(iso: Int): Int {
    val steps = listOf(50, 64, 80, 100, 125, 160, 200, 250, 320, 400, 500, 640, 800, 1000, 1250, 1600, 2000, 2500, 3200, 4000, 5000, 6400)
    return steps.minByOrNull { Math.abs(it - iso) } ?: iso
}
