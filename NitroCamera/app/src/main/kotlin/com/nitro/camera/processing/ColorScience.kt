package com.nitro.camera.processing

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.opencv.core.*
import org.opencv.imgproc.Imgproc

/**
 * Perceptual color science pipeline — applied after capture to make images
 * look more like Apple/Pixel output. Each stage is tuned per scene.
 *
 * Pipeline order matters:
 *   1. Warmth shift (white balance nudge)
 *   2. Shadow lift (recover dark regions without blowing highlights)
 *   3. CLAHE local contrast (micro-contrast, like Deep Fusion's texture recovery)
 *   4. Saturation boost with skin-tone protection
 *   5. Perceptual sharpening
 */
object ColorScience {

    suspend fun process(
        bitmap: Bitmap,
        params: SceneProcessingParams
    ): Bitmap = withContext(Dispatchers.Default) {
        var mat = bitmap.toMat()

        if (params.warmthShift != 0f)         mat = applyWarmth(mat, params.warmthShift)
        if (params.shadowLift > 0f)            mat = liftShadows(mat, params.shadowLift)
        if (params.contrastBoost != 1.0f)      mat = applyClahe(mat)
        if (params.saturationBoost != 1.0f)    mat = boostSaturation(mat, params.saturationBoost)
        if (params.sharpenAmount > 0f)         mat = perceptualSharpen(mat, params.sharpenAmount)

        val result = mat.toBitmap()
        mat.release()
        result
    }

    // ── Warmth shift ──────────────────────────────────────────────────────────

    /**
     * Nudges white balance by shifting the red/blue channel balance.
     * Positive = warmer (more red, less blue), negative = cooler.
     */
    private fun applyWarmth(src: Mat, shift: Float): Mat {
        val channels = mutableListOf<Mat>()
        Core.split(src, channels)
        // BGR order: channels[0]=B, channels[1]=G, channels[2]=R
        val redBoost = 1.0 + shift.toDouble()
        val blueReduce = 1.0 - shift.toDouble() * 0.6
        Core.multiply(channels[2], Scalar(redBoost), channels[2])    // R up
        Core.multiply(channels[0], Scalar(blueReduce), channels[0])  // B down
        channels.forEach { Core.min(it, Scalar(255.0), it) }
        val dst = Mat(); Core.merge(channels, dst)
        channels.forEach { it.release() }; src.release()
        return dst
    }

    // ── Shadow lift ───────────────────────────────────────────────────────────

    /**
     * Applies gamma correction selectively to the shadow region.
     * Uses a luminance mask so bright areas are untouched.
     */
    private fun liftShadows(src: Mat, amount: Float): Mat {
        val hsv = Mat(); Imgproc.cvtColor(src, hsv, Imgproc.COLOR_BGR2HSV)
        val channels = mutableListOf<Mat>(); Core.split(hsv, channels)
        val vChannel = channels[2]  // Value / luminance

        // LUT: apply gamma only where V < 128
        val lut = Mat(1, 256, CvType.CV_8UC1)
        val gamma = 1.0 - amount.toDouble() * 0.5   // e.g. amount=0.1 → gamma=0.95
        for (i in 0..255) {
            val lifted = (Math.pow(i / 255.0, gamma) * 255).toInt().coerceIn(0, 255)
            val blend = if (i < 128) lifted else i   // Only touch shadows
            lut.put(0, i, blend.toDouble())
        }
        Core.LUT(vChannel, lut, vChannel)

        Core.merge(channels, hsv)
        val dst = Mat(); Imgproc.cvtColor(hsv, dst, Imgproc.COLOR_HSV2BGR)
        listOf(hsv, lut).plus(channels).forEach { it.release() }; src.release()
        return dst
    }

    // ── CLAHE local contrast ──────────────────────────────────────────────────

    /**
     * Contrast Limited Adaptive Histogram Equalization applied to luminance only.
     * Produces the micro-contrast detail recovery characteristic of Deep Fusion —
     * textures pop without changing global exposure.
     */
    private fun applyClahe(src: Mat): Mat {
        val lab = Mat(); Imgproc.cvtColor(src, lab, Imgproc.COLOR_BGR2Lab)
        val channels = mutableListOf<Mat>(); Core.split(lab, channels)
        val clahe = Imgproc.createCLAHE(2.0, Size(8.0, 8.0))
        clahe.apply(channels[0], channels[0])      // L channel only
        Core.merge(channels, lab)
        val dst = Mat(); Imgproc.cvtColor(lab, dst, Imgproc.COLOR_Lab2BGR)
        listOf(lab).plus(channels).forEach { it.release() }; src.release()
        return dst
    }

    // ── Saturation boost with skin-tone protection ────────────────────────────

    /**
     * Boosts vibrance (saturation of less-saturated pixels more than already-vivid ones).
     * Skin tones (HSV hue ≈ 0–25°, warm saturation) are protected to avoid
     * over-saturated orange skin — the classic Apple skin tone advantage.
     */
    private fun boostSaturation(src: Mat, factor: Float): Mat {
        val hsv = Mat(); Imgproc.cvtColor(src, hsv, Imgproc.COLOR_BGR2HSV)
        val channels = mutableListOf<Mat>(); Core.split(hsv, channels)
        val hChannel = channels[0]; val sChannel = channels[1]

        // Build skin-tone protection mask: H in [0,25] ∪ [165,180], S in [40,200]
        val skinMaskLow = Mat(); val skinMaskHigh = Mat(); val sMask = Mat()
        Core.inRange(hChannel, Scalar(0.0), Scalar(25.0), skinMaskLow)
        Core.inRange(hChannel, Scalar(165.0), Scalar(180.0), skinMaskHigh)
        Core.bitwise_or(skinMaskLow, skinMaskHigh, skinMaskLow)
        Core.inRange(sChannel, Scalar(40.0), Scalar(200.0), sMask)
        val skinMask = Mat(); Core.bitwise_and(skinMaskLow, sMask, skinMask)

        // Vibrance boost: scale saturation, but less for already-high-S pixels
        val sFloat = sChannel.toFloat32()
        // boost = factor, but taper off: boost_actual = factor * (1 - S/255 * 0.5)
        val scaleMat = Mat(sFloat.size(), CvType.CV_32FC1, Scalar(factor.toDouble()))
        val taperMat = Mat(); Core.multiply(sFloat, Scalar(1.0 / 510.0), taperMat)
        Core.subtract(scaleMat, taperMat, scaleMat)
        Core.multiply(sFloat, scaleMat, sFloat)
        sFloat.clamp()
        sFloat.convertTo(sChannel, CvType.CV_8UC1)

        // Restore original saturation for skin pixels
        val originalS = channels[1].clone()
        originalS.copyTo(sChannel, skinMask)

        Core.merge(channels, hsv)
        val dst = Mat(); Imgproc.cvtColor(hsv, dst, Imgproc.COLOR_HSV2BGR)
        listOf(hsv, skinMaskLow, skinMaskHigh, sMask, skinMask, sFloat, scaleMat, taperMat, originalS)
            .plus(channels).forEach { it.release() }
        src.release()
        return dst
    }

    // ── Perceptual sharpening ─────────────────────────────────────────────────

    /**
     * Frequency-selective unsharp masking:
     *   - High-frequency layer is amplified
     *   - Edges in flat regions are NOT boosted (local-variance gate)
     *   - Result: texture sharpness without noise amplification or halos
     */
    private fun perceptualSharpen(src: Mat, amount: Float): Mat {
        val blurred = Mat()
        Imgproc.GaussianBlur(src, blurred, Size(0.0, 0.0), 1.5)

        val highFreq = Mat(); Core.subtract(src.toFloat32(), blurred.toFloat32(), highFreq)

        // Gate: only sharpen where local variance (contrast) is high enough
        val grayF = src.toGray().toFloat32()
        val localVar = Mat(); val localMean = Mat()
        Imgproc.blur(grayF, localMean, Size(5.0, 5.0))
        val diff = Mat(); Core.subtract(grayF, localMean, diff)
        Core.multiply(diff, diff, diff)
        Imgproc.blur(diff, localVar, Size(5.0, 5.0))

        // Normalise variance gate to [0, 1]
        val minVal = DoubleArray(1); val maxVal = DoubleArray(1)
        Core.minMaxLoc(localVar, minVal, maxVal)
        Core.subtract(localVar, Scalar(minVal[0]), localVar)
        if (maxVal[0] > minVal[0]) Core.divide(localVar, Scalar(maxVal[0] - minVal[0]), localVar)

        // Broadcast gate to 3 channels
        val gate = Mat(); Core.merge(listOf(localVar, localVar, localVar), gate)
        val scaledHF = Mat(); Core.multiply(highFreq, gate, scaledHF)
        Core.multiply(scaledHF, Scalar(amount.toDouble()), scaledHF)

        val sharpened = Mat(); Core.add(src.toFloat32(), scaledHF, sharpened)
        sharpened.clamp()

        val dst = Mat(); sharpened.convertTo(dst, CvType.CV_8UC3)
        listOf(blurred, highFreq, grayF, localVar, localMean, diff, gate, scaledHF, sharpened).forEach { it.release() }
        src.release()
        return dst
    }
}
