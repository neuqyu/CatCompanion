package com.example.catcompanion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

/**
 * V0.4: real articulated movements are taken from a short source video.
 * The on-device ML Kit model removes the backdrop frame by frame.
 * No video generation from a still image is claimed.
 */
internal object MotionFrames {
    private const val CANVAS = 256
    const val FPS = 60
    private const val MAX_FRAMES = 120

    private fun root(context: Context) = File(context.filesDir, "cat_motion")
    private fun meta(context: Context, action: PetView.Action) =
        File(folder(context, action), "fps.txt")
    fun frameRate(context: Context, action: PetView.Action): Int =
        meta(context, action).takeIf { it.isFile }?.readText()?.trim()?.toIntOrNull()
            ?.coerceIn(1, FPS) ?: 8
    private fun folder(context: Context, action: PetView.Action) =
        File(root(context), action.name.lowercase())

    fun count(context: Context, action: PetView.Action): Int =
        folder(context, action).listFiles()?.count { it.isFile && it.extension == "png" } ?: 0

    fun clear(context: Context, action: PetView.Action) {
        folder(context, action).deleteRecursively()
    }

    fun clearAll(context: Context) {
        root(context).deleteRecursively()
    }

    fun summary(context: Context): String {
        val actions = PetView.Action.entries
            .filter { count(context, it) >= 2 }
            .map { it.name }
        return if (actions.isEmpty()) "还没有真实逐帧动作" else
            "已导入：${actions.joinToString("、")}"
    }

    fun frameFiles(context: Context, action: PetView.Action): List<File> =
        folder(context, action).listFiles()
            ?.filter { it.isFile && it.name.matches(Regex("frame_[0-9]{3}\\.png")) }
            ?.sortedBy { it.name }?.take(MAX_FRAMES) ?: emptyList()

    fun load(context: Context, action: PetView.Action): List<Bitmap> {
        val files = folder(context, action).listFiles()
            ?.filter { it.isFile && it.name.matches(Regex("frame_[0-9]{3}\\.png")) }
            ?.sortedBy { it.name }?.take(MAX_FRAMES) ?: emptyList()
        if (files.size < 2) return emptyList()
        val loaded = ArrayList<Bitmap>()
        try {
            for (file in files) {
                loaded.add(BitmapFactory.decodeFile(file.path) ?: error("Corrupt frame: ${file.name}"))
            }
            return loaded
        } catch (e: Exception) {
            loaded.forEach { it.recycle() }
            return emptyList()
        }
    }

    fun frameIndex(elapsedMs: Long, count: Int, fps: Int = FPS): Int =
        if (count < 2) 0 else ((elapsedMs.coerceAtLeast(0L) * fps / 1000L) % count).toInt()

    fun importVideo(
        context: Context,
        uri: Uri,
        action: PetView.Action,
        progress: (Int, Int) -> Unit
    ): Int {
        val retriever = MediaMetadataRetriever()
        val segmenter = SubjectSegmentation.getClient(
            SubjectSegmenterOptions.Builder().enableForegroundBitmap().build()
        )
        val target = folder(context, action)
        val staging = File(root(context), action.name.lowercase() + "_incoming")
        val backup = File(root(context), action.name.lowercase() + "_previous")
        staging.deleteRecursively()
        check(staging.mkdirs() || staging.isDirectory)
        var saved = 0
        try {
            retriever.setDataSource(context, uri)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: error("Video duration unavailable")
            require(duration >= 400L) { "请选择时长至少 0.4 秒的视频" }
            val length = min(duration, 2000L)
            val total = min(MAX_FRAMES, max(24, (length * FPS / 1000L).toInt()))
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull()?.coerceAtLeast(1) ?: 512
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull()?.coerceAtLeast(1) ?: 512
            val scale = min(1f, 512f / max(width, height))
            val scaledW = max(1, (width * scale).toInt())
            val scaledH = max(1, (height * scale).toInt())

            val raw = File(staging, "raw")
            check(raw.mkdirs() || raw.isDirectory)
            val sizes = ArrayList<Pair<Int, Int>>(total)
            val bottoms = ArrayList<Int>(total)
            // First pass: segment each real source pose. Lower-fps source may yield duplicates.
            for (i in 0 until total) {
                val whenUs = i.toLong() * 1_000_000L / FPS
                val image = if (Build.VERSION.SDK_INT >= 27) {
                    retriever.getScaledFrameAtTime(
                        whenUs, MediaMetadataRetriever.OPTION_CLOSEST, scaledW, scaledH
                    )
                } else {
                    val frame = retriever.getFrameAtTime(whenUs, MediaMetadataRetriever.OPTION_CLOSEST)
                    if (frame != null) {
                        val resized = Bitmap.createScaledBitmap(frame, scaledW, scaledH, true)
                        if (resized !== frame) frame.recycle()
                        resized
                    } else null
                } ?: error("视频第 ${i + 1} 帧无法读取")
                val foreground = try {
                    Tasks.await(
                        segmenter.process(InputImage.fromBitmap(image, 0)),
                        60, TimeUnit.SECONDS
                    ).foregroundBitmap ?: error("未识别到猫咪前景（第 ${i + 1} 帧）")
                } finally {
                    image.recycle()
                }
                val cut = try {
                    bottoms.add(subjectBottom(foreground))
                    CatPhotoProcessor.cropToContent(foreground)
                } finally { foreground.recycle() }
                try {
                    sizes.add(cut.width to cut.height)
                    FileOutputStream(File(raw, "raw_" + i.toString().padStart(3, '0') + ".png")).use { out ->
                        check(cut.compress(Bitmap.CompressFormat.PNG, 100, out))
                    }
                } finally { cut.recycle() }
                progress(i + 1, total * 2)
            }
            // Second pass uses one scale for every pose. This prevents per-frame zoom jitter.
            val orderedWidths = sizes.map { it.first }.sorted()
            val orderedHeights = sizes.map { it.second }.sorted()
            val percentileIndex = ((sizes.size - 1) * .90f).toInt()
            val refW = orderedWidths[percentileIndex].toFloat().coerceAtLeast(1f)
            val refH = orderedHeights[percentileIndex].toFloat().coerceAtLeast(1f)
            val commonScale = min(222f / refW, 216f / refH)
            val medianBottom = bottoms.sorted()[bottoms.size / 2]
            var smoothedMovement = 0f
            for (i in 0 until total) {
                val original = BitmapFactory.decodeFile(
                    File(raw, "raw_" + i.toString().padStart(3, '0') + ".png").path
                ) ?: error("暂存动作帧读取失败")
                val output = try {
                    val h = original.height * commonScale
                    val w = original.width * commonScale
                    // Preserve vertical motion from the source, smooth small mask jitter.
                    val movement = (medianBottom - bottoms[i]) * commonScale
                    smoothedMovement = if (i == 0) movement else
                        .60f * movement + .40f * smoothedMovement
                    val center = 128f
                    val bottom = 234f + smoothedMovement.coerceIn(-70f, 70f)
                    val normalized = Bitmap.createBitmap(CANVAS, CANVAS, Bitmap.Config.ARGB_8888)
                    Canvas(normalized).drawBitmap(
                        original, null,
                        RectF(center - w / 2f, bottom - h, center + w / 2f, bottom),
                        Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                    )
                    normalized
                } finally { original.recycle() }
                try {
                    val smooth = featherAlpha(output)
                    try {
                        FileOutputStream(File(staging, "frame_" + i.toString().padStart(3, '0') + ".png")).use {
                            check(smooth.compress(Bitmap.CompressFormat.PNG, 100, it))
                        }
                    } finally { smooth.recycle() }
                } finally { output.recycle() }
                saved++
                progress(total + saved, total * 2)
            }
            raw.deleteRecursively()
            File(staging, "fps.txt").writeText(FPS.toString())
            softenLoop(staging, saved)
            check(saved >= 2)
            backup.deleteRecursively()
            if (target.exists()) check(target.renameTo(backup)) { "Cannot backup existing animation" }
            if (!staging.renameTo(target)) {
                if (backup.exists()) backup.renameTo(target)
                error("Cannot save animation")
            }
            backup.deleteRecursively()
            return saved
        } finally {
            retriever.release()
            segmenter.close()
            if (staging.exists()) staging.deleteRecursively()
        }
    }

    private fun subjectBottom(bitmap: Bitmap): Int {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        for (y in h - 1 downTo 0) {
            var found = 0
            for (x in 0 until w step 2) {
                if ((pixels[y * w + x] ushr 24) > 50) found++
            }
            if (found >= 2) return y
        }
        return h - 1
    }

    /** Mild edge feather for translucent fur and whiskers; no semantic mask repair. */
    private fun featherAlpha(original: Bitmap): Bitmap {
        val w = original.width
        val h = original.height
        val pixels = IntArray(w * h)
        original.getPixels(pixels, 0, w, 0, 0, w, h)
        val newPixels = pixels.clone()
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            val center = pixels[i] ushr 24
            val left = pixels[i - 1] ushr 24
            val right = pixels[i + 1] ushr 24
            val top = pixels[i - w] ushr 24
            val bottom = pixels[i + w] ushr 24
            if (center == 255 && left == 255 && right == 255 && top == 255 && bottom == 255) continue
            if (center == 0 && left == 0 && right == 0 && top == 0 && bottom == 0) continue
            val alpha = ((center * 6 + left + right + top + bottom) / 10).coerceIn(0, 255)
            val color = if (center > 0) pixels[i] else {
                val near = listOf(i-1, i+1, i-w, i+w).maxByOrNull { pixels[it] ushr 24 } ?: i
                pixels[near]
            }
            newPixels[i] = (alpha shl 24) or (color and 0x00ffffff)
        }
        return Bitmap.createBitmap(newPixels, w, h, Bitmap.Config.ARGB_8888)
    }

    /** Only crossfade the loop seam if the start/end silhouettes overlap well. */
    private fun softenLoop(dir: File, frames: Int) {
        if (frames < 12) return
        fun file(i: Int) = File(dir, "frame_" + i.toString().padStart(3, '0') + ".png")
        val first = BitmapFactory.decodeFile(file(0).path) ?: return
        val last = BitmapFactory.decodeFile(file(frames - 1).path) ?: run { first.recycle(); return }
        try {
            val p1 = IntArray(first.width * first.height)
            val p2 = IntArray(last.width * last.height)
            first.getPixels(p1, 0, first.width, 0, 0, first.width, first.height)
            last.getPixels(p2, 0, last.width, 0, 0, last.width, last.height)
            var intersection = 0
            var union = 0
            for (i in p1.indices step 4) {
                val a = (p1[i] ushr 24) > 128
                val b = (p2[i] ushr 24) > 128
                if (a && b) intersection++
                if (a || b) union++
            }
            if (union == 0 || intersection.toFloat() / union < .78f) return
            for (i in 0 until 4) {
                val path = file(frames - 4 + i)
                val tail = BitmapFactory.decodeFile(path.path) ?: continue
                val head = BitmapFactory.decodeFile(file(i).path)
                if (head == null) { tail.recycle(); continue }
                val blend = tail.copy(Bitmap.Config.ARGB_8888, true)
                try {
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        alpha = ((i + 1) * 255 / 5).coerceIn(0, 255)
                    }
                    Canvas(blend).drawBitmap(head, 0f, 0f, paint)
                    FileOutputStream(path).use {
                        check(blend.compress(Bitmap.CompressFormat.PNG, 100, it))
                    }
                } finally { tail.recycle(); head.recycle(); blend.recycle() }
            }
        } finally { first.recycle(); last.recycle() }
    }
}
