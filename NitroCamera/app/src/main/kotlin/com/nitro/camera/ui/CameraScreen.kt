package com.nitro.camera.ui

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nitro.camera.camera.CameraState
import com.nitro.camera.ui.components.*
import com.nitro.camera.viewmodel.CameraViewModel
import com.nitro.camera.viewmodel.CaptureMode
import com.nitro.camera.viewmodel.ProcessingState

@Composable
fun CameraScreen(viewModel: CameraViewModel) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    var showControls by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {

        // ── Viewfinder ──────────────────────────────────────────────────────
        Viewfinder(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { _, _, zoom, _ ->
                        viewModel.setZoom(ui.params.zoomRatio * zoom)
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = { showControls = !showControls }
                    )
                },
            onSurfaceReady = { viewModel.startCamera(it) }
        )

        // ── Focus peaking overlay ────────────────────────────────────────────
        if (ui.showFocusPeaking) {
            FocusPeakingOverlay(
                modifier = Modifier.fillMaxSize(),
                focusDistance = ui.metrics.focusDistance
            )
        }

        // ── Top HUD ──────────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .align(Alignment.TopStart),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Histogram
            if (ui.showHistogram) {
                Histogram(data = FloatArray(256))
            }

            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                HudToggleChip("HIST", ui.showHistogram) { viewModel.toggleHistogram() }
                HudToggleChip("PEAK", ui.showFocusPeaking) { viewModel.toggleFocusPeaking() }
                HudToggleChip("ZEBRA", ui.showZebraStripes) { viewModel.toggleZebraStripes() }
            }
        }

        // ── Scene badge ──────────────────────────────────────────────────────
        SceneBadge(
            scene = ui.detectedScene,
            confidence = ui.sceneConfidence,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 16.dp)
        )

        // ── Processing overlay ───────────────────────────────────────────────
        if (ui.processingState !is ProcessingState.Idle) {
            ProcessingOverlay(
                state = ui.processingState,
                onDismiss = { viewModel.dismissResult() },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 72.dp)
            )
        }

        // ── Portrait blur slider (only in PORTRAIT mode) ─────────────────────
        AnimatedVisibility(
            visible = ui.captureMode == CaptureMode.PORTRAIT,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 180.dp),
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut()
        ) {
            PortraitBlurSlider(
                blurRadius = ui.portraitBlurRadius,
                onBlurChange = { viewModel.setPortraitBlur(it) }
            )
        }

        // ── Mode selector ────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = if (showControls) 280.dp else 120.dp)
                .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            CaptureMode.entries.forEach { mode ->
                val selected = ui.captureMode == mode
                TextButton(
                    onClick = { viewModel.setCaptureMode(mode) },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = if (selected) Color(0xFFFFD700) else Color.White
                    )
                ) {
                    Text(mode.name, fontSize = 12.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }

        // ── Capture button ───────────────────────────────────────────────────
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 36.dp)
        ) {
            CaptureButton(
                isCapturing = ui.processingState !is ProcessingState.Idle,
                onClick = { viewModel.capture() }
            )
        }

        // ── Control panel (slide up on double-tap) ───────────────────────────
        AnimatedVisibility(
            visible = showControls,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically { it },
            exit = slideOutVertically { it }
        ) {
            ControlPanel(
                params = ui.params,
                metrics = ui.metrics,
                capabilities = ui.capabilities,
                onAutoToggle = { viewModel.setAutoMode(it) },
                onIsoChange = { viewModel.setISO(it) },
                onShutterChange = { viewModel.setShutterSpeed(it) },
                onFocusChange = { viewModel.setFocusDistance(it) },
                onWbChange = { viewModel.setWhiteBalance(it) },
                onZoomChange = { viewModel.setZoom(it) },
                modifier = Modifier.fillMaxWidth()
            )
        }

        // ── Camera error state ───────────────────────────────────────────────
        if (ui.cameraState is CameraState.Error) {
            Box(
                modifier = Modifier.fillMaxSize().background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Camera error: ${(ui.cameraState as CameraState.Error).message}",
                    color = Color.Red,
                    fontSize = 14.sp
                )
            }
        }
    }
}

@Composable
private fun CaptureButton(isCapturing: Boolean, onClick: () -> Unit) {
    Box(contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .border(3.dp, Color.White, CircleShape)
        )
        Button(
            onClick = onClick,
            enabled = !isCapturing,
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isCapturing) Color.Gray else Color.White,
                disabledContainerColor = Color.Gray
            ),
            modifier = Modifier.size(64.dp),
            contentPadding = PaddingValues(0.dp)
        ) {
            if (isCapturing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = Color.White,
                    strokeWidth = 2.dp
                )
            }
        }
    }
}

@Composable
private fun HudToggleChip(label: String, active: Boolean, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        colors = ButtonDefaults.textButtonColors(
            contentColor = if (active) Color(0xFFFFD700) else Color.Gray
        ),
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
        modifier = Modifier.height(24.dp)
    ) {
        Text(label, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}
