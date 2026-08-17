package com.vanta.app.ui.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Base64
import android.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ImageUtils {

    /**
     * Creates a temporary file in the cache directory for camera photo capture.
     */
    fun createTempImageFile(context: Context): File {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val storageDir = File(context.cacheDir, "camera_photos").apply { mkdirs() }
        return File.createTempFile("VANTA_IMG_${timeStamp}_", ".jpg", storageDir)
    }

    /**
     * Reads an image Uri, correctly rotates it according to EXIF data, scales it
     * to a maximum dimension of 768px, compresses it as lightweight JPEG, and returns Base64.
     */
    suspend fun processImageUri(context: Context, uri: Uri): Pair<String, String>? = withContext(Dispatchers.IO) {
        try {
            // First decode bounds to compute optimal inSampleSize to save memory
            var inputStream: InputStream? = context.contentResolver.openInputStream(uri) ?: return@withContext null
            val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeStream(inputStream, null, boundsOpts)
            inputStream?.close()

            val maxDimension = 768
            var sampleSize = 1
            while (boundsOpts.outWidth / (sampleSize * 2) >= maxDimension || boundsOpts.outHeight / (sampleSize * 2) >= maxDimension) {
                sampleSize *= 2
            }

            // Decode actual bitmap with subsampling
            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            inputStream = context.contentResolver.openInputStream(uri) ?: return@withContext null
            val originalBitmap = BitmapFactory.decodeStream(inputStream, null, decodeOpts)
            inputStream?.close()

            if (originalBitmap == null) return@withContext null

            // Correct EXIF orientation
            var orientedBitmap = originalBitmap
            try {
                inputStream = context.contentResolver.openInputStream(uri)
                if (inputStream != null) {
                    val exif = ExifInterface(inputStream)
                    val orientation = exif.getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_UNDEFINED
                    )
                    val matrix = Matrix()
                    when (orientation) {
                        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
                        ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
                    }
                    orientedBitmap = Bitmap.createBitmap(
                        originalBitmap,
                        0, 0,
                        originalBitmap.width,
                        originalBitmap.height,
                        matrix,
                        true
                    )
                }
            } catch (e: Exception) {
                // Ignore exif errors and use original
            } finally {
                inputStream?.close()
            }

            // Scale to exact target bounds (max 768px on longest side)
            val width = orientedBitmap.width
            val height = orientedBitmap.height
            val scale = if (width > maxDimension || height > maxDimension) {
                val scaleWidth = maxDimension.toFloat() / width
                val scaleHeight = maxDimension.toFloat() / height
                minOf(scaleWidth, scaleHeight)
            } else {
                1.0f
            }

            val scaledBitmap = if (scale < 1.0f) {
                Bitmap.createScaledBitmap(
                    orientedBitmap,
                    (width * scale).toInt().coerceAtLeast(1),
                    (height * scale).toInt().coerceAtLeast(1),
                    true
                )
            } else {
                orientedBitmap
            }

            // Compress to JPEG with 75% quality (fast transmission & optimal token/memory ratio)
            val baos = ByteArrayOutputStream()
            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 75, baos)
            val bytes = baos.toByteArray()
            val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)

            Pair(base64, "image/jpeg")
        } catch (e: Exception) {
            android.util.Log.e("ImageUtils", "Failed to process image uri: $uri", e)
            null
        }
    }

    /**
     * Decodes a Uri into a memory-efficient Bitmap for local Compose display.
     */
    suspend fun loadBitmapFromUri(context: Context, uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
        try {
            var stream = context.contentResolver.openInputStream(uri) ?: return@withContext null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeStream(stream, null, bounds)
            stream.close()

            var sample = 1
            while (bounds.outWidth / (sample * 2) >= 600 || bounds.outHeight / (sample * 2) >= 600) {
                sample *= 2
            }

            stream = context.contentResolver.openInputStream(uri) ?: return@withContext null
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bitmap = BitmapFactory.decodeStream(stream, null, opts)
            stream.close()
            bitmap
        } catch (e: Exception) {
            null
        }
    }
}
