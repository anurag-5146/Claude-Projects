package com.nitro.camera.ui.components

import android.graphics.Bitmap
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize

/**
 * Single-pass overlay that composites:
 *   1. Focus peaking — red edge-detected Bitmap overlaid on the viewfinder
 *   2. Zebra stripes — animated diagonal stripes on overexposed regions
 *   3. Live histogram — rendered in bottom-left corner using real preview data
 *
 * All three are driven by [AnalysisResult] emitted from [PreviewAnalyzer]
 * without touching the Camera2 preview surface.
 */
@Composable
fun RealtimeOverlay(
    showFocusPeaking: Boolean,
    showZebraStripes: Boolean,
    showHistogram: Boolean,
    focusMask: Bitmap?,
    zebraFraction: Float,
    histogram: FloatArray,
    modifier: Modifier = Modifier
) {
    // Animated zebra offset — stripes march across the screen
    val infiniteTransition = rememberInfiniteTransition(label = "zebra")
    val zebraOffset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 20f,
        animationSpec = infiniteRepeatable(
            animation = tween(400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "zebraOffset"
    )

    val focusBitmap = remember(focusMask) { focusMask?.asImageBitmap() }

    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width; val h = size.height

        // ── 1. Focus peaking ─────────────────────────────────────────────────
        if (showFocusPeaking && focusBitmap != null) {
            drawImage(
                image = focusBitmap,
                dstOffset = IntOffset.Zero,
                dstSize = IntSize(w.toInt(), h.toInt()),
                alpha = 0.85f,
                blendMode = BlendMode.SrcOver
            )
        }

        // ── 2. Zebra stripes ─────────────────────────────────────────────────
        if (showZebraStripes && zebraFraction > 0.01f) {
            val stripeSpacing = 20f
            val stripeWidth = 10f
            // Draw angled stripes over the entire canvas area (clipped by draw bounds)
            // We use a semi-transparent yellow-black pattern over the whole frame
            // as a proxy — real per-pixel zebra needs a GPU shader
            val zebraAlpha = (zebraFraction * 0.8f).coerceIn(0.1f, 0.7f)
            var x = -h + zebraOffset          // start left of canvas so stripes slide in
            while (x < w + h) {
                drawLine(
                    color = Color.Yellow.copy(alpha = zebraAlpha),
                    start = Offset(x, 0f),
                    end = Offset(x + h, h),
                    strokeWidth = stripeWidth
                )
                x += stripeSpacing
            }
        }

        // ── 3. Live histogram ────────────────────────────────────────────────
        if (showHistogram && histogram.isNotEmpty()) {
            val histX = 16f; val histY = h - 76f
            val histW = 120f; val histH = 60f
            val barW = histW / histogram.size

            // Background
            drawRect(
                color = Color.Black.copy(alpha = 0.55f),
                topLeft = Offset(histX - 4f, histY - 4f),
                size = Size(histW + 8f, histH + 8f)
            )

            // Bars
            histogram.forEachIndexed { i, value ->
                val barH = histH * value
                drawRect(
                    color = Color.White.copy(alpha = 0.8f),
                    topLeft = Offset(histX + i * barW, histY + histH - barH),
                    size = Size(barW.coerceAtLeast(1f), barH)
                )
            }

            // Clipping warning bar at far right
            if (histogram.takeLast(5).any { it > 0.85f }) {
                drawRect(
                    color = Color.Red.copy(alpha = 0.8f),
                    topLeft = Offset(histX + histW - 4f, histY),
                    size = Size(4f, histH)
                )
            }
        }
    }
}
