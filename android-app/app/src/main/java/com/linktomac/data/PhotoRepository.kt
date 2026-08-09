package com.linktomac.data

import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Base64
import android.util.Size
import com.linktomac.net.PhotoThumbnail
import java.io.ByteArrayOutputStream

/**
 * Reads photos from MediaStore, paginated in Kotlin rather than via SQL LIMIT/OFFSET — see
 * docs/PROTOCOL.md's OEM-provider-compatibility note (same reasoning as CallLogRepository).
 * Thumbnails are generated on demand, only for the requested page, not the whole library.
 */
class PhotoRepository(private val context: Context) {
    private var observer: ContentObserver? = null

    private data class PhotoRow(val id: Long, val takenAt: Long)

    /** All photo ids/dates, newest first — cheap (two columns), used to compute a page. */
    private fun readAllRows(): List<PhotoRow> {
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED
        )
        val cursor = try {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                "${MediaStore.Images.Media.DATE_TAKEN} DESC"
            )
        } catch (e: SecurityException) {
            null
        } ?: return emptyList()

        val rows = mutableListOf<PhotoRow>()
        cursor.use {
            val idCol = it.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val takenCol = it.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            val addedCol = it.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            while (it.moveToNext()) {
                val taken = it.getLong(takenCol)
                val takenAt = if (taken > 0) taken else it.getLong(addedCol) * 1000
                rows.add(PhotoRow(id = it.getLong(idCol), takenAt = takenAt))
            }
        }
        return rows
    }

    fun readPage(offset: Int, limit: Int): Pair<List<PhotoThumbnail>, Boolean> {
        val rows = readAllRows()
        val page = rows.drop(offset).take(limit)
        val hasMore = offset + page.size < rows.size
        val thumbnails = page.mapNotNull { buildThumbnail(it) }
        return thumbnails to hasMore
    }

    /** Returns raw file bytes + mime type, or null if the photo no longer exists / no permission. */
    fun readFull(id: String): Pair<ByteArray, String>? {
        val longId = id.toLongOrNull() ?: return null
        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, longId)
        val bytes = try {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        } catch (e: SecurityException) {
            null
        } ?: return null
        val mimeType = context.contentResolver.getType(uri) ?: "image/jpeg"
        return bytes to mimeType
    }

    private fun buildThumbnail(row: PhotoRow): PhotoThumbnail? {
        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, row.id)
        val bitmap = loadThumbnailBitmap(uri) ?: return null
        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, THUMBNAIL_QUALITY, output)
        bitmap.recycle()
        return PhotoThumbnail(
            id = row.id.toString(),
            takenAt = row.takenAt.toDouble(),
            thumbnailBase64 = Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
        )
    }

    private fun loadThumbnailBitmap(uri: Uri): Bitmap? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.contentResolver.loadThumbnail(uri, Size(THUMBNAIL_SIZE, THUMBNAIL_SIZE), null)
            } else {
                decodeDownscaled(uri)
            }
        } catch (e: Exception) {
            null
        }
    }

    /** API 26–28 fallback: loadThumbnail() needs API 29+, so decode+downscale manually. */
    private fun decodeDownscaled(uri: Uri): Bitmap? {
        val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, boundsOptions) }

        var sampleSize = 1
        while (boundsOptions.outWidth / sampleSize > THUMBNAIL_SIZE * 2 ||
            boundsOptions.outHeight / sampleSize > THUMBNAIL_SIZE * 2
        ) {
            sampleSize *= 2
        }
        val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        return context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, decodeOptions) }
    }

    /** No-ops without photo access rather than crashing — see CallLogRepository.observe for
     *  the same reasoning. Idempotent: safe to call again after the user grants access. */
    fun observe(onChange: () -> Unit) {
        if (observer != null) return
        val contentObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = onChange()
        }
        try {
            context.contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, contentObserver)
            observer = contentObserver
        } catch (e: SecurityException) {
            // Not granted yet — caller can retry once permission is granted.
        }
    }

    fun stopObserving() {
        observer?.let { context.contentResolver.unregisterContentObserver(it) }
        observer = null
    }

    companion object {
        private const val THUMBNAIL_SIZE = 300
        private const val THUMBNAIL_QUALITY = 60
    }
}
