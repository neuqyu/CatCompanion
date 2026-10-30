package com.example.catcompanion
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min

internal object CatPhotoProcessor {
    fun load(context: Context, uri: Uri): Bitmap {
        if (Build.VERSION.SDK_INT >= 28) return ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            val scale = min(1f, 1024f / max(info.size.width, info.size.height))
            decoder.setTargetSize(max(1, (info.size.width * scale).toInt()), max(1, (info.size.height * scale).toInt()))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        return context.contentResolver.openInputStream(uri).use {
            BitmapFactory.decodeStream(it)
        } ?: error("Cannot decode photo")
    }
    fun cutout(bitmap: Bitmap, done: (Result<Bitmap>) -> Unit) {
        val labeler = ImageLabeling.getClient(ImageLabelerOptions.DEFAULT_OPTIONS)
        labeler.process(InputImage.fromBitmap(bitmap, 0)).addOnCompleteListener { task ->
            labeler.close()
            val isCat = task.isSuccessful && task.result.any { it.text.equals("Cat", true) }
            if (!isCat) android.util.Log.i("CatCompanion", "Image labeling did not identify cat; attempting generic subject cutout")
            val model = SubjectSegmentation.getClient(SubjectSegmenterOptions.Builder().enableForegroundBitmap().build())
            model.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { r ->
                    val image = r.foregroundBitmap
                    if (image != null) done(Result.success(image))
                    else done(Result.failure(IllegalStateException("No foreground detected")))
                    model.close()
                }.addOnFailureListener {
                    model.close()
                    done(Result.failure(it))
                }
        }
    }
    fun save(context: Context, bitmap: Bitmap) {
        FileOutputStream(PetSettings.petImage(context)).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
