package com.bruhascended.dustit.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.bruhascended.dustit.ml.JunkCategory

enum class MediaKind { Image, Video }

/**
 * One photo or video. Mostly a mirror of a MediaStore row, where [id] is the MediaStore `_ID`
 * (unique across images and videos). Files from WhatsApp's hidden folders, which MediaStore
 * doesn't expose, carry their [documentUri] instead and a negative [id] (see `hiddenMediaId`).
 */
@Entity(tableName = "media", indices = [Index("date_taken")])
data class MediaEntity(
    @PrimaryKey val id: Long,
    val kind: MediaKind,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "relative_path") val relativePath: String,
    @ColumnInfo(name = "mime_type") val mimeType: String,
    val size: Long,
    val width: Int,
    val height: Int,
    /** 0 for images. */
    @ColumnInfo(name = "duration_ms") val durationMs: Long,
    /** Epoch millis; falls back to the modification time when there is no capture date. */
    @ColumnInfo(name = "date_taken") val dateTaken: Long,
    /** Epoch seconds, straight from MediaStore. A change means the pixels may have changed. */
    @ColumnInfo(name = "date_modified") val dateModified: Long,
    /** Set only for files from WhatsApp's hidden folders, reached through a folder the user granted. */
    @ColumnInfo(name = "document_uri") val documentUri: String? = null,
)

/**
 * Everything computed from an image's pixels in one scan pass. Kept apart from [MediaEntity]
 * so bumping the analysis version only re-analyses, it never re-indexes.
 */
@Entity(tableName = "analysis", indices = [Index("category", "score")])
class AnalysisEntity(
    @PrimaryKey @ColumnInfo(name = "media_id") val mediaId: Long,
    /** Null when the image couldn't be decoded; stored anyway so it isn't retried every scan. */
    val category: JunkCategory?,
    val score: Float,
    /** Variance of the Laplacian; higher is sharper. Only comparable between similar shots. */
    val sharpness: Float,
    @ColumnInfo(name = "average_hash") val averageHash: Long,
    /** 16x16 grayscale thumbnail used to confirm near-duplicates; null when undecodable. */
    val tiny: ByteArray?,
    val version: Int,
)

/** Membership in a set of near-identical photos, recomputed after every scan that changes anything. */
@Entity(tableName = "similar_member", indices = [Index("group_id")])
data class SimilarMemberEntity(
    @PrimaryKey @ColumnInfo(name = "media_id") val mediaId: Long,
    @ColumnInfo(name = "group_id") val groupId: Long,
)

/** Cached partial-content fingerprint; only valid while [size] and [dateModified] still match the media row. */
@Entity(tableName = "content_hash")
data class ContentHashEntity(
    @PrimaryKey @ColumnInfo(name = "media_id") val mediaId: Long,
    val size: Long,
    @ColumnInfo(name = "date_modified") val dateModified: Long,
    val hash: String,
)

/** Everything the cleanup rules need about one item, joined in a single query. */
data class MediaRow(
    val id: Long,
    val kind: MediaKind,
    val size: Long,
    val relativePath: String,
    val dateTaken: Long,
    val width: Int,
    val height: Int,
    val durationMs: Long,
    val category: JunkCategory?,
    val score: Float?,
    val sharpness: Float?,
    val similarGroup: Long?,
    val documentUri: String? = null,
)

data class PendingImage(val id: Long, val mimeType: String, val relativePath: String, val documentUri: String?)

/** A file in DustIt's own trash for hidden folders (see `HiddenTrash`). A cache of what's on disk. */
@Entity(tableName = "trashed_file")
data class TrashedFileEntity(
    /** Document URI of the renamed file. */
    @PrimaryKey val uri: String,
    @ColumnInfo(name = "original_name") val originalName: String,
    @ColumnInfo(name = "relative_path") val relativePath: String,
    val size: Long,
    @ColumnInfo(name = "trashed_at") val trashedAt: Long,
)

data class TrashSummary(val count: Int, val bytes: Long)

/**
 * A hidden folder as of its last listing. Adding, removing or renaming a file changes a folder's
 * modification time, so while it matches, the folder's index rows are still right and listing it
 * again (slow: the provider checks every file) can be skipped.
 */
@Entity(tableName = "hidden_folder")
data class HiddenFolderEntity(
    @PrimaryKey @ColumnInfo(name = "relative_path") val relativePath: String,
    @ColumnInfo(name = "document_id") val documentId: String,
    /** The granted folder this was listed through; stamps from another grant are ignored. */
    val tree: String,
    val modified: Long,
)

class SimilarityInput(
    val id: Long,
    val category: JunkCategory?,
    val relativePath: String,
    val dateTaken: Long,
    val width: Int,
    val height: Int,
    val averageHash: Long,
    val tiny: ByteArray,
    val sharpness: Float,
)
