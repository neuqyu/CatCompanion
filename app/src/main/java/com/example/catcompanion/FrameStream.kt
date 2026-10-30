package com.example.catcompanion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.util.concurrent.Executors

/**
 * Decode 60-fps frame sequences in a small bounded cache off the main thread.
 * Drops late frames rather than blocking UI drawing.
 */
internal class FrameStream(
    context: Context,
    action: PetView.Action,
    private val onFrameReady: () -> Unit
) {
    private val frames = MotionFrames.frameFiles(context, action)
    val count: Int = frames.size
    val fps: Int = MotionFrames.frameRate(context, action)
    private val decoder = Executors.newSingleThreadExecutor()
    private val cache = LinkedHashMap<Int, Bitmap>(24, 0.75f, true)
    private val requested = HashSet<Int>()
    private var stopped = false

    init {
        for (i in 0 until minOf(count, 8)) request(i)
    }

    private fun request(index: Int) {
        if (count < 2) return
        val i = ((index % count) + count) % count
        synchronized(this) {
            if (stopped || cache.containsKey(i) || !requested.add(i)) return
        }
        try {
            decoder.execute {
                val bitmap = try {
                    BitmapFactory.decodeFile(frames[i].path)
                } catch (_: Exception) { null }
                var updated = false
                synchronized(this) {
                    requested.remove(i)
                    if (stopped) {
                        bitmap?.recycle()
                    } else if (bitmap != null) {
                        cache[i] = bitmap
                        while (cache.size > 20) {
                            val iter = cache.entries.iterator()
                            iter.next()
                            iter.remove() // leave recycling to GC; UI may still draw the frame
                        }
                        updated = true
                    }
                }
                if (updated) onFrameReady()
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            synchronized(this) { requested.remove(i) }
        }
    }

    fun get(elapsedMs: Long): Bitmap? {
        if (count < 2) return null
        val i = MotionFrames.frameIndex(elapsedMs, count, fps)
        request(i)
        for (ahead in 1..6) request((i + ahead) % count)
        return synchronized(this) {
            cache[i] ?: cache[(i - 1 + count) % count]
                ?: cache[(i - 2 + count) % count]
        }
    }

    fun close() {
        synchronized(this) {
            stopped = true
            cache.clear()
            requested.clear()
        }
        decoder.shutdownNow()
    }
}
