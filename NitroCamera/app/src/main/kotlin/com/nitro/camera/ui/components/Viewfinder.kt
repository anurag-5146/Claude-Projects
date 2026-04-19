package com.nitro.camera.ui.components

import android.graphics.SurfaceTexture
import android.view.TextureView
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

@Composable
fun Viewfinder(
    modifier: Modifier = Modifier,
    onSurfaceReady: (SurfaceTexture) -> Unit
) {
    var textureView by remember { mutableStateOf<TextureView?>(null) }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            TextureView(ctx).apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, w: Int, h: Int) {
                        onSurfaceReady(surface)
                    }
                    override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) = Unit
                    override fun onSurfaceTextureDestroyed(s: SurfaceTexture) = true
                    override fun onSurfaceTextureUpdated(s: SurfaceTexture) = Unit
                }
                textureView = this
            }
        }
    )
}
