package com.example.catcompanion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min

/** Local-first processing. No developer-operated server receives the photo. */
internal object CatPhotoProcessor {
    data class Cutout(val bitmap: Bitmap, val catConfidence: Float?)

    fun load(context: Context, uri: Uri): Bitmap {
        if (Build.VERSION.SDK_INT >= 28) {
            return ImageDecoder.decodeBitmap(
                ImageDecoder.createSource(context.contentResolver, uri)
            ) { decoder, info, _ ->
                val w = info.size.width
                val h = info.size.height
                require(w > 0 && h > 0) { "Invalid image dimensions" }
                val scale = min(1f, 1024f / max(w, h).toFloat())
                decoder.setTargetSize(max(1, (w * scale).toInt()), max(1, (h * scale).toInt()))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Cannot open image" }
            BitmapFactory.decodeStream(input, null, bounds)
        }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Unsupported image format" }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > 1024) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Cannot reopen image" }
            BitmapFactory.decodeStream(input, null, options)
        } ?: error("Unable to decode image")
    }

    /** The classifier is advisory: unfamiliar cats can still be segmented. */
    fun cutout(input: Bitmap, done: (Result<Cutout>) -> Unit) {
        val image = InputImage.fromBitmap(input, 0)
        val labeler = ImageLabeling.getClient(ImageLabelerOptions.DEFAULT_OPTIONS)
        labeler.process(image).addOnCompleteListener { labelTask ->
            val catScore = if (labelTask.isSuccessful) {
                labelTask.result.filter {
                    val label = it.text.lowercase()
                    label == "cat" || label == "kitten"
                }.maxOfOrNull { it.confidence }
            } else null
            labeler.close()
            val segmenter = SubjectSegmentation.getClient(
                SubjectSegmenterOptions.Builder().enableForegroundBitmap().build()
            )
            segmenter.process(image).addOnSuccessListener { segmentation ->
                val foreground = segmentation.foregroundBitmap
                segmenter.close()
                if (foreground == null) {
                    done(Result.failure(IllegalStateException("No foreground subject found")))
                } else {
                    Thread {
                        try {
                            done(Result.success(Cutout(cropToContent(foreground), catScore)))
                        } catch (error: Exception) {
                            done(Result.failure(error))
                        }
                    }.start()
                }
            }.addOnFailureListener { error ->
                segmenter.close()
                done(Result.failure(error))
            }
        }
    }

    /**
     * Remove transparent margins returned by ML Kit, preserve 4% padding so
     * paws and ears do not touch the floating window boundary.
     */
    fun cropToContent(source: Bitmap): Bitmap {
        val w = source.width
        val h = source.height
        require(w > 0 && h > 0)
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)
        var left = w
        var top = h
        var right = -1
        var bottom = -1
        for (y in 0 until h) {
            for (x in 0 until w) {
                if ((pixels[y * w + x] ushr 24) > 20) {
                    if (x < left) left = x
                    if (x > right) right = x
                    if (y < top) top = y
                    if (y > bottom) bottom = y
                }
            }
        }
        check(right >= left && bottom >= top) { "AI result contains no visible foreground" }
        val cw = right - left + 1
        val ch = bottom - top + 1
        val pad = max(4, (max(cw, ch) * .04f).toInt())
        val result = Bitmap.createBitmap(cw + pad * 2, ch + pad * 2, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.drawBitmap(source, -left + pad.toFloat(), -top + pad.toFloat(), null)
        return result
    }

    /** Atomic-ish local PNG update so a failed write does not destroy the old pet. */
    fun save(context: Context, bitmap: Bitmap) {
        val destination = PetSettings.petImage(context)
        val temporary = File(context.filesDir, "custom_pet_new.png")
        try {
            FileOutputStream(temporary).use { out ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) { "Could not encode PNG" }
                out.fd.sync()
            }
            if (!temporary.renameTo(destination)) {
                temporary.copyTo(destination, overwrite = true)
                check(temporary.delete() || !temporary.exists())
            }
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }
}
