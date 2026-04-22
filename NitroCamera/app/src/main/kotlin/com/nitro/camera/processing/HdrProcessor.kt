package com.nitro.camera.processing

import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.opencv.core.*
import org.opencv.photo.Photo

private const val TAG = "HdrProcessor"

/**
 * HDR+ style pipeline:
 *   1. Decode bracketed JPEG frames
 *   2. Align frames with ECC (removes camera shake between shots)
 *   3. Merge using Mertens exposure fusion (no calibration needed)
 *   4. Apply Reinhard global tonemapping
 *
 * Mertens fusion is chosen over Debevec because:
 * - No camera response calibration required
 * - Works on JPEG input (not just RAW)
 * - Produces naturally-looking results without HDR artifacts
 */
object HdrProcessor {

    suspend fun process(
        jpegFrames: List<ByteArray>,
        onProgress: (Float) -> Unit = {}
    ): Bitmap? = withContext(Dispatchers.Default) {
        if (jpegFrames.size < 2) {
            Log.w(TAG, "Need at least 2 frames for HDR, got ${jpegFrames.size}")
            return@withContext null
        }

        onProgress(0.1f)

        // 1. Decode
        val mats = jpegFrames.mapNotNull { it.decodeJpegToMat() }
        if (mats.size < 2) return@withContext null
        onProgress(0.25f)

        // 2. Align
        val aligned = FrameAligner.alignToReference(mats)
        onProgress(0.5f)

        // 3. Mertens exposure fusion
        val merged = Mat()
        val mergeMertens = Photo.createMergeMertens(
            /* contrast_weight= */ 1.0f,
            /* saturation_weight= */ 1.0f,
            /* exposure_weight= */ 0.0f
        )
        mergeMertens.process(aligned.toMatOfMat(), merged)
        onProgress(0.75f)

        // Mertens output is CV_32FC3 in [0,1] — scale to [0,255]
        val scaled = Mat()
        merged.convertTo(scaled, org.opencv.core.CvType.CV_8UC3, 255.0)

        // 4. Reinhard tonemapping on the merged result for pleasing contrast
        val tonemapped = applyTonemap(merged)
        onProgress(0.9f)

        val result = tonemapped.toBitmap()

        // Cleanup
        listOf(merged, scaled, tonemapped).forEach { it.release() }
        mats.forEach { it.release() }
        aligned.drop(1).forEach { it.release() }

        onProgress(1.0f)
        result
    }

    private fun applyTonemap(hdr32f: Mat): Mat {
        val tonemap = Photo.createTonemapReinhard(
            /* gamma= */ 1.8f,
            /* intensity= */ 0.0f,
            /* light_adapt= */ 0.9f,
            /* color_adapt= */ 0.0f
        )
        val tonemapped = Mat()
        tonemap.process(hdr32f, tonemapped)
        // Convert from [0,1] float to [0,255] uint8
        val out = Mat()
        tonemapped.convertTo(out, CvType.CV_8UC3, 255.0)
        tonemapped.release()
        return out
    }

    private fun List<Mat>.toMatOfMat(): MutableList<Mat> = toMutableList()
}
