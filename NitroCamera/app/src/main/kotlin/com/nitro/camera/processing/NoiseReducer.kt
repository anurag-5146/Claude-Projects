package com.nitro.camera.processing

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.opencv.core.Mat
import org.opencv.photo.Photo

/**
 * Single-frame noise reduction via OpenCV fastNlMeansDenoisingColored.
 *
 * This is the same algorithm used in professional photo editors —
 * it works by comparing small patches across the image to find similar ones,
 * then averages them to remove noise while preserving edges.
 *
 * Best applied to high-ISO shots (ISO 800+) where temporal stacking
 * isn't available (single captures in PHOTO mode).
 *
 * [strength] maps to h/hColor in fastNlMeans:
 *   0.1–0.3 = light NR (low ISO, preserve texture)
 *   0.4–0.6 = medium NR (ISO 400–1600)
 *   0.7–1.0 = heavy NR (ISO 1600+, lose some fine detail)
 */
object NoiseReducer {

    suspend fun reduce(bitmap: Bitmap, strength: Float = 0.4f): Bitmap =
        withContext(Dispatchers.Default) {
            val src = bitmap.toMat()
            val dst = Mat()

            val h = (strength * 15f).coerceIn(3f, 20f)
            Photo.fastNlMeansDenoisingColored(
                src, dst,
                /* h= */ h,
                /* hColor= */ h,
                /* templateWindowSize= */ 7,
                /* searchWindowSize= */ 21
            )

            val result = dst.toBitmap()
            src.release()
            dst.release()
            result
        }
}
