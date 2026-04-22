package com.nitro.camera.ui.components

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage

/**
 * Compact photo preview dialog (thumbnail + 3 actions).
 * Shows beside capture button after photo is taken.
 * Click thumbnail to view full-screen.
 */
@Composable
fun PhotoPreviewDialog(
    uri: String,
    onDelete: () -> Unit,
    onRetake: () -> Unit,
    onShare: () -> Unit
) {
    val ctx = LocalContext.current
    var showLightbox by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .width(160.dp)
            .background(Color.Black.copy(alpha = 0.8f), RoundedCornerShape(12.dp))
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Thumbnail (clickable)
        AsyncImage(
            model = uri,
            contentDescription = "Last captured photo",
            modifier = Modifier
                .size(144.dp)
                .background(Color.Gray, RoundedCornerShape(8.dp))
                .clickable { showLightbox = true },
            contentScale = ContentScale.Crop
        )

        // Action buttons
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(32.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Delete
            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(32.dp).background(Color.Red.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
            ) {
                Text("🗑", fontSize = 14.sp)
            }
            // Share
            IconButton(
                onClick = {
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "image/jpeg"
                        putExtra(Intent.EXTRA_STREAM, Uri.parse(uri))
                    }
                    ctx.startActivity(Intent.createChooser(shareIntent, "Share photo"))
                },
                modifier = Modifier.size(32.dp).background(Color(0xFF1E88E5).copy(alpha = 0.6f), RoundedCornerShape(4.dp))
            ) {
                Text("↗", fontSize = 14.sp)
            }
            // Retake
            IconButton(
                onClick = onRetake,
                modifier = Modifier.size(32.dp).background(Color(0xFF00BCD4).copy(alpha = 0.6f), RoundedCornerShape(4.dp))
            ) {
                Text("↻", fontSize = 14.sp)
            }
        }
    }

    // Full-screen lightbox
    if (showLightbox) {
        Dialog(
            onDismissRequest = { showLightbox = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.95f))
                    .clickable { showLightbox = false },
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(
                    model = uri,
                    contentDescription = "Full-screen photo",
                    modifier = Modifier
                        .fillMaxWidth(0.95f)
                        .clickable { /* prevent dismiss on click */ },
                    contentScale = ContentScale.Fit
                )
            }
        }
    }
}
