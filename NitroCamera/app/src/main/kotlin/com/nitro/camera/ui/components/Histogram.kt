package com.nitro.camera.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun Histogram(
    data: FloatArray,
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier
            .size(120.dp, 60.dp)
            .background(Color.Black.copy(alpha = 0.5f))
    ) {
        if (data.isEmpty()) return@Canvas
        val barWidth = size.width / data.size
        data.forEachIndexed { i, value ->
            val barHeight = size.height * value
            drawRect(
                color = Color.White.copy(alpha = 0.8f),
                topLeft = Offset(i * barWidth, size.height - barHeight),
                size = Size(barWidth.coerceAtLeast(1f), barHeight)
            )
        }
        // Clipping warning — red bar at right edge
        if (data.takeLast(5).any { it > 0.9f }) {
            drawRect(
                color = Color.Red.copy(alpha = 0.6f),
                topLeft = Offset(size.width - 4f, 0f),
                size = Size(4f, size.height)
            )
        }
    }
}
