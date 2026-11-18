package com.bruhascended.dustit.data

import android.content.ContentResolver
import android.content.ContentUris
import android.net.Uri
import androidx.core.net.toUri
import android.provider.MediaStore
import android.provider.MediaStore.MediaColumns

object MediaStoreSource {
    /** All external volumes (internal storage and SD cards). Trashed and pending items are excluded by default. */
    val images: Uri = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
    val videos: Uri = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)

    fun uri(id: Long, kind: MediaKind): Uri = ContentUris.withAppendedId(collection(kind), id)

    fun queryAll(resolver: ContentResolver): List<MediaEntity> =
        query(resolver, MediaKind.Image) + query(resolver, MediaKind.Video)

    private fun collection(kind: MediaKind) = when (kind) {
        MediaKind.Image -> images
        MediaKind.Video -> videos
    }

    private fun query(resolver: ContentResolver, kind: MediaKind): List<MediaEntity> {
        val projection = arrayOf(
            MediaColumns._ID,
            MediaColumns.DISPLAY_NAME,
            MediaColumns.RELATIVE_PATH,
            MediaColumns.MIME_TYPE,
            MediaColumns.SIZE,
            MediaColumns.WIDTH,
            MediaColumns.HEIGHT,
            MediaColumns.DURATION,
            MediaColumns.DATE_TAKEN,
            MediaColumns.DATE_MODIFIED,
        )
        val result = ArrayList<MediaEntity>()
        resolver.query(collection(kind), projection, null, null, null)?.use { c ->
            val id = c.getColumnIndexOrThrow(MediaColumns._ID)
            val name = c.getColumnIndexOrThrow(MediaColumns.DISPLAY_NAME)
            val path = c.getColumnIndexOrThrow(MediaColumns.RELATIVE_PATH)
            val mime = c.getColumnIndexOrThrow(MediaColumns.MIME_TYPE)
            val size = c.getColumnIndexOrThrow(MediaColumns.SIZE)
            val width = c.getColumnIndexOrThrow(MediaColumns.WIDTH)
            val height = c.getColumnIndexOrThrow(MediaColumns.HEIGHT)
            val duration = c.getColumnIndexOrThrow(MediaColumns.DURATION)
            val taken = c.getColumnIndexOrThrow(MediaColumns.DATE_TAKEN)
            val modified = c.getColumnIndexOrThrow(MediaColumns.DATE_MODIFIED)
            result.ensureCapacity(c.count)
            while (c.moveToNext()) {
                val dateModified = c.getLong(modified)
                result += MediaEntity(
                    id = c.getLong(id),
                    kind = kind,
                    displayName = c.getString(name).orEmpty(),
                    relativePath = c.getString(path).orEmpty(),
                    mimeType = c.getString(mime).orEmpty(),
                    size = c.getLong(size),
                    width = c.getInt(width),
                    height = c.getInt(height),
                    durationMs = c.getLong(duration),
                    dateTaken = c.getLong(taken).takeIf { it > 0 } ?: (dateModified * 1000),
                    dateModified = dateModified,
                )
            }
        }
        return result
    }
}

/** Where to read or show an item: its MediaStore URI, or its document URI for a file from a hidden folder. */
fun contentUri(id: Long, kind: MediaKind, documentUri: String?): Uri =
    documentUri?.toUri() ?: MediaStoreSource.uri(id, kind)

val MediaEntity.contentUri: Uri get() = contentUri(id, kind, documentUri)
