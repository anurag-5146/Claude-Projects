package com.nitro.camera.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * DSLR-style vertical scroll dial.
 *
 * Displays a column of value labels — current value centred and bright,
 * adjacent values fading out. Drag up/down to change value, with
 * haptic tick on every step. Mirrors the feel of a physical camera dial.
 */
@Composable
fun <T> ProDial(
    label: String,
    values: List<T>,
    currentIndex: Int,
    formatter: (T) -> String,
    onIndexChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    visibleItems: Int = 5
) {
    val haptic = LocalHapticFeedback.current
    var dragAccumulator by remember { mutableFloatStateOf(0f) }
    val itemHeightPx = 44f   // pixels per step

    Box(
        modifier = modifier
            .width(80.dp)
            .height((visibleItems * 44).dp)
            .background(Color(0xFF111111), RoundedCornerShape(12.dp))
            .pointerInput(currentIndex, values.size) {
                detectVerticalDragGestures(
                    onDragEnd = { dragAccumulator = 0f },
                    onDragCancel = { dragAccumulator = 0f }
                ) { _, dragAmount ->
                    dragAccumulator -= dragAmount          // negative = drag up = increase index
                    val steps = (dragAccumulator / itemHeightPx).roundToInt()
                    if (steps != 0) {
                        dragAccumulator -= steps * itemHeightPx
                        val newIdx = (currentIndex + steps).coerceIn(0, values.lastIndex)
                        if (newIdx != currentIndex) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onIndexChange(newIdx)
                        }
                    }
                }
            }
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Label
            Text(
                text = label,
                color = Color(0xFF888888),
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp,
                modifier = Modifier.padding(bottom = 2.dp)
            )

            // Visible value rows
            val half = visibleItems / 2
            for (offset in -half..half) {
                val idx = currentIndex + offset
                val inRange = idx in values.indices
                val text = if (inRange) formatter(values[idx]) else ""
                val isCurrent = offset == 0
                val alpha = when (abs(offset)) {
                    0 -> 1f; 1 -> 0.55f; 2 -> 0.25f; else -> 0.08f
                }

                // Centre tick line
                if (isCurrent) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(Color(0xFFFFD700).copy(alpha = 0.6f))
                    )
                }

                Text(
                    text = text,
                    color = if (isCurrent) Color(0xFFFFD700) else Color.White,
                    fontSize = if (isCurrent) 16.sp else 12.sp,
                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp)
                        .wrapContentHeight()
                        .alpha(alpha)
                )

                if (isCurrent) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(Color(0xFFFFD700).copy(alpha = 0.6f))
                    )
                }
            }
        }
    }
}

// ── Pre-built dial value lists ────────────────────────────────────────────────

val ISO_VALUES = listOf(50, 64, 80, 100, 125, 160, 200, 250, 320, 400, 500,
    640, 800, 1000, 1250, 1600, 2000, 2500, 3200, 4000, 5000, 6400)

val SHUTTER_VALUES_NS: List<Long> = listOf(
    1_000_000_000L / 8000, 1_000_000_000L / 4000, 1_000_000_000L / 2000,
    1_000_000_000L / 1000, 1_000_000_000L / 500,  1_000_000_000L / 250,
    1_000_000_000L / 125,  1_000_000_000L / 60,   1_000_000_000L / 30,
    1_000_000_000L / 15,   1_000_000_000L / 8,    1_000_000_000L / 4,
    1_000_000_000L / 2,    1_000_000_000L,        2_000_000_000L,
    4_000_000_000L,        8_000_000_000L,        15_000_000_000L,
    30_000_000_000L
)

val WB_MODES = listOf(
    android.hardware.camera2.CameraMetadata.CONTROL_AWB_MODE_AUTO,
    android.hardware.camera2.CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT,
    android.hardware.camera2.CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT,
    android.hardware.camera2.CameraMetadata.CONTROL_AWB_MODE_SHADE,
    android.hardware.camera2.CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT,
    android.hardware.camera2.CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT
)

val WB_LABELS = listOf("AUTO", "SUN", "CLOUD", "SHADE", "TUNGSTEN", "FLUOR")

fun formatShutterNs(ns: Long): String {
    val s = ns / 1_000_000_000.0
    return if (s >= 1.0) "%.0fs".format(s) else "1/${(1.0 / s).roundToInt()}"
}
