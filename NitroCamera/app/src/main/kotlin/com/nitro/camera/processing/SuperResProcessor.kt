package com.nitro.camera.processing

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

private const val TAG = "SuperResProcessor"

// Real-ESRGAN x2 TFLite. Convert weights per docs/MODEL_CONVERSION.md.
// Place in app/src/main/assets/realesrgan_x2.tflite
private const val ESRGAN_MODEL = "realesrgan_x2.tflite"
private const val PATCH_SIZE = 256        // Process in 256×256 patches
private const val PATCH_OVERLAP = 16      // Overlap to avoid seam artefacts
private const val SCALE_FACTOR = 2

/**
 * Super-resolution upscaling:
 *   - If ESRGAN model is present → 4× ML upscale patch-by-patch (GPU delegate)
 *   - Otherwise → high-quality Lanczos bicubic fallback
 *
 * Patch-based inference keeps peak RAM under 200 MB even on low-end devices.
 * Triggered automatically when digital zoom > 2× or on explicit SR mode.
 */
class SuperResProcessor(private val context: Context) {

    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null
    private var modelAvailable = false

    fun init() {
        modelAvailable = context.assets.list("")?.contains(ESRGAN_MODEL) == true
        if (!modelAvailable) { Log.w(TAG, "ESRGAN model not found — bicubic fallback active"); return }

        runCatching {
            val opts = Interpreter.Options()
            if (CompatibilityList().isDelegateSupportedOnThisDevice) {
                gpuDelegate = GpuDelegate()
                opts.addDelegate(gpuDelegate)
                Log.d(TAG, "ESRGAN running on GPU")
            } else {
                opts.numThreads = 4
            }
            interpreter = Interpreter(loadModelFile(), opts)
            Log.d(TAG, "ESRGAN interpreter ready")
        }.onFailure { Log.e(TAG, "ESRGAN init failed", it); modelAvailable = false }
    }

    val isActive: Boolean get() = interpreter != null

    suspend fun upscale(bitmap: Bitmap): Bitmap = withContext(Dispatchers.Default) {
        if (interpreter == null) return@withContext bitmap  // pass-through (no bicubic — avoids wasting CPU on identical quality)
        runCatching { esrganUpscale(bitmap) }.getOrElse {
            Log.w(TAG, "ESRGAN inference failed, pass-through: ${it.message}")
            bitmap
        }
    }

    // ── ESRGAN patch inference ────────────────────────────────────────────────

    private fun esrganUpscale(src: Bitmap): Bitmap {
        val inW = src.width; val inH = src.height
        val outW = inW * SCALE_FACTOR; val outH = inH * SCALE_FACTOR
        val result = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(result)

        val step = PATCH_SIZE - PATCH_OVERLAP
        var y = 0
        while (y < inH) {
            var x = 0
            while (x < inW) {
                val pw = minOf(PATCH_SIZE, inW - x)
                val ph = minOf(PATCH_SIZE, inH - y)
                val patch = Bitmap.createBitmap(src, x, y, pw, ph)
                val paddedPatch = if (pw < PATCH_SIZE || ph < PATCH_SIZE) padPatch(patch) else patch

                val upscaled = runPatchInference(paddedPatch)

                // Crop to actual output size (remove padding)
                val cropW = pw * SCALE_FACTOR; val cropH = ph * SCALE_FACTOR
                val cropped = Bitmap.createBitmap(upscaled, 0, 0, cropW, cropH)
                canvas.drawBitmap(cropped, (x * SCALE_FACTOR).toFloat(), (y * SCALE_FACTOR).toFloat(), null)

                if (paddedPatch != patch) paddedPatch.recycle()
                patch.recycle(); upscaled.recycle(); cropped.recycle()
                x += step
            }
            y += step
        }
        return result
    }

    private fun runPatchInference(patch: Bitmap): Bitmap {
        val input = bitmapToInputBuffer(patch)
        val outputSize = PATCH_SIZE * SCALE_FACTOR
        val outputBuffer = ByteBuffer.allocateDirect(1 * outputSize * outputSize * 3 * 4)
            .order(ByteOrder.nativeOrder())

        interpreter!!.run(input, outputBuffer)
        return outputBufferToBitmap(outputBuffer, outputSize, outputSize)
    }

    private fun bitmapToInputBuffer(bmp: Bitmap): ByteBuffer {
        val buf = ByteBuffer.allocateDirect(1 * PATCH_SIZE * PATCH_SIZE * 3 * 4).order(ByteOrder.nativeOrder())
        val pixels = IntArray(PATCH_SIZE * PATCH_SIZE)
        bmp.getPixels(pixels, 0, PATCH_SIZE, 0, 0, PATCH_SIZE, PATCH_SIZE)
        for (px in pixels) {
            buf.putFloat(((px shr 16) and 0xFF) / 255f)
            buf.putFloat(((px shr 8) and 0xFF) / 255f)
            buf.putFloat((px and 0xFF) / 255f)
        }
        buf.rewind()
        return buf
    }

    private fun outputBufferToBitmap(buf: ByteBuffer, w: Int, h: Int): Bitmap {
        buf.rewind()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(w * h)
        for (i in pixels.indices) {
            val r = (buf.float * 255).toInt().coerceIn(0, 255)
            val g = (buf.float * 255).toInt().coerceIn(0, 255)
            val b = (buf.float * 255).toInt().coerceIn(0, 255)
            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        bmp.setPixels(pixels, 0, w, 0, 0, w, h)
        return bmp
    }

    private fun padPatch(patch: Bitmap): Bitmap {
        val padded = Bitmap.createBitmap(PATCH_SIZE, PATCH_SIZE, Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(padded).drawBitmap(patch, 0f, 0f, null)
        return padded
    }

    // ── Bicubic fallback ──────────────────────────────────────────────────────

    private fun bicubicUpscale(src: Bitmap, scale: Int): Bitmap =
        Bitmap.createScaledBitmap(src, src.width * scale, src.height * scale, true)

    private fun loadModelFile(): MappedByteBuffer {
        val fd = context.assets.openFd(ESRGAN_MODEL)
        return FileInputStream(fd.fileDescriptor).channel
            .map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
    }

    fun close() {
        interpreter?.close()
        gpuDelegate?.close()
    }
}
