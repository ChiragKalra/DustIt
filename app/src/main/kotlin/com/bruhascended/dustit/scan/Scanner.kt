package com.bruhascended.dustit.scan

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.bruhascended.dustit.data.AnalysisEntity
import com.bruhascended.dustit.data.MediaKind
import com.bruhascended.dustit.data.MediaRepository
import com.bruhascended.dustit.data.PendingImage
import com.bruhascended.dustit.data.contentUri
import com.bruhascended.dustit.ml.isRawPhoto
import com.bruhascended.dustit.ml.loadModelInput
import com.bruhascended.dustit.whatsapp.HiddenFolder
import com.bruhascended.dustit.whatsapp.WhatsAppAccess
import com.bruhascended.dustit.whatsapp.WhatsAppLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "DustIt"

/** Results saved per transaction. Each write also wakes every screen watching the index. */
private const val SAVE_BATCH = 64

/** Decoded images allowed to wait for the model; bounds memory at ~200 KB each. */
private const val DECODED_BUFFER = 48

/** Each decoder briefly holds a few MB of native buffers; low-RAM devices get fewer of them. */
private const val LOW_RAM_DECODERS = 2

/**
 * Syncs the local index with MediaStore (and WhatsApp's hidden folders, if the user shared them),
 * analyses every image that needs it, then regroups similar photos.
 */
class Scanner(
    private val context: Context,
    private val repository: MediaRepository,
    private val whatsApp: WhatsAppAccess,
) {
    /** Decoding is CPU-bound; leave a couple of cores for inference, the UI and the rest of the system. */
    private val decoders: Int =
        if (context.getSystemService(ActivityManager::class.java).isLowRamDevice) LOW_RAM_DECODERS
        else (Runtime.getRuntime().availableProcessors() - 2).coerceIn(2, 6)

    /** A scan from the app and one from the background watcher take turns. */
    private val oneAtATime = Mutex()

    /**
     * A [background] scan syncs and analyses the gallery only, because OxygenOS stops a background
     * job about 5 s in (11 s from a cold start). It leaves WhatsApp's hidden folders as last
     * listed (one changed Sent folder took 20 s to list on the test phone) and doesn't regroup
     * similar photos (2.5 to 3.2 s over the whole library there). Both happen on the next scan
     * from the app: analysis saved here marks the groups as outdated.
     *
     * [onProgress] is first called with `(0, total)` once the amount of work is known, and is
     * not called at all if there is nothing to analyse.
     */
    suspend fun run(
        background: Boolean = false,
        onProgress: suspend (done: Int, total: Int) -> Unit = { _, _ -> },
    ) = oneAtATime.withLock {
        val start = SystemClock.elapsedRealtime()
        val mediaStoreChanged = repository.syncWithMediaStore()
        val mediaStoreSynced = SystemClock.elapsedRealtime()
        val hiddenChanged = if (background) {
            repository.keepHiddenFoldersApart()
        } else {
            repository.syncHiddenFolders(whatsApp.currentTree())
        }
        val synced = SystemClock.elapsedRealtime()
        val pending = repository.imagesNeedingAnalysis()
        if (pending.isNotEmpty()) analyze(pending, onProgress)
        val analyzed = SystemClock.elapsedRealtime()
        // Needed after anything above saved a change, in this scan or in one that was cut short.
        val outdated = repository.isGroupingOutdated()
        val regroup = outdated && !background
        if (regroup) repository.regroupSimilar()
        val done = SystemClock.elapsedRealtime()
        Log.i(
            TAG,
            "scan: sync ${mediaStoreSynced - start} ms (changed=$mediaStoreChanged), " +
                "hidden folders ${synced - mediaStoreSynced} ms (changed=$hiddenChanged${if (background) ", not listed" else ""}), " +
                "analysed ${pending.size} in ${analyzed - synced} ms, " +
                "regroup ${if (regroup) "${done - analyzed} ms" else if (outdated) "left for the app" else "skipped"}",
        )
    }

    private class Decoded(val id: Long, val bitmap: Bitmap?)

    /**
     * Several decoders feed one inference loop through a bounded channel, so decoding the next
     * images overlaps with running the model on the current one. On a Nord 6 that measured
     * roughly four times faster than decoding and inferring a batch at a time.
     */
    private suspend fun analyze(pending: List<PendingImage>, onProgress: suspend (done: Int, total: Int) -> Unit) = coroutineScope {
        onProgress(0, pending.size)
        val resolver = context.contentResolver
        val next = AtomicInteger(0)
        val decoded = Channel<Decoded>(DECODED_BUFFER)

        val workers = List(decoders) {
            launch(Dispatchers.IO) {
                while (true) {
                    val image = pending.getOrNull(next.getAndIncrement()) ?: break
                    val bitmap = if (isNeverAnalysed(image)) {
                        null // recorded as "not analysed", which keeps it out of every classifier suggestion
                    } else {
                        loadModelInput(resolver, contentUri(image.id, MediaKind.Image, image.documentUri))
                    }
                    decoded.send(Decoded(image.id, bitmap))
                }
            }
        }
        launch {
            workers.joinAll()
            decoded.close()
        }

        ImageAnalyzer(context).use { analyzer ->
            val batch = ArrayList<AnalysisEntity>(SAVE_BATCH)
            var done = 0
            suspend fun flush() {
                repository.saveAnalysis(batch.toList())
                done += batch.size
                batch.clear()
                onProgress(done, pending.size)
            }
            for (item in decoded) {
                val bitmap = item.bitmap
                batch += if (bitmap == null) {
                    ImageAnalyzer.unreadable(item.id)
                } else {
                    try {
                        analyzer.analyze(item.id, bitmap)
                    } finally {
                        bitmap.recycle()
                    }
                }
                if (batch.size == SAVE_BATCH) flush()
            }
            if (batch.isNotEmpty()) flush()
        }
    }
}

/**
 * Raw photos are someone's deliberate originals (see [isRawPhoto]). Stickers are cut-out
 * drawings the model was never trained on; they have their own suggestion instead.
 */
private fun isNeverAnalysed(image: PendingImage): Boolean =
    isRawPhoto(image.mimeType) || WhatsAppLayout.hiddenFolderOf(image.relativePath) == HiddenFolder.Stickers
