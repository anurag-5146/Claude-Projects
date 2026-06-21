package com.nitro.camera.camera

import android.graphics.ImageFormat
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import java.nio.ByteBuffer

data class AnalysisResult(
    val histogram: FloatArray = FloatArray(256),
    val focusMask: android.graphics.Bitmap? = null,   // downsampled edge map
    val zebraPixelsFraction: Float = 0f               // fraction of frame overexposed
) {
    companion object { val EMPTY = AnalysisResult() }
}

/**
 * Taps into a low-resolution YUV_420_888 ImageReader on the camera session
 * to drive three real-time overlays without touching the main preview stream:
 *
 *   - Luminance histogram (256 bins, normalised)
 *   - Focus peaking edge mask via Sobel (Bitmap at 1/4 resolution)
 *   - Zebra stripe fraction (% of frame above 235 luma — clipping warning)
 *
 * Only every [analysisPeriod]th frame is processed to keep CPU below 5%.
 */
class PreviewAnalyzer(
    private val scope: CoroutineScope,
    private val handler: Handler,
    private val analysisPeriod: Int = 4     // analyse 1 in every 4 frames ≈ 7–8fps
) {
    val analysisWidth  = 640
    val analysisHeight = 480

    private val _result = MutableStateFlow(AnalysisResult.EMPTY)
    val result: StateFlow<AnalysisResult> = _result.asStateFlow()

    private var frameCount = 0

    val imageReader: ImageReader = ImageReader.newInstance(
        analysisWidth, analysisHeight, ImageFormat.YUV_420_888, 2
    ).also { reader ->
        reader.setOnImageAvailableListener({ r ->
            val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            frameCount++
            if (frameCount % analysisPeriod == 0) {
                processAsync(image)
            } else {
                image.close()
            }
        }, handler)
    }

    private fun processAsync(image: Image) {
        scope.launch(Dispatchers.Default) {
            runCatching {
                val yPlane = image.planes[0]
                val yMat = yPlaneToMat(yPlane, image.width, image.height)

                val histogram = computeHistogram(yMat)
                val focusMask = computeFocusPeaking(yMat)
                val zebraFraction = computeZebraFraction(yMat)

                yMat.release()
                _result.value = AnalysisResult(histogram, focusMask, zebraFraction)
            }.onFailure { it.printStackTrace() }
            image.close()
        }
    }

    // ── Histogram ─────────────────────────────────────────────────────────────

    private fun computeHistogram(yMat: Mat): FloatArray {
        val hist = Mat()
        val images = listOf(yMat)
        val channels = MatOfInt(0)
        val mask = Mat()
        val histSize = MatOfInt(256)
        val ranges = MatOfFloat(0f, 256f)
        Imgproc.calcHist(images, channels, mask, hist, histSize, ranges)

        val data = FloatArray(256)
        var maxVal = 1f
        for (i in 0..255) maxVal = maxOf(maxVal, hist.get(i, 0)[0].toFloat())
        for (i in 0..255) data[i] = hist.get(i, 0)[0].toFloat() / maxVal

        hist.release(); mask.release()
        return data
    }

    // ── Focus peaking ─────────────────────────────────────────────────────────

    private fun computeFocusPeaking(yMat: Mat): android.graphics.Bitmap {
        val sobelX = Mat(); val sobelY = Mat(); val magnitude = Mat()
        Imgproc.Sobel(yMat, sobelX, CvType.CV_16S, 1, 0, 3)
        Imgproc.Sobel(yMat, sobelY, CvType.CV_16S, 0, 1, 3)
        Core.cartToPolar(sobelX.also { Imgproc.convertScaleAbs(it, it) },
                         sobelY.also { Imgproc.convertScaleAbs(it, it) }, magnitude, Mat())

        // Threshold: only show strong edges
        val peakThreshold = 80.0
        val thresholded = Mat()
        Imgproc.threshold(magnitude, thresholded, peakThreshold, 255.0, Imgproc.THRESH_BINARY)

        // Paint edge pixels red (RGBA)
        val rgba = Mat(yMat.rows(), yMat.cols(), CvType.CV_8UC4, Scalar(0.0, 0.0, 0.0, 0.0))
        val redColor = Scalar(255.0, 0.0, 0.0, 220.0)
        rgba.setTo(redColor, thresholded)

        val bmp = android.graphics.Bitmap.createBitmap(yMat.cols(), yMat.rows(),
            android.graphics.Bitmap.Config.ARGB_8888)
        org.opencv.android.Utils.matToBitmap(rgba, bmp)

        listOf(sobelX, sobelY, magnitude, thresholded, rgba).forEach { it.release() }
        return bmp
    }

    // ── Zebra stripes ─────────────────────────────────────────────────────────

    private fun computeZebraFraction(yMat: Mat): Float {
        val overexposed = Mat()
        Imgproc.threshold(yMat, overexposed, 235.0, 255.0, Imgproc.THRESH_BINARY)
        val whitePixels = Core.countNonZero(overexposed)
        val total = yMat.rows() * yMat.cols()
        overexposed.release()
        return whitePixels.toFloat() / total.toFloat()
    }

    // ── YUV plane → Mat ───────────────────────────────────────────────────────

    private fun yPlaneToMat(plane: Image.Plane, w: Int, h: Int): Mat {
        val buffer: ByteBuffer = plane.buffer
        val rowStride = plane.rowStride
        val mat = Mat(h, w, CvType.CV_8UC1)
        val rowData = ByteArray(rowStride)
        val pixelData = ByteArray(w)
        for (row in 0 until h) {
            buffer.position(row * rowStride)
            buffer.get(rowData, 0, minOf(rowStride, buffer.remaining()))
            System.arraycopy(rowData, 0, pixelData, 0, w)
            mat.put(row, 0, pixelData)
        }
        return mat
    }

    fun close() = imageReader.close()
}
