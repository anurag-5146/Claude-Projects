package com.nitro.camera.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke

/**
 * Draws a simple focus-peaking ring around the active AF region.
 * In a full implementation this would highlight high-frequency edges
 * detected via Sobel on the preview frame (requires RenderScript/Vulkan).
 * This placeholder renders a focus bracket for UX demonstration.
 */
@Composable
fun FocusPeakingOverlay(
    modifier: Modifier = Modifier,
    focusDistance: Float = 0f
) {
    Canvas(modifier = modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val radius = (size.width * 0.08f).coerceAtLeast(40f)

        drawCircle(
            color = Color.Red.copy(alpha = 0.85f),
            radius = radius,
            center = androidx.compose.ui.geometry.Offset(cx, cy),
            style = Stroke(width = 2f)
        )
        // Corner brackets
        val arm = radius * 0.4f
        val left = cx - radius; val right = cx + radius
        val top = cy - radius; val bottom = cy + radius
        listOf(
            Pair(left to top, listOf(left + arm to top, left to top + arm)),
            Pair(right to top, listOf(right - arm to top, right to top + arm)),
            Pair(left to bottom, listOf(left + arm to bottom, left to bottom - arm)),
            Pair(right to bottom, listOf(right - arm to bottom, right to bottom - arm))
        ).forEach { (corner, arms) ->
            arms.forEach { (ex, ey) ->
                drawLine(
                    color = Color.Red.copy(alpha = 0.9f),
                    start = androidx.compose.ui.geometry.Offset(corner.first, corner.second),
                    end = androidx.compose.ui.geometry.Offset(ex, ey),
                    strokeWidth = 3f
                )
            }
        }
    }
}
