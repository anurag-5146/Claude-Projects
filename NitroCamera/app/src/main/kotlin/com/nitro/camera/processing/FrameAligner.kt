package com.nitro.camera.processing

import android.util.Log
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import org.opencv.video.Video

private const val TAG = "FrameAligner"
private const val ECC_MAX_ITER = 50
private const val ECC_EPS = 1e-3

/**
 * Aligns a list of Mats to the first (reference) frame using OpenCV's
 * Enhanced Correlation Coefficient (ECC) algorithm.
 *
 * ECC is robust to lighting differences between frames, making it ideal
 * for both HDR (large EV differences) and night mode (subtle motion).
 */
object FrameAligner {

    fun alignToReference(frames: List<Mat>): List<Mat> {
        if (frames.size <= 1) return frames
        val reference = frames.first()
        val refGray = reference.toGray()

        return frames.mapIndexed { i, frame ->
            if (i == 0) frame
            else tryAlign(refGray, reference, frame) ?: frame
        }.also { refGray.release() }
    }

    private fun tryAlign(refGray: Mat, reference: Mat, target: Mat): Mat? {
        return try {
            val targetGray = target.toGray()

            // Scale down for speed — ECC on half-res, apply to full-res
            val scaleFactor = 0.5
            val refSmall = Mat(); val tgtSmall = Mat()
            Imgproc.resize(refGray, refSmall, Size(), scaleFactor, scaleFactor)
            Imgproc.resize(targetGray, tgtSmall, Size(), scaleFactor, scaleFactor)

            val motionMatrix = Mat.eye(2, 3, CvType.CV_32F)
            val criteria = TermCriteria(
                TermCriteria.COUNT + TermCriteria.EPS, ECC_MAX_ITER, ECC_EPS
            )

            Video.findTransformECC(
                refSmall, tgtSmall, motionMatrix,
                Video.MOTION_EUCLIDEAN, criteria, Mat(), 1
            )

            // Scale translation back to full resolution
            motionMatrix.put(0, 2, motionMatrix.get(0, 2)[0] / scaleFactor)
            motionMatrix.put(1, 2, motionMatrix.get(1, 2)[0] / scaleFactor)

            val aligned = Mat()
            Imgproc.warpAffine(
                target, aligned, motionMatrix,
                reference.size(), Imgproc.INTER_LINEAR,
                Core.BORDER_REFLECT_101
            )

            listOf(targetGray, refSmall, tgtSmall, motionMatrix).forEach { it.release() }
            aligned
        } catch (e: Exception) {
            Log.w(TAG, "ECC alignment failed, using raw frame: ${e.message}")
            null
        }
    }
}
