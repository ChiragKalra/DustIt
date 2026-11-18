package com.bruhascended.dustit.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Android 11's SQLite caps bound parameters at 999; stay well under it for `IN (...)` lists. */
private const val MAX_SQL_ARGS = 500

@Dao
abstract class MediaDao {

    // region Sync

    @Query("SELECT * FROM media")
    abstract suspend fun allMedia(): List<MediaEntity>

    /** Rows synced from MediaStore; hidden-folder rows are synced separately and never touched by it. */
    @Query("SELECT * FROM media WHERE document_uri IS NULL")
    abstract suspend fun mediaStoreMedia(): List<MediaEntity>

    @Query("SELECT * FROM media WHERE document_uri IS NOT NULL")
    abstract suspend fun hiddenMedia(): List<MediaEntity>

    /** `relative_path + display_name` of every MediaStore item stored under an app's media folder. */
    @Query("SELECT relative_path || display_name FROM media WHERE document_uri IS NULL AND relative_path LIKE 'Android/media/%'")
    abstract suspend fun visibleAppMediaPaths(): List<String>

    /**
     * Hidden-folder rows for a file MediaStore shows too, matched by path the same way the
     * hidden-folder sync filters its listing. Only happens if a folder loses its `.nomedia`.
     */
    @Query(
        """
        SELECT h.id FROM media h
        JOIN media v ON v.document_uri IS NULL AND v.relative_path = h.relative_path AND v.display_name = h.display_name
        WHERE h.document_uri IS NOT NULL
        """
    )
    abstract suspend fun hiddenAlsoInMediaStore(): List<Long>

    @Query("SELECT * FROM media WHERE id IN (:ids)")
    protected abstract suspend fun mediaByIdsChunk(ids: List<Long>): List<MediaEntity>

    open suspend fun mediaByIds(ids: List<Long>): List<MediaEntity> = ids.chunked(MAX_SQL_ARGS).flatMap { mediaByIdsChunk(it) }

    @Upsert
    abstract suspend fun upsertMedia(items: List<MediaEntity>)

    @Query("DELETE FROM media WHERE id IN (:ids)")
    abstract suspend fun deleteMediaRows(ids: List<Long>)

    @Query("DELETE FROM analysis WHERE media_id IN (:ids)")
    abstract suspend fun deleteAnalysisRows(ids: List<Long>)

    @Query("DELETE FROM similar_member WHERE media_id IN (:ids)")
    abstract suspend fun deleteSimilarRows(ids: List<Long>)

    @Transaction
    open suspend fun applySync(plan: SyncPlan) {
        forgetNow(plan.removedIds)
        plan.contentChangedIds.chunked(MAX_SQL_ARGS).forEach {
            deleteAnalysisRows(it)
            deleteSimilarRows(it)
        }
        if (plan.upserts.isNotEmpty()) upsertMedia(plan.upserts)
    }

    /** Drops rows for items the user just trashed, without waiting for the next sync. */
    @Transaction
    open suspend fun forget(ids: List<Long>) = forgetNow(ids)

    private suspend fun forgetNow(ids: List<Long>) {
        ids.chunked(MAX_SQL_ARGS).forEach {
            deleteContentHashRows(it)
            deleteSimilarRows(it)
            deleteAnalysisRows(it)
            deleteMediaRows(it)
        }
    }

    // endregion

    // region Analysis

    @Query(
        """
        SELECT m.id, m.mime_type AS mimeType, m.relative_path AS relativePath, m.document_uri AS documentUri FROM media m
        LEFT JOIN analysis a ON a.media_id = m.id
        WHERE m.kind = 'Image' AND (a.media_id IS NULL OR a.version != :version)
        ORDER BY m.document_uri IS NOT NULL, m.date_taken DESC
        """
    )
    abstract suspend fun imagesNeedingAnalysis(version: Int): List<PendingImage>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertAnalysis(items: List<AnalysisEntity>)

    @Query("SELECT COUNT(*) FROM media WHERE kind = 'Image'")
    abstract fun imageCount(): Flow<Int>

    @Query(
        """
        SELECT COUNT(*) FROM analysis a
        JOIN media m ON m.id = a.media_id
        WHERE a.version = :version
        """
    )
    abstract fun analyzedCount(version: Int): Flow<Int>

    // endregion

    // region Similar photos

    @Query(
        """
        SELECT a.media_id AS id, a.category, m.relative_path AS relativePath, m.date_taken AS dateTaken, m.width, m.height,
               a.average_hash AS averageHash, a.tiny, a.sharpness
        FROM analysis a
        JOIN media m ON m.id = a.media_id
        WHERE a.version = :version AND a.tiny IS NOT NULL
        """
    )
    abstract suspend fun similarityInputs(version: Int): List<SimilarityInput>

    @Query("DELETE FROM content_hash WHERE media_id IN (:ids)")
    abstract suspend fun deleteContentHashRows(ids: List<Long>)

    @Query("SELECT * FROM content_hash")
    abstract suspend fun contentHashes(): List<ContentHashEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertContentHashes(items: List<ContentHashEntity>)

    @Query("DELETE FROM similar_member")
    abstract suspend fun clearSimilar()

    @Insert
    abstract suspend fun insertSimilar(items: List<SimilarMemberEntity>)

    @Transaction
    open suspend fun replaceSimilar(items: List<SimilarMemberEntity>) {
        clearSimilar()
        if (items.isNotEmpty()) insertSimilar(items)
    }

    // endregion

    // region Cleanup rules input

    @Query(
        """
        SELECT m.id, m.kind, m.size, m.relative_path AS relativePath, m.date_taken AS dateTaken,
               m.width, m.height, m.duration_ms AS durationMs,
               a.category, a.score, a.sharpness, s.group_id AS similarGroup, m.document_uri AS documentUri
        FROM media m
        LEFT JOIN analysis a ON a.media_id = m.id AND a.version = :version
        LEFT JOIN similar_member s ON s.media_id = m.id
        """
    )
    abstract suspend fun rows(version: Int): List<MediaRow>

    // endregion

    // region Hidden folders

    @Query("SELECT * FROM hidden_folder WHERE tree = :tree")
    abstract suspend fun hiddenFolders(tree: String): List<HiddenFolderEntity>

    @Query("DELETE FROM hidden_folder")
    abstract suspend fun clearHiddenFolders()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertHiddenFolders(items: List<HiddenFolderEntity>)

    @Transaction
    open suspend fun replaceHiddenFolders(items: List<HiddenFolderEntity>) {
        clearHiddenFolders()
        if (items.isNotEmpty()) insertHiddenFolders(items)
    }

    // endregion

    // region Hidden-folder trash

    @Query("SELECT * FROM trashed_file")
    abstract suspend fun trashedFiles(): List<TrashedFileEntity>

    @Query("SELECT COUNT(*) AS count, COALESCE(SUM(size), 0) AS bytes FROM trashed_file")
    abstract fun trashSummary(): Flow<TrashSummary>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertTrash(items: List<TrashedFileEntity>)

    @Query("DELETE FROM trashed_file")
    abstract suspend fun clearTrash()

    @Query("DELETE FROM trashed_file WHERE uri IN (:uris)")
    protected abstract suspend fun deleteTrashChunk(uris: List<String>)

    @Transaction
    open suspend fun deleteTrash(uris: List<String>) = uris.chunked(MAX_SQL_ARGS).forEach { deleteTrashChunk(it) }

    @Transaction
    open suspend fun replaceTrash(items: List<TrashedFileEntity>) {
        clearTrash()
        if (items.isNotEmpty()) insertTrash(items)
    }

    /** Files just moved to the trash: their rows go, and their trash entries appear, in one change. */
    @Transaction
    open suspend fun moveToTrash(ids: List<Long>, entries: List<TrashedFileEntity>) {
        forgetNow(ids)
        if (entries.isNotEmpty()) insertTrash(entries)
    }

    // endregion
}
