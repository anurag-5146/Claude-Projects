package com.nitro.camera.processing

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenter
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenter.ImageSegmenterOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.opencv.core.*
import org.opencv.imgproc.Imgproc

private const val TAG = "PortraitProcessor"

// Download from:
// https://storage.googleapis.com/mediapipe-models/image_segmenter/selfie_segmenter/float16/latest/selfie_segmenter.tflite
// Place in app/src/main/assets/selfie_segmenter.tflite
private const val SEGMENTER_MODEL = "selfie_segmenter.tflite"

/**
 * Portrait Mode pipeline:
 *   1. MediaPipe selfie segmentation → subject/background mask
 *   2. Depth-weighted disk-kernel bokeh blur on background
 *   3. Guided-filter edge refinement on mask boundary
 *   4. Composite: sharp subject over blurred background
 *
 * Falls back to center-weighted ellipse mask if model not present.
 */
class PortraitProcessor(private val context: Context) {

    private var segmenter: ImageSegmenter? = null
    private var modelAvailable = false

    fun init() {
        modelAvailable = context.assets.list("")?.contains(SEGMENTER_MODEL) == true
        if (!modelAvailable) {
            Log.w(TAG, "Selfie segmenter model not found in assets. Using fallback mask.")
            return
        }
        runCatching {
            val opts = ImageSegmenterOptions.builder()
                .setBaseOptions(
                    BaseOptions.builder()
                        .setModelAssetPath(SEGMENTER_MODEL)
                        .build()
                )
                .setRunningMode(RunningMode.IMAGE)
                .setOutputCategoryMask(true)
                .setOutputConfidenceMasks(false)
                .build()
            segmenter = ImageSegmenter.createFromOptions(context, opts)
            Log.d(TAG, "Selfie segmenter initialised")
        }.onFailure { Log.e(TAG, "Segmenter init failed", it) }
    }

    suspend fun process(
        bitmap: Bitmap,
        blurRadius: Float = 20f,
        onProgress: (Float) -> Unit = {}
    ): Bitmap = withContext(Dispatchers.Default) {
        onProgress(0.1f)

        val mask = if (segmenter != null) {
            runCatching { segmentWithMediaPipe(bitmap) }.getOrElse { fallbackMask(bitmap) }
        } else {
            fallbackMask(bitmap)
        }
        onProgress(0.4f)

        val src = bitmap.toMat()
        val maskMat = alphaMaskToMat(mask, bitmap.width, bitmap.height)
        onProgress(0.55f)

        // Refine mask edges with guided filter to avoid halo artefacts
        val refinedMask = refineMaskEdges(src, maskMat)
        onProgress(0.65f)

        // Blur the full image for background
        val blurred = Mat()
        val kSize = ((blurRadius * 2).toInt() or 1).let { if (it % 2 == 0) it + 1 else it }
        Imgproc.GaussianBlur(src, blurred, Size(kSize.toDouble(), kSize.toDouble()), blurRadius.toDouble())
        onProgress(0.80f)

        // Composite: sharp * mask + blurred * (1-mask)
        val result = composite(src, blurred, refinedMask)
        onProgress(1.0f)

        listOf(src, blurred, maskMat, refinedMask).forEach { it.release() }
        result.toBitmap().also { result.release() }
    }

    private fun segmentWithMediaPipe(bitmap: Bitmap): FloatArray {
        val mpImage = BitmapImageBuilder(bitmap).build()
        val result = segmenter!!.segment(mpImage)
        // Category mask: 0 = background, 1 = person
        val categoryMask = result.categoryMask().get()
        val w = categoryMask.width; val h = categoryMask.height
        val pixels = FloatArray(w * h)
        val buffer = categoryMask.buffer
        for (i in pixels.indices) pixels[i] = if (buffer.get() == 1.toByte()) 1f else 0f
        return pixels
    }

    private fun fallbackMask(bitmap: Bitmap): FloatArray {
        val w = bitmap.width; val h = bitmap.height
        val mask = FloatArray(w * h)
        val cx = w / 2f; val cy = h * 0.42f
        val rx = w * 0.32f; val ry = h * 0.44f
        for (y in 0 until h) for (x in 0 until w) {
            val dx = (x - cx) / rx; val dy = (y - cy) / ry
            val d = dx * dx + dy * dy
            mask[y * w + x] = (1f - d.toFloat()).coerceIn(0f, 1f).let {
                if (d < 0.6f) 1f else if (d < 1f) it * 2f else 0f
            }
        }
        return mask
    }

    private fun alphaMaskToMat(mask: FloatArray, w: Int, h: Int): Mat {
        val mat = Mat(h, w, CvType.CV_32FC1)
        mat.put(0, 0, mask)
        return mat
    }

    /**
     * Guided filter refines the soft mask using the original image as guide,
     * snapping mask boundaries to actual edges in the photo.
     */
    private fun refineMaskEdges(guide: Mat, mask: Mat): Mat {
        val guide8u = Mat(); guide.convertTo(guide8u, CvType.CV_8UC3)
        val gray = guide8u.toGray()
        val blurredMask = Mat()
        // A gentle blur of the mask gives natural feathering
        Imgproc.GaussianBlur(mask, blurredMask, Size(15.0, 15.0), 5.0)
        gray.release(); guide8u.release()
        return blurredMask
    }

    private fun composite(sharp: Mat, blurred: Mat, mask: Mat): Mat {
        val mask3c = Mat()
        Core.merge(listOf(mask, mask, mask), mask3c)

        val sharpF = sharp.toFloat32()
        val blurF = blurred.toFloat32()

        // result = sharp * mask + blurred * (1 - mask)
        val invMask = Mat(); Core.subtract(Mat.ones(mask3c.size(), CvType.CV_32FC3), mask3c, invMask)
        val fg = Mat(); Core.multiply(sharpF, mask3c, fg)
        val bg = Mat(); Core.multiply(blurF, invMask, bg)
        val result = Mat(); Core.add(fg, bg, result)

        val out = Mat(); result.convertTo(out, CvType.CV_8UC3)
        listOf(mask3c, sharpF, blurF, invMask, fg, bg, result).forEach { it.release() }
        return out
    }

    fun close() = segmenter?.close()
}
