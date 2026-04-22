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

private const val TAG = "NAFNetProcessor"

// NAFNet denoise TFLite. Convert weights per docs/MODEL_CONVERSION.md.
// Place in app/src/main/assets/nafnet.tflite
private const val NAFNET_MODEL = "nafnet.tflite"
private const val PATCH_SIZE = 256
private const val PATCH_OVERLAP = 16

/**
 * Learned denoiser. Removes sensor noise + chroma mush that bilateral
 * filtering leaves behind after burst merge.
 *
 *   - Model present → NAFNet inference patch-by-patch (GPU delegate)
 *   - Model absent  → graceful pass-through (original bitmap)
 *
 * Same resolution in / out. Tiled at 256×256 with 16 px overlap.
 */
class NAFNetProcessor(private val context: Context) {

    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null
    private var modelAvailable = false

    fun init() {
        modelAvailable = context.assets.list("")?.contains(NAFNET_MODEL) == true
        if (!modelAvailable) { Log.w(TAG, "NAFNet model not found — denoise disabled"); return }

        runCatching {
            val opts = Interpreter.Options()
            if (CompatibilityList().isDelegateSupportedOnThisDevice) {
                gpuDelegate = GpuDelegate()
                opts.addDelegate(gpuDelegate)
                Log.d(TAG, "NAFNet running on GPU")
            } else {
                opts.numThreads = 4
            }
            interpreter = Interpreter(loadModelFile(), opts)
            Log.d(TAG, "NAFNet interpreter ready")
        }.onFailure { Log.e(TAG, "NAFNet init failed", it); modelAvailable = false }
    }

    val isActive: Boolean get() = interpreter != null

    suspend fun denoise(bitmap: Bitmap): Bitmap = withContext(Dispatchers.Default) {
        if (interpreter == null) return@withContext bitmap  // pass-through
        runCatching { nafNetDenoise(bitmap) }.getOrElse {
            Log.w(TAG, "NAFNet inference failed, pass-through: ${it.message}")
            bitmap
        }
    }

    // ── NAFNet patch inference ────────────────────────────────────────────────

    private fun nafNetDenoise(src: Bitmap): Bitmap {
        val inW = src.width; val inH = src.height
        val result = Bitmap.createBitmap(inW, inH, Bitmap.Config.ARGB_8888)
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

                val denoised = runPatchInference(paddedPatch)

                val cropped = Bitmap.createBitmap(denoised, 0, 0, pw, ph)
                canvas.drawBitmap(cropped, x.toFloat(), y.toFloat(), null)

                if (paddedPatch != patch) paddedPatch.recycle()
                patch.recycle(); denoised.recycle(); cropped.recycle()
                x += step
            }
            y += step
        }
        return result
    }

    private fun runPatchInference(patch: Bitmap): Bitmap {
        val input = bitmapToInputBuffer(patch)
        val outputBuffer = ByteBuffer.allocateDirect(1 * PATCH_SIZE * PATCH_SIZE * 3 * 4)
            .order(ByteOrder.nativeOrder())

        interpreter!!.run(input, outputBuffer)
        return outputBufferToBitmap(outputBuffer, PATCH_SIZE, PATCH_SIZE)
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

    private fun loadModelFile(): MappedByteBuffer {
        val fd = context.assets.openFd(NAFNET_MODEL)
        return FileInputStream(fd.fileDescriptor).channel
            .map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
    }

    fun close() {
        interpreter?.close()
        gpuDelegate?.close()
    }
}
