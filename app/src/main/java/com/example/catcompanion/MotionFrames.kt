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
    private const val CANVAS = 320
    private const val FRAME_INTERVAL_MS = 125L

    private fun root(context: Context) = File(context.filesDir, "cat_motion")
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

    fun load(context: Context, action: PetView.Action): List<Bitmap> {
        val files = folder(context, action).listFiles()
            ?.filter { it.isFile && it.name.matches(Regex("frame_[0-9]{3}\\.png")) }
            ?.sortedBy { it.name }?.take(20) ?: emptyList()
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

    fun frameIndex(elapsedMs: Long, count: Int): Int =
        if (count < 2) 0 else ((elapsedMs.coerceAtLeast(0L) / FRAME_INTERVAL_MS) % count).toInt()

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
            val length = min(duration, 2500L)
            val total = min(20, max(4, (length / FRAME_INTERVAL_MS).toInt()))
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull()?.coerceAtLeast(1) ?: 512
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull()?.coerceAtLeast(1) ?: 512
            val scale = min(1f, 512f / max(width, height))
            val scaledW = max(1, (width * scale).toInt())
            val scaledH = max(1, (height * scale).toInt())

            for (i in 0 until total) {
                val whenUs = (i.toLong() * length * 1000L / total)
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
                val cut = CatPhotoProcessor.cropToContent(foreground)
                val output = normalizeFrame(cut)
                if (cut !== foreground) cut.recycle()
                foreground.recycle()
                val destination = File(staging, "frame_${i.toString().padStart(3, '0')}.png")
                try {
                    FileOutputStream(destination).use { out ->
                        check(output.compress(Bitmap.CompressFormat.PNG, 100, out))
                    }
                } finally {
                    output.recycle()
                }
                saved++
                progress(saved, total)
            }
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

    private fun normalizeFrame(bitmap: Bitmap): Bitmap {
        val output = Bitmap.createBitmap(CANVAS, CANVAS, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        // Keep individual frames centered and bottom-aligned so the feet stay near the ground.
        val scale = min(276f / bitmap.width, 280f / bitmap.height)
        val w = bitmap.width * scale
        val h = bitmap.height * scale
        canvas.drawBitmap(
            bitmap, null,
            RectF((CANVAS - w) / 2f, 294f - h, (CANVAS + w) / 2f, 294f),
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        )
        return output
    }
}
