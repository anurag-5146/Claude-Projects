package com.nitro.camera.processing

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc

// ── Bitmap ↔ Mat conversions ──────────────────────────────────────────────────

fun Bitmap.toMat(): Mat {
    val mat = Mat()
    Utils.bitmapToMat(this, mat)          // ARGB_8888 → CV_8UC4 (RGBA)
    Imgproc.cvtColor(mat, mat, Imgproc.COLOR_RGBA2BGR)
    return mat
}

fun Mat.toBitmap(): Bitmap {
    val out = Mat()
    Imgproc.cvtColor(this, out, Imgproc.COLOR_BGR2RGBA)
    val bmp = Bitmap.createBitmap(out.cols(), out.rows(), Bitmap.Config.ARGB_8888)
    Utils.matToBitmap(out, bmp)
    out.release()
    return bmp
}

fun ByteArray.decodeJpegToBitmap(sampleSize: Int = 1): Bitmap? =
    BitmapFactory.decodeByteArray(this, 0, size,
        BitmapFactory.Options().apply { inSampleSize = sampleSize })

fun ByteArray.decodeJpegToMat(): Mat? =
    decodeJpegToBitmap()?.toMat()

// ── Mat helpers ───────────────────────────────────────────────────────────────

fun Mat.toGray(): Mat = Mat().also { Imgproc.cvtColor(this, it, Imgproc.COLOR_BGR2GRAY) }

fun Mat.toFloat32(): Mat = Mat().also { this.convertTo(it, CvType.CV_32FC(channels())) }

fun Mat.toUint8(): Mat = Mat().also {
    this.convertTo(it, CvType.CV_8UC(channels()), 255.0)
}

/** Clamp all values to [0, 255] in place. */
fun Mat.clamp(): Mat = apply { Core.min(this, Scalar.all(255.0), this); Core.max(this, Scalar.all(0.0), this) }
