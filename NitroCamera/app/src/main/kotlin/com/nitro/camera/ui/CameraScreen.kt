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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nitro.camera.camera.CameraState
import com.nitro.camera.camera.VideoState
import com.nitro.camera.processing.LogProfile
import com.nitro.camera.ui.components.*
import com.nitro.camera.viewmodel.CameraViewModel
import com.nitro.camera.viewmodel.CaptureMode
import com.nitro.camera.viewmodel.ProcessingState
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
fun CameraScreen(viewModel: CameraViewModel) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val haptic = LocalHapticFeedback.current
    var showProDials by remember { mutableStateOf(false) }
    var recordingSeconds by remember { mutableIntStateOf(0) }

    // Recording timer
    LaunchedEffect(ui.videoState) {
        if (ui.videoState is VideoState.Recording) {
            recordingSeconds = 0
            while (isActive) { delay(1000); recordingSeconds++ }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {

        // ── Viewfinder ────────────────────────────────────────────────────────
        Viewfinder(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { _, _, zoom, _ ->
                        viewModel.setZoom(ui.params.zoomRatio * zoom)
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(onDoubleTap = { showProDials = !showProDials })
                },
            onSurfaceReady = { texture, w, h -> viewModel.startCamera(texture, w, h) }
        )

        // ── Real-time overlay (focus peaking + zebra + live histogram) ─────────
        RealtimeOverlay(
            showFocusPeaking = ui.showFocusPeaking,
            showZebraStripes = ui.showZebraStripes,
            showHistogram = ui.showHistogram,
            focusMask = ui.focusMask,
            zebraFraction = ui.zebraFraction,
            histogram = ui.liveHistogram,
            modifier = Modifier.fillMaxSize()
        )

        // ── Scene badge ───────────────────────────────────────────────────────
        SceneBadge(
            scene = ui.detectedScene,
            confidence = ui.sceneConfidence,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 16.dp)
        )

        // ── Top-right HUD toggles ─────────────────────────────────────────────
        Column(
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 16.dp, end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalAlignment = Alignment.End
        ) {
            HudToggleChip("⚡ACTION", ui.params.actionMode) { viewModel.setActionMode(!ui.params.actionMode) }
            HudToggleChip("PEAK", ui.showFocusPeaking)  { viewModel.toggleFocusPeaking() }
            HudToggleChip("ZEBRA", ui.showZebraStripes) { viewModel.toggleZebraStripes() }
            HudToggleChip("HIST", ui.showHistogram)     { viewModel.toggleHistogram() }
        }

        // ── Recording indicator ───────────────────────────────────────────────
        AnimatedVisibility(
            visible = ui.videoState is VideoState.Recording,
            modifier = Modifier.align(Alignment.TopStart).padding(16.dp),
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            RecordingBadge(seconds = recordingSeconds)
        }

        // ── Processing overlay ────────────────────────────────────────────────
        if (ui.processingState !is ProcessingState.Idle) {
            ProcessingOverlay(
                state = ui.processingState,
                onDismiss = { viewModel.dismissResult() },
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 72.dp)
            )
        }

        // ── Pro dials (double-tap to reveal) ──────────────────────────────────
        AnimatedVisibility(
            visible = showProDials && ui.captureMode != CaptureMode.VIDEO,
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 8.dp),
            enter = slideInHorizontally { it },
            exit = slideOutHorizontally { it }
        ) {
            ProDialPanel(ui = ui, viewModel = viewModel)
        }

        // ── Portrait blur slider ──────────────────────────────────────────────
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

        // ── LOG profile selector (video mode only) ────────────────────────────
        AnimatedVisibility(
            visible = ui.captureMode == CaptureMode.VIDEO,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 180.dp),
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            LogProfileSelector(
                current = ui.videoLogProfile,
                onSelect = { viewModel.setLogProfile(it) }
            )
        }

        // ── Mode selector ─────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 120.dp)
                .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
                .padding(horizontal = 6.dp, vertical = 3.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            CaptureMode.entries.forEach { mode ->
                val selected = ui.captureMode == mode
                TextButton(
                    onClick = { viewModel.setCaptureMode(mode) },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = if (selected) Color(0xFFFFD700) else Color.White
                    ),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Text(mode.name, fontSize = 11.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }

        // ── Capture / Record button ───────────────────────────────────────────
        Box(modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp)) {
            if (ui.captureMode == CaptureMode.VIDEO) {
                RecordButton(
                    videoState = ui.videoState,
                    onToggle = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        viewModel.toggleRecording()
                    },
                    onPause = { viewModel.pauseRecording() }
                )
            } else {
                CaptureButton(
                    isCapturing = ui.processingState !is ProcessingState.Idle,
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        viewModel.capture()
                    }
                )
            }
        }

        // ── Photo Preview Dialog (right of capture button) ────────────────────
        if (ui.lastCapturedUri.isNotEmpty() && ui.captureMode != CaptureMode.VIDEO) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = 32.dp, end = 24.dp)
            ) {
                PhotoPreviewDialog(
                    uri = ui.lastCapturedUri,
                    onDelete = { viewModel.deleteLastPhoto() },
                    onRetake = { viewModel.clearPreview() },
                    onShare = {}  // Share already handled in composable
                )
            }
        }

        // ── Camera error ──────────────────────────────────────────────────────
        if (ui.cameraState is CameraState.Error) {
            Box(Modifier.fillMaxSize().background(Color.Black), Alignment.Center) {
                Text("Camera error: ${(ui.cameraState as CameraState.Error).message}",
                    color = Color.Red, fontSize = 14.sp)
            }
        }
    }
}

// ── Pro dial panel ────────────────────────────────────────────────────────────

@Composable
private fun ProDialPanel(ui: com.nitro.camera.viewmodel.UiState, viewModel: CameraViewModel) {
    val isoIdx = remember(ui.params.iso) { ISO_VALUES.indexOfFirst { it >= ui.params.iso }.coerceAtLeast(0) }
    val ssIdx = remember(ui.params.shutterSpeedNs) { SHUTTER_VALUES_NS.indexOfFirst { it >= ui.params.shutterSpeedNs }.coerceAtLeast(0) }
    val wbIdx = remember(ui.params.awbMode) { WB_MODES.indexOfFirst { it == ui.params.awbMode }.coerceAtLeast(0) }

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.End
    ) {
        ProDial(
            label = "ISO",
            values = ISO_VALUES,
            currentIndex = isoIdx,
            formatter = { it.toString() },
            onIndexChange = { viewModel.setISO(ISO_VALUES[it]) }
        )
        ProDial(
            label = "SS",
            values = SHUTTER_VALUES_NS,
            currentIndex = ssIdx,
            formatter = { formatShutterNs(it) },
            onIndexChange = { viewModel.setShutterSpeed(SHUTTER_VALUES_NS[it]) }
        )
        ProDial(
            label = "WB",
            values = WB_MODES,
            currentIndex = wbIdx,
            formatter = { WB_LABELS[WB_MODES.indexOf(it)] },
            onIndexChange = { viewModel.setWhiteBalance(WB_MODES[it]) }
        )
    }
}

// ── Buttons ───────────────────────────────────────────────────────────────────

@Composable
private fun CaptureButton(isCapturing: Boolean, onClick: () -> Unit) {
    Box(contentAlignment = Alignment.Center) {
        Box(Modifier.size(80.dp).border(3.dp, Color.White, CircleShape))
        Button(
            onClick = onClick,
            enabled = !isCapturing,
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.White,
                disabledContainerColor = Color.Gray
            ),
            modifier = Modifier.size(64.dp),
            contentPadding = PaddingValues(0.dp)
        ) {
            if (isCapturing) CircularProgressIndicator(Modifier.size(24.dp), color = Color.White, strokeWidth = 2.dp)
        }
    }
}

@Composable
private fun RecordButton(videoState: VideoState, onToggle: () -> Unit, onPause: () -> Unit) {
    val isRecording = videoState is VideoState.Recording
    val isPaused = videoState is VideoState.Paused
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        if (isRecording) {
            // Pause button
            IconButton(onClick = onPause,
                modifier = Modifier.size(44.dp).background(Color.White.copy(alpha = 0.2f), CircleShape)) {
                Text("⏸", fontSize = 18.sp)
            }
        }
        // Main record / stop button
        Box(contentAlignment = Alignment.Center) {
            Box(Modifier.size(80.dp).border(3.dp, if (isRecording || isPaused) Color.Red else Color.White, CircleShape))
            Button(
                onClick = onToggle,
                shape = if (isRecording || isPaused) RoundedCornerShape(8.dp) else CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = if (isRecording) Color.Red else Color.White),
                modifier = Modifier.size(56.dp),
                contentPadding = PaddingValues(0.dp)
            ) {}
        }
    }
}

// ── Misc small composables ────────────────────────────────────────────────────

@Composable
private fun RecordingBadge(seconds: Int) {
    Row(
        modifier = Modifier
            .background(Color.Red, RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(Modifier.size(8.dp).background(Color.White, CircleShape))
        Text("%02d:%02d".format(seconds / 60, seconds % 60), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun LogProfileSelector(current: LogProfile.Profile, onSelect: (LogProfile.Profile) -> Unit) {
    Row(
        modifier = Modifier
            .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        LogProfile.Profile.entries.forEach { profile ->
            TextButton(
                onClick = { onSelect(profile) },
                colors = ButtonDefaults.textButtonColors(
                    contentColor = if (current == profile) Color(0xFFFFD700) else Color.Gray
                ),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                modifier = Modifier.height(30.dp)
            ) {
                Text(profile.name, fontSize = 11.sp, fontWeight = if (current == profile) FontWeight.Bold else FontWeight.Normal)
            }
        }
    }
}

@Composable
private fun HudToggleChip(label: String, active: Boolean, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        colors = ButtonDefaults.textButtonColors(contentColor = if (active) Color(0xFFFFD700) else Color.Gray),
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
        modifier = Modifier.height(24.dp)
    ) {
        Text(label, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}
