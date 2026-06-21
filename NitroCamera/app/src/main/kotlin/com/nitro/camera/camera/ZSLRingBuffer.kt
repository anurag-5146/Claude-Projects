package com.nitro.camera.camera

import android.media.Image
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Zero-Shutter-Lag ring buffer: stores the N most recent frames so capture
 * can return the latest pre-captured frame instead of waiting for a new one.
 */
class ZSLRingBuffer(private val capacity: Int = 3) {

    private val lock = ReentrantLock()
    private val buffer = ArrayDeque<TimestampedImage>(capacity)

    data class TimestampedImage(
        val image: Image,
        val timestampNs: Long
    )

    fun push(image: Image) = lock.withLock {
        if (buffer.size >= capacity) {
            buffer.removeFirst().image.close()
        }
        buffer.addLast(TimestampedImage(image, image.timestamp))
    }

    fun acquireLatest(): TimestampedImage? = lock.withLock {
        val latest = buffer.lastOrNull() ?: return null
        buffer.removeLast()
        buffer.forEach { it.image.close() }
        buffer.clear()
        latest
    }

    fun clear() = lock.withLock {
        buffer.forEach { it.image.close() }
        buffer.clear()
    }

    val size: Int get() = lock.withLock { buffer.size }
}
