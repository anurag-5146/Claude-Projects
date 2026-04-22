package com.nitro.camera.processing

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "ImageProcessor"

class ImageProcessor(private val context: Context) {

    suspend fun saveJpeg(image: Image): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val buffer: ByteBuffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            image.close()

            val filename = "NITRO_${timestamp()}.jpg"
            saveToMediaStore(bytes, filename, "image/jpeg", Environment.DIRECTORY_DCIM + "/NitroCamera")
        }
    }

    suspend fun saveRaw(image: Image): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val planes = image.planes
            val buffer = planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            image.close()

            val filename = "NITRO_${timestamp()}.dng"
            val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_DCIM), "NitroCamera")
            dir.mkdirs()
            val file = File(dir, filename)
            FileOutputStream(file).use { it.write(bytes) }
            file.absolutePath
        }
    }

    /**
     * Computes a luminance histogram from a JPEG-decoded bitmap.
     * Returns 256-bucket float array normalised to [0,1].
     */
    suspend fun computeHistogram(jpegBytes: ByteArray): FloatArray = withContext(Dispatchers.Default) {
        val bmp = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size,
            BitmapFactory.Options().apply { inSampleSize = 8 })
            ?: return@withContext FloatArray(256)

        val histogram = IntArray(256)
        val pixels = IntArray(bmp.width * bmp.height)
        bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        bmp.recycle()

        for (pixel in pixels) {
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            val luma = (0.299 * r + 0.587 * g + 0.114 * b).toInt().coerceIn(0, 255)
            histogram[luma]++
        }

        val max = histogram.max().toFloat().coerceAtLeast(1f)
        FloatArray(256) { histogram[it] / max }
    }

    suspend fun saveJpegBytes(jpegBytes: ByteArray, prefix: String): String =
        withContext(Dispatchers.IO) {
            val filename = "${prefix}_${timestamp()}.jpg"
            saveToMediaStore(jpegBytes, filename, "image/jpeg", Environment.DIRECTORY_DCIM + "/NitroCamera")
        }

    suspend fun saveBitmap(bitmap: android.graphics.Bitmap, prefix: String): String =
        withContext(Dispatchers.IO) {
            val baos = java.io.ByteArrayOutputStream()
            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 97, baos)
            val bytes = baos.toByteArray()
            val filename = "${prefix}_${timestamp()}.jpg"
            saveToMediaStore(bytes, filename, "image/jpeg", Environment.DIRECTORY_DCIM + "/NitroCamera")
        }

    private fun saveToMediaStore(bytes: ByteArray, filename: String, mimeType: String, relativePath: String): String {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }

        val resolver = context.contentResolver
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI

        val uri = resolver.insert(collection, values)
            ?: throw IllegalStateException("Failed to create MediaStore entry")

        resolver.openOutputStream(uri)?.use { it.write(bytes) }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }

        Log.d(TAG, "Saved $filename → $uri")
        return uri.toString()
    }

    private fun timestamp() = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
}
