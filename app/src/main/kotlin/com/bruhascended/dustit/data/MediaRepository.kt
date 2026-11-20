package com.bruhascended.dustit.data

import android.app.PendingIntent
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.edit
import androidx.core.net.toUri
import com.bruhascended.dustit.cleanup.SimilarityGrouper
import com.bruhascended.dustit.scan.ImageAnalyzer
import com.bruhascended.dustit.whatsapp.HiddenFilesService
import com.bruhascended.dustit.whatsapp.HiddenFolderSource
import com.bruhascended.dustit.whatsapp.HiddenTrash
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

data class ScanProgress(val analyzed: Int, val total: Int) {
    val isComplete: Boolean get() = analyzed >= total
}

private const val TAG = "DustIt"

/** Minimum gap between whole-library reads while the index is changing (a scan writes several times a second). */
private const val ROWS_REFRESH_MS = 1_000L

/** Bump when similar-photo grouping changes, so existing libraries are regrouped on the next scan. */
private const val GROUPING_VERSION = 2

/**
 * Renames and deletes in hidden folders run a few at a time: each is a round trip to the system's
 * storage provider, which also updates its own index, so a big batch is otherwise slow.
 */
private const val FILE_OP_PARALLELISM = 4

/** Hidden-folder files moved to the trash per database commit. */
private const val TRASH_COMMIT = 100
private const val KEY_GROUPING_VERSION = "grouping_version"

class MediaRepository(
    context: Context,
    database: DustDatabase,
    scope: CoroutineScope,
) {
    private val dao = database.mediaDao()
    private val appContext = context.applicationContext
    private val resolver = context.contentResolver
    private val prefs = context.getSharedPreferences("index", Context.MODE_PRIVATE)
    private val exactDuplicates = ExactDuplicates(resolver)
    private val hiddenFolders = HiddenFolderSource(resolver)
    private val fileOps = Dispatchers.IO.limitedParallelism(FILE_OP_PARALLELISM)

    /**
     * Held by every hidden-folder sync, and by each chunk of a trash, restore or delete. A sync
     * that listed a folder before a chunk renamed files in it would otherwise write the old names
     * back. Chunks keep the wait short in both directions.
     */
    private val hiddenLock = Mutex()

    /**
     * Everything the cleanup rules need, refreshed when the index changes. One read feeds every
     * screen (the last result is replayed to new screens straight away), and the throttle sits
     * before the query so a running scan can't make it run back to back.
     */
    val rows: SharedFlow<List<MediaRow>> = database.invalidationTracker
        .createFlow("media", "analysis", "similar_member")
        .conflate()
        .transform {
            emit(dao.rows(ImageAnalyzer.VERSION))
            delay(ROWS_REFRESH_MS)
        }
        .shareIn(scope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    suspend fun rowsOnce(): List<MediaRow> = dao.rows(ImageAnalyzer.VERSION)

    /** Returns true if the local index changed. */
    suspend fun syncWithMediaStore(): Boolean = withContext(Dispatchers.IO) {
        val plan = planSync(dao.mediaStoreMedia(), MediaStoreSource.queryAll(resolver))
        if (!plan.isEmpty) groupingNeeded()
        dao.applySync(plan)
        !plan.isEmpty
    }

    /**
     * Brings WhatsApp's hidden folders in [tree] into the index (or drops them all when [tree] is
     * null), refreshes DustIt's trash for them and deletes trashed files older than 30 days.
     * Returns true if the index changed. Run after [syncWithMediaStore].
     */
    suspend fun syncHiddenFolders(tree: Uri?): Boolean = withContext(Dispatchers.IO) {
        hiddenLock.withLock { syncHiddenFoldersNow(tree) }
    }

    /**
     * What a background scan does instead of [syncHiddenFolders]: nothing on storage, so it can't
     * be held up by WhatsApp's folders (listing a Sent folder that changed took 20 s on the test
     * phone, and OxygenOS freezes a background scan after 5 to 11 s). It only applies the one rule
     * a MediaStore sync can affect: no file indexed from both sources. Returns true if that
     * dropped anything.
     */
    suspend fun keepHiddenFoldersApart(): Boolean = withContext(Dispatchers.IO) {
        hiddenLock.withLock {
            val shownByMediaStore = dao.hiddenAlsoInMediaStore()
            if (shownByMediaStore.isNotEmpty()) {
                groupingNeeded()
                dao.forget(shownByMediaStore)
            }
            shownByMediaStore.isNotEmpty()
        }
    }

    private suspend fun syncHiddenFoldersNow(tree: Uri?): Boolean {
        val listing = tree?.let { hiddenFolders.list(it, dao.hiddenFolders(it.toString())) ?: return false }
        val unchanged = listing?.unchanged.orEmpty()
        val existing = dao.hiddenMedia()
        // One file indexed from both sources would look like a byte-identical duplicate of itself,
        // and trashing "the copy" would delete the only file. Never index anything MediaStore shows
        // (MediaDao.hiddenAlsoInMediaStore is the same rule, for keepHiddenFoldersApart).
        val visible = dao.visibleAppMediaPaths().toHashSet()
        val media = (listing?.media.orEmpty() + existing.filter { it.relativePath in unchanged })
            .filter { it.relativePath + it.displayName !in visible }
        val plan = planSync(existing, media)
        if (!plan.isEmpty) groupingNeeded()
        dao.applySync(plan)

        val now = System.currentTimeMillis()
        val trash = listing?.trash.orEmpty() + dao.trashedFiles().filter { it.relativePath in unchanged }
        val (expired, kept) = trash.partition { now - it.trashedAt >= HiddenTrash.KEEP_MS }
        // A delete costs as much as a rename (about 140 ms alone on the test phone), so several at once.
        val stillThere = coroutineScope {
            expired.map { entry -> async(fileOps) { entry.takeUnless { hiddenFolders.delete(it.uri.toUri()) } } }
                .awaitAll().filterNotNull()
        }
        dao.replaceTrash(kept + stillThere)
        dao.replaceHiddenFolders(listing?.folders.orEmpty())
        return !plan.isEmpty
    }

    /**
     * Moves files from hidden folders to DustIt's trash, [onProgress] counting files done.
     * Returns the ids that were moved; anything that failed stays where it was.
     *
     * Each rename costs tens of milliseconds (Android re-indexes the file), so thousands take
     * minutes. Results are committed every [TRASH_COMMIT] files: the review grid shrinks as it
     * goes, and if the process dies part-way the index matches what actually moved.
     */
    suspend fun trashHidden(ids: List<Long>, onProgress: (Int) -> Unit): List<Long> = withContext(Dispatchers.IO) {
        val items = dao.mediaByIds(ids).filter { it.documentUri != null }
        HiddenFilesService.keepRunning(appContext, HiddenFilesService.Task.Trash, items.size) { report ->
            val trashedAt = System.currentTimeMillis()
            val start = SystemClock.elapsedRealtime()
            val done = AtomicInteger()
            val movedIds = ArrayList<Long>(items.size)
            for (chunk in items.chunked(TRASH_COMMIT)) hiddenLock.withLock {
                val moved = coroutineScope {
                    chunk.map { item ->
                        async(fileOps) {
                            val renamed = hiddenFolders.rename(item.documentUri!!.toUri(), HiddenTrash.name(item.displayName, trashedAt))
                            val count = done.incrementAndGet()
                            onProgress(count)
                            report(count)
                            renamed?.let { item to it }
                        }
                    }.awaitAll().filterNotNull()
                }
                dao.moveToTrash(
                    ids = moved.map { it.first.id },
                    entries = moved.map { (item, uri) ->
                        TrashedFileEntity(uri.toString(), item.displayName, item.relativePath, item.size, trashedAt)
                    },
                )
                moved.mapTo(movedIds) { it.first.id }
            }
            Log.i(TAG, "trash: moved ${movedIds.size} of ${items.size} in ${SystemClock.elapsedRealtime() - start} ms")
            movedIds
        }
    }

    val hiddenTrash: Flow<TrashSummary> = dao.trashSummary()

    /** Puts every trashed hidden-folder file back under its old name. They reappear on the next scan. */
    suspend fun restoreHiddenTrash(): Int = withContext(Dispatchers.IO) {
        processTrash(HiddenFilesService.Task.Restore) { hiddenFolders.rename(it.uri.toUri(), it.originalName) != null }
    }

    /** Deletes every trashed hidden-folder file for good. */
    suspend fun emptyHiddenTrash(): Int = withContext(Dispatchers.IO) {
        processTrash(HiddenFilesService.Task.Delete) { hiddenFolders.delete(it.uri.toUri()) }
    }

    private val trashLock = Mutex()
    private val trashBusyState = MutableStateFlow(false)

    /** True while trashed hidden-folder files are being restored or deleted. */
    val hiddenTrashBusy: StateFlow<Boolean> = trashBusyState

    /** One restore or delete at a time; a second request waits, then finds nothing left to do. */
    private suspend fun processTrash(task: HiddenFilesService.Task, action: (TrashedFileEntity) -> Boolean): Int = trashLock.withLock {
        trashBusyState.value = true
        try {
            processTrashNow(task, action)
        } finally {
            trashBusyState.value = false
        }
    }

    private suspend fun processTrashNow(task: HiddenFilesService.Task, action: (TrashedFileEntity) -> Boolean): Int {
        val entries = dao.trashedFiles()
        return HiddenFilesService.keepRunning(appContext, task, entries.size) { report ->
            val start = SystemClock.elapsedRealtime()
            val done = AtomicInteger()
            var handledCount = 0
            for (chunk in entries.chunked(TRASH_COMMIT)) hiddenLock.withLock {
                val handled = coroutineScope {
                    chunk.map { entry ->
                        async(fileOps) { entry.uri.takeIf { action(entry) }.also { report(done.incrementAndGet()) } }
                    }.awaitAll().filterNotNull()
                }
                dao.deleteTrash(handled)
                handledCount += handled.size
            }
            Log.i(TAG, "trash: processed $handledCount of ${entries.size} in ${SystemClock.elapsedRealtime() - start} ms")
            handledCount
        }
    }

    suspend fun imagesNeedingAnalysis(): List<PendingImage> = dao.imagesNeedingAnalysis(ImageAnalyzer.VERSION)

    suspend fun saveAnalysis(items: List<AnalysisEntity>) {
        groupingNeeded()
        dao.insertAnalysis(items)
    }

    fun isGroupingOutdated(): Boolean = prefs.getInt(KEY_GROUPING_VERSION, 0) != GROUPING_VERSION

    /**
     * Called before saving anything the groups are built from. Written to disk first, so a scan
     * cut short after saving (frozen or killed in the background) still gets its groups rebuilt
     * by the next scan, which would otherwise find nothing new and skip it.
     */
    private fun groupingNeeded() {
        if (!isGroupingOutdated()) prefs.edit(commit = true) { remove(KEY_GROUPING_VERSION) }
    }

    suspend fun regroupSimilar() {
        val inputs = dao.similarityInputs(ImageAnalyzer.VERSION)
        val exact = withContext(Dispatchers.IO) { exactDuplicates.find(dao.allMedia(), dao.contentHashes()) }
        if (exact.newHashes.isNotEmpty()) dao.insertContentHashes(exact.newHashes)
        val members = withContext(Dispatchers.Default) {
            SimilarityGrouper.group(inputs, exact.groups).flatMap { ids ->
                val groupId = ids.min()
                ids.map { SimilarMemberEntity(it, groupId) }
            }
        }
        dao.replaceSimilar(members)
        prefs.edit { putInt(KEY_GROUPING_VERSION, GROUPING_VERSION) }
    }

    suspend fun forget(ids: List<Long>) = dao.forget(ids)

    fun scanProgress(): Flow<ScanProgress> =
        combine(dao.analyzedCount(ImageAnalyzer.VERSION), dao.imageCount(), ::ScanProgress)

    /**
     * System dialog that moves [uris] to the MediaStore trash, where they stay restorable for
     * 30 days. Apps holding the "media management" special access skip the dialog.
     */
    fun createTrashRequest(uris: List<Uri>): PendingIntent = MediaStore.createTrashRequest(resolver, uris, true)
}
