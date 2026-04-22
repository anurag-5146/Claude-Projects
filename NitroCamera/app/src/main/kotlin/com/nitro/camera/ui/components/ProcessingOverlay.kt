package com.nitro.camera.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nitro.camera.viewmodel.ProcessingState

@Composable
fun ProcessingOverlay(
    state: ProcessingState,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    when (state) {
        is ProcessingState.Capturing -> CaptureProgress(state, modifier)
        is ProcessingState.Processing -> ProcessProgress(state, modifier)
        is ProcessingState.Done -> ResultBadge(state, onDismiss, modifier)
        is ProcessingState.Error -> ErrorBadge(state.message, onDismiss, modifier)
        ProcessingState.Idle -> Unit
    }
}

@Composable
private fun CaptureProgress(state: ProcessingState.Capturing, modifier: Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(state.label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { if (state.total > 0) state.current.toFloat() / state.total else 0f },
                modifier = Modifier.fillMaxWidth(0.6f).height(4.dp),
                color = Color(0xFFFFD700),
                trackColor = Color.White.copy(alpha = 0.3f),
                strokeCap = StrokeCap.Round
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "${state.current}/${state.total}",
                color = Color.Gray,
                fontSize = 11.sp
            )
        }
    }
}

@Composable
private fun ProcessProgress(state: ProcessingState.Processing, modifier: Modifier) {
    val animatedProgress by animateFloatAsState(
        targetValue = state.progress,
        animationSpec = tween(300),
        label = "progress"
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(state.stage, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { animatedProgress },
                modifier = Modifier.fillMaxWidth(0.6f).height(4.dp),
                color = Color(0xFFFFD700),
                trackColor = Color.White.copy(alpha = 0.3f),
                strokeCap = StrokeCap.Round
            )
            Spacer(Modifier.height(4.dp))
            Text("${(animatedProgress * 100).toInt()}%", color = Color.Gray, fontSize = 11.sp)
        }
    }
}

@Composable
private fun ResultBadge(state: ProcessingState.Done, onDismiss: () -> Unit, modifier: Modifier) {
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(2500)
        onDismiss()
    }
    Row(
        modifier = modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .background(Color(0xFF1A1A1A).copy(alpha = 0.9f), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("✓", color = Color(0xFF4CAF50), fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text("Saved in ${state.latencyMs}ms", color = Color.White, fontSize = 13.sp)
    }
}

@Composable
private fun ErrorBadge(message: String, onDismiss: () -> Unit, modifier: Modifier) {
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(3000)
        onDismiss()
    }
    Row(
        modifier = modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .background(Color(0xFF3A1A1A).copy(alpha = 0.9f), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("✕", color = Color.Red, fontSize = 16.sp)
        Text(message, color = Color.White, fontSize = 12.sp)
    }
}
