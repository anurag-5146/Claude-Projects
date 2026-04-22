package com.nitro.camera.processing

import android.content.Context
import android.graphics.Bitmap
import android.util.Log

private const val TAG = "PostProcessor"

/**
 * Neural post-processing orchestrator.
 * Runs on every saved capture across all modes (PHOTO/HDR/NIGHT/PORTRAIT).
 *
 * Pipeline:
 *   1. NAFNet denoise (same res in/out)
 *   2. Real-ESRGAN 2× super-res (doubles resolution)
 *
 * Both stages gracefully pass through if their TFLite asset is missing,
 * so the app ships and runs with or without models bundled.
 */
class PostProcessor(context: Context) {

    private val denoiser = NAFNetProcessor(context).also { it.init() }
    private val upscaler = SuperResProcessor(context).also { it.init() }

    /** True if at least one neural model is active. When false, callers should skip post-processing entirely. */
    val isActive: Boolean get() = denoiser.isActive || upscaler.isActive

    /**
     * Run denoise → super-res on [bitmap]. Emits progress updates via [onProgress].
     * Returns a new Bitmap (caller owns). Input [bitmap] is recycled if a new one is produced.
     */
    suspend fun process(
        bitmap: Bitmap,
        onProgress: (Float, String) -> Unit = { _, _ -> }
    ): Bitmap {
        val t0 = System.currentTimeMillis()

        onProgress(0.0f, "Neural denoise…")
        val denoised = denoiser.denoise(bitmap)
        val tDenoise = System.currentTimeMillis()
        Log.d(TAG, "Denoise ${tDenoise - t0} ms")

        if (denoised !== bitmap) bitmap.recycle()

        onProgress(0.5f, "Neural super-res…")
        val upscaled = upscaler.upscale(denoised)
        Log.d(TAG, "Super-res ${System.currentTimeMillis() - tDenoise} ms")

        if (upscaled !== denoised) denoised.recycle()

        onProgress(1.0f, "Saving…")
        return upscaled
    }

    fun close() {
        denoiser.close()
        upscaler.close()
    }
}
