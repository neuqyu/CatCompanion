package com.example.catcompanion

import android.graphics.Bitmap
import android.graphics.Color
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Offline color-key extraction; for photos against a nearly uniform backdrop.
 * This is NOT AI semantic segmentation.
 */
internal object PlainBackgroundMatte {
    fun cut(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        require(width > 0 && height > 0)
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val corners = intArrayOf(
            pixels[0], pixels[width - 1], pixels[(height - 1) * width],
            pixels[width * height - 1]
        )
        val red = corners.map { Color.red(it) }.sorted()[1]
        val green = corners.map { Color.green(it) }.sorted()[1]
        val blue = corners.map { Color.blue(it) }.sorted()[1]
        val queue = IntArray(width * height)
        val visited = BooleanArray(width * height)
        var read = 0
        var write = 0
        fun fits(i: Int): Boolean {
            val p = pixels[i]
            val dr = Color.red(p) - red
            val dg = Color.green(p) - green
            val db = Color.blue(p) - blue
            val dr2 = dr * dr + dg * dg + db * db
            return dr2 < 58 * 58
        }
        fun add(i: Int) {
            if (i !in pixels.indices || visited[i]) return
            visited[i] = true
            if (fits(i)) queue[write++] = i
        }
        for (x in 0 until width) { add(x); add((height - 1) * width + x) }
        for (y in 0 until height) { add(y * width); add(y * width + width - 1) }
        while (read < write) {
            val i = queue[read++]
            val x = i % width
            val y = i / width
            if (x > 0) add(i - 1)
            if (x < width - 1) add(i + 1)
            if (y > 0) add(i - width)
            if (y < height - 1) add(i + width)
        }
        require(write > width * height / 50) {
            "照片背景不是均匀纯色，离线方法不适用"
        }
        for (i in 0 until write) {
            pixels[queue[i]] = Color.TRANSPARENT
        }
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        result.setPixels(pixels, 0, width, 0, 0, width, height)
        return CatPhotoProcessor.cropToContent(result)
    }
}
