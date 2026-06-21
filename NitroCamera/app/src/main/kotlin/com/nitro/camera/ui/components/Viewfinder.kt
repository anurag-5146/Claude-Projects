package com.nitro.camera.ui.components

import android.graphics.SurfaceTexture
import android.view.TextureView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Camera preview surface.
 *
 * The TextureView is sized so the preview's native aspect ratio is preserved
 * (no stretching). We default to 4:3 — the natural ratio of most phone sensors —
 * and let the caller override. The view is centred in the parent; excess space
 * is letterboxed (black bars), which is correct DSLR behaviour.
 *
 * [onSurfaceReady] receives the SurfaceTexture plus the actual view size in
 * pixels so the camera controller can pick a matching preview resolution.
 */
@Composable
fun Viewfinder(
    modifier: Modifier = Modifier,
    aspectRatio: Float = 4f / 3f,   // width / height for landscape; flipped internally
    onSurfaceReady: (SurfaceTexture, Int, Int) -> Unit
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AndroidView(
            // Portrait layout: swap ratio so TextureView is tall-and-narrow.
            modifier = Modifier.aspectRatio(1f / aspectRatio),
            factory = { ctx ->
                TextureView(ctx).apply {
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, w: Int, h: Int) {
                            onSurfaceReady(surface, w, h)
                        }
                        override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) = Unit
                        override fun onSurfaceTextureDestroyed(s: SurfaceTexture) = true
                        override fun onSurfaceTextureUpdated(s: SurfaceTexture) = Unit
                    }
                }
            }
        )
    }
}
