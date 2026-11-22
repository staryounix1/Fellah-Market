package com.example.core.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

object ImageCompressor {
    suspend fun compressAndSave(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }

            val maxDim = 1024
            var sampleSize = 1
            while (bounds.outWidth / sampleSize > maxDim || bounds.outHeight / sampleSize > maxDim) {
                sampleSize *= 2
            }

            val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val bitmap = resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            } ?: return@runCatching null

            val dir = File(context.filesDir, "offer_photos").apply { mkdirs() }
            val outFile = File(dir, "photo_${UUID.randomUUID()}.jpg")
            FileOutputStream(outFile).use { fos ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 75, fos)
            }
            bitmap.recycle()
            outFile.absolutePath
        }.getOrNull()
    }
}
