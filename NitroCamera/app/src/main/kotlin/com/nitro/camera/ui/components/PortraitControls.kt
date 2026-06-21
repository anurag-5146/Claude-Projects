package com.nitro.camera.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nitro.camera.processing.Scene

@Composable
fun PortraitBlurSlider(
    blurRadius: Float,
    onBlurChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp)
            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("BLUR", color = Color.Gray, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(36.dp))
        Slider(
            value = blurRadius,
            onValueChange = onBlurChange,
            valueRange = 2f..40f,
            modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(
                thumbColor = Color(0xFFFFD700),
                activeTrackColor = Color(0xFFFFD700),
                inactiveTrackColor = Color.Gray
            )
        )
        Text("%.0f".format(blurRadius), color = Color.White, fontSize = 12.sp, modifier = Modifier.width(28.dp))
    }
}

@Composable
fun SceneBadge(
    scene: Scene,
    confidence: Float,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = scene != Scene.GENERAL,
        modifier = modifier,
        enter = fadeIn() + slideInVertically { -it },
        exit = fadeOut()
    ) {
        Row(
            modifier = Modifier
                .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(20.dp))
                .padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Text(scene.emoji, fontSize = 14.sp)
            Text(
                scene.label,
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                "${(confidence * 100).toInt()}%",
                color = Color.Gray,
                fontSize = 10.sp
            )
        }
    }
}
