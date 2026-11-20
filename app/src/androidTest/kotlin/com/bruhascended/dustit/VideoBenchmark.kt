package com.bruhascended.dustit

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.media.MediaMetadataRetriever.BitmapParams
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkManager
import com.bruhascended.dustit.data.MediaEntity
import com.bruhascended.dustit.data.MediaKind
import com.bruhascended.dustit.data.MediaStoreSource
import com.bruhascended.dustit.data.contentUri
import com.bruhascended.dustit.ml.Confidence
import com.bruhascended.dustit.ml.FeatureExtractor
import com.bruhascended.dustit.ml.JunkCategory
import com.bruhascended.dustit.ml.JunkClassifier
import com.bruhascended.dustit.ml.Prediction
import com.bruhascended.dustit.whatsapp.HiddenFolder
import com.bruhascended.dustit.whatsapp.HiddenFolderSource
import com.bruhascended.dustit.whatsapp.WhatsAppLayout
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Whether video cleanup is feasible, measured on this device's own library. Not pass/fail: read
 * the `DustItBench` lines. Logs only counts, sizes and timings, never names or paths.
 *
 * 1. Does the photo classifier recognise video frames? Camera videos are the negatives: the camera
 *    can't record a chat, a meme or a forwarded greeting.
 * 2. What does reading frames cost?
 * 3. Can WhatsApp's Sent copies be matched to their originals by length and frames?
 *
 * Needs photo access and, for the hidden folders, WhatsApp's folders connected in the installed app.
 *
 *   adb shell am instrument -w -e class com.bruhascended.dustit.VideoBenchmark \
 *       com.bruhascended.dustit.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class VideoBenchmark {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val resolver = context.contentResolver
    private val params = BitmapParams().apply { preferredConfig = Bitmap.Config.ARGB_8888 }
    private val pixels = IntArray(SIZE * SIZE)

    private fun log(message: String) = Log.i("DustItBench", message)

    private enum class Source { Camera, OtherGallery, WhatsAppReceived, WhatsAppSent, WhatsAppHiddenOther }

    private fun sourceOf(m: MediaEntity): Source {
        val hidden = WhatsAppLayout.hiddenFolderOf(m.relativePath)
        return when {
            hidden == HiddenFolder.Sent -> Source.WhatsAppSent
            hidden != null -> Source.WhatsAppHiddenOther
            WhatsAppLayout.isWhatsAppMedia(m.relativePath) -> Source.WhatsAppReceived
            m.relativePath.startsWith("DCIM/Camera/", ignoreCase = true) -> Source.Camera
            else -> Source.OtherGallery
        }
    }

    /** Every video on the phone, gallery and hidden folders, by where it came from. */
    private suspend fun videosBySource(): Map<Source, List<MediaEntity>> {
        WorkManager.getInstance(context).cancelUniqueWork("scan").result.get()
        val visible = MediaStoreSource.queryAll(resolver).filter { it.kind == MediaKind.Video }
        val tree = context.appContainer.whatsApp.currentTree()
        val start = SystemClock.elapsedRealtime()
        val hidden = tree?.let { HiddenFolderSource(resolver).list(it, emptyList()) }?.media.orEmpty()
            .filter { it.kind == MediaKind.Video }
        log("video: hidden folders ${if (tree == null) "not connected" else "listed in ${SystemClock.elapsedRealtime() - start} ms"}")
        val bySource = (visible + hidden).groupBy(::sourceOf).toSortedMap()
        for ((source, items) in bySource) {
            log("video: $source ${items.size} files, %.2f GB".format(items.sumOf { it.size } / 1e9))
        }
        return bySource
    }

    @Test
    fun classifyFrames(): Unit = runBlocking {
        val bySource = videosBySource()
        JunkClassifier(context).use { classifier ->
            for ((source, items) in bySource) classify(classifier, source, items)
        }
    }

    @Test
    fun matchSentCopies(): Unit = runBlocking {
        val bySource = videosBySource()
        val sent = bySource[Source.WhatsAppSent].orEmpty()
        matchSent(sent, "gallery", (bySource[Source.Camera].orEmpty() + bySource[Source.OtherGallery].orEmpty()))
        matchSent(sent, "received", bySource[Source.WhatsAppReceived].orEmpty())
    }

    private class Scored(val media: MediaEntity, val middle: Prediction, val agreed: Prediction?)

    private fun classify(classifier: JunkClassifier, source: Source, items: List<MediaEntity>) {
        val sample = spread(items, if (source == Source.Camera) items.size else SAMPLE)
        val scored = ArrayList<Scored>()
        var failed = 0
        var openNs = 0L
        var frameNs = 0L
        var classifyNs = 0L
        var frames = 0
        var sideways = 0
        var sidewaysLeftUnrotated = 0
        for (m in sample) {
            val retriever = MediaMetadataRetriever()
            try {
                var t = SystemClock.elapsedRealtimeNanos()
                retriever.setDataSource(context, m.contentUri)
                val durationMs = retriever.number(MediaMetadataRetriever.METADATA_KEY_DURATION)
                val rotation = retriever.number(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                val width = retriever.number(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                val height = retriever.number(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                openNs += SystemClock.elapsedRealtimeNanos() - t
                val predictions = ArrayList<Prediction>()
                for (position in FRAME_POSITIONS) {
                    t = SystemClock.elapsedRealtimeNanos()
                    val frame = retriever.getScaledFrameAtTime(
                        (durationMs * 1000 * position).toLong(), MediaMetadataRetriever.OPTION_CLOSEST_SYNC, SIZE, SIZE, params,
                    ) ?: break
                    frameNs += SystemClock.elapsedRealtimeNanos() - t
                    frames++
                    if (position == 0.5 && rotation % 180 != 0L && width > 0 && height > 0) {
                        sideways++
                        if ((frame.width > frame.height) == (width > height)) sidewaysLeftUnrotated++
                    }
                    loadPixels(frame)
                    t = SystemClock.elapsedRealtimeNanos()
                    predictions += classifier.classify(pixels)
                    classifyNs += SystemClock.elapsedRealtimeNanos() - t
                }
                if (predictions.size != FRAME_POSITIONS.size) {
                    failed++
                    continue
                }
                val agreed = predictions.takeIf { p -> p.all { it.category == p[0].category } }?.minBy { it.score }
                scored += Scored(m, predictions[1], agreed)
            } catch (_: Exception) {
                failed++
            } finally {
                retriever.release()
            }
        }
        val ok = scored.size.coerceAtLeast(1)
        log(
            "video classify $source: sampled ${sample.size} of ${items.size}, failed $failed; " +
                "open %.1f ms, frame %.1f ms, classify %.1f ms; sideways $sideways (frame left unrotated: $sidewaysLeftUnrotated)".format(
                    openNs / 1e6 / ok, frameNs / 1e6 / frames.coerceAtLeast(1), classifyNs / 1e6 / frames.coerceAtLeast(1),
                ),
        )
        val sampleBytes = scored.sumOf { it.media.size }.coerceAtLeast(1)
        val totalBytes = items.sumOf { it.size }
        for ((rule, pick) in listOf<Pair<String, (Scored) -> Prediction?>>("all 3 frames agree" to { it.agreed }, "middle frame" to { it.middle })) {
            val parts = JunkCategory.clutter.mapNotNull { category ->
                val hits = scored.mapNotNull { s -> pick(s)?.takeIf { it.category == category }?.let { s to it.score } }
                if (hits.none { it.second >= Confidence.MAYBE }) return@mapNotNull null
                val counts = THRESHOLDS.joinToString("/") { th -> hits.count { it.second >= th }.toString() }
                val likelyBytes = hits.filter { it.second >= Confidence.LIKELY }.sumOf { it.first.media.size }
                "$category $counts (≥0.9 ≈ %.2f GB of all)".format(likelyBytes.toDouble() / sampleBytes * totalBytes / 1e9)
            }
            log("video classify $source [$rule] at ${THRESHOLDS.joinToString("/")}: ${parts.joinToString("; ").ifEmpty { "nothing ≥0.6" }}")
        }
    }

    /** First frame and the keyframe nearest the middle: both cheap, as neither decodes past a keyframe. */
    private class Frames(val first: ByteArray, val middle: ByteArray)

    private fun framesOf(retriever: MediaMetadataRetriever, durationMs: Long): Frames? {
        val first = tinyAt(retriever, 0) ?: return null
        val middle = tinyAt(retriever, durationMs * 1000 / 2) ?: return null
        return Frames(first, middle)
    }

    /**
     * For each Sent copy, looks among [originals] for videos of the same length (±0.5 s), then
     * compares their first frames and middle keyframes, in all four rotations in case one copy has
     * its rotation baked in. A pairing with a video of a clearly different length is scored the same
     * way, as a control. Byte-identical copies (same size) are counted apart: DustIt already offers
     * those as exact duplicates.
     */
    private fun matchSent(sent: List<MediaEntity>, label: String, candidates: List<MediaEntity>) {
        val originals = candidates.filter { it.durationMs > 0 }
        if (sent.isEmpty() || originals.isEmpty()) {
            log("video match $label: nothing to match (sent ${sent.size}, originals ${originals.size})")
            return
        }
        val sizes = originals.mapTo(HashSet()) { it.size }
        val frameCache = HashMap<Long, Frames?>()
        var originalNs = 0L
        fun originalFrames(o: MediaEntity): Frames? = frameCache.getOrPut(o.id) {
            val t = SystemClock.elapsedRealtimeNanos()
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, o.contentUri)
                framesOf(retriever, o.durationMs)
            } catch (_: Exception) {
                null
            } finally {
                retriever.release()
                originalNs += SystemClock.elapsedRealtimeNanos() - t
            }
        }
        val start = SystemClock.elapsedRealtime()
        var read = 0
        var openNs = 0L
        var sentFramesNs = 0L
        var sentFrames = 0
        var sameSize = 0
        var sameSizeBytes = 0L
        val nearest = IntArray(4) // ≤0.1 s, ≤0.5 s, ≤2 s, further
        var sameLength = 0
        var firstMatch = 0
        var middleMatch = 0
        var bothMatch = 0
        var bothBytes = 0L
        var rotatedMatches = 0
        var control = 0
        var controlFirst = 0
        var controlBoth = 0
        val matchedOriginals = HashSet<Long>()
        for ((index, s) in sent.withIndex()) {
            if (index > 0 && index % 500 == 0) log("video match $label: $index of ${sent.size} after ${(SystemClock.elapsedRealtime() - start) / 1000} s")
            if (s.size in sizes) {
                sameSize++
                sameSizeBytes += s.size
                continue
            }
            val retriever = MediaMetadataRetriever()
            try {
                var t = SystemClock.elapsedRealtimeNanos()
                retriever.setDataSource(context, s.contentUri)
                val durationMs = retriever.number(MediaMetadataRetriever.METADATA_KEY_DURATION)
                openNs += SystemClock.elapsedRealtimeNanos() - t
                read++
                if (durationMs <= 0) continue
                val gaps = originals.map { it to abs(it.durationMs - durationMs) }
                val closest = gaps.minOf { it.second }
                nearest[
                    when {
                        closest <= 100 -> 0
                        closest <= 500 -> 1
                        closest <= 2_000 -> 2
                        else -> 3
                    },
                ]++
                val alike = gaps.filter { it.second <= 500 }.map { it.first }
                if (alike.isEmpty()) continue
                sameLength++
                t = SystemClock.elapsedRealtimeNanos()
                val mine = framesOf(retriever, durationMs) ?: continue
                sentFramesNs += SystemClock.elapsedRealtimeNanos() - t
                sentFrames++
                var bestFirst = -1.0
                var bestMiddle = -1.0
                var bestBoth = -1.0
                var bestBothRotation = 0
                var bestOriginal: MediaEntity? = null
                for (o in alike) {
                    val theirs = originalFrames(o) ?: continue
                    val (first, rotation) = bestCorrelation(mine.first, theirs.first)
                    val middle = correlation(mine.middle, rotated(theirs.middle, rotation))
                    bestFirst = maxOf(bestFirst, first)
                    bestMiddle = maxOf(bestMiddle, middle)
                    if (minOf(first, middle) > bestBoth) {
                        bestBoth = minOf(first, middle)
                        bestBothRotation = rotation
                        bestOriginal = o
                    }
                }
                if (bestFirst >= MATCH) firstMatch++
                if (bestMiddle >= MATCH) middleMatch++
                if (bestBoth >= MATCH) {
                    bothMatch++
                    bothBytes += s.size
                    bestOriginal?.let { matchedOriginals += it.id }
                    if (bestBothRotation != 0) rotatedMatches++
                }
                gaps.filter { it.second > 5_000 }.takeIf { it.isNotEmpty() }?.let { far -> far[index % far.size].first }?.let { other ->
                    val theirs = originalFrames(other) ?: return@let
                    control++
                    val (first, rotation) = bestCorrelation(mine.first, theirs.first)
                    if (first >= MATCH) controlFirst++
                    if (first >= MATCH && correlation(mine.middle, rotated(theirs.middle, rotation)) >= MATCH) controlBoth++
                }
            } catch (_: Exception) {
                // unreadable; not counted
            } finally {
                retriever.release()
            }
        }
        log(
            "video match $label: ${sent.size} Sent copies vs ${originals.size} originals in ${(SystemClock.elapsedRealtime() - start) / 1000} s; " +
                "byte-identical (same size) $sameSize (%.2f GB); read $read at %.1f ms; nearest by length ≤0.1 s ${nearest[0]}, ≤0.5 s ${nearest[1]}, ≤2 s ${nearest[2]}, further ${nearest[3]}".format(
                    sameSizeBytes / 1e9, openNs / 1e6 / read.coerceAtLeast(1),
                ),
        )
        log(
            "video match $label: same length $sameLength; frames ≥$MATCH first $firstMatch, middle keyframe $middleMatch, both $bothMatch (%.2f GB, $rotatedMatches rotated, ${matchedOriginals.size} distinct originals); ".format(bothBytes / 1e9) +
                "control first $controlFirst / both $controlBoth of $control; frames %.0f ms per Sent copy, originals %.0f ms each (${frameCache.size} read)".format(
                    sentFramesNs / 1e6 / sentFrames.coerceAtLeast(1), originalNs / 1e6 / frameCache.size.coerceAtLeast(1),
                ),
        )
    }

    private fun tinyAt(retriever: MediaMetadataRetriever, atUs: Long): ByteArray? {
        val frame = retriever.getScaledFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, SIZE, SIZE, params) ?: return null
        loadPixels(frame)
        return FeatureExtractor.extract(pixels, SIZE).tiny
    }

    /** Stretches [frame] to the model's square, as photos are, into [pixels]; recycles it. */
    private fun loadPixels(frame: Bitmap) {
        val square = Bitmap.createScaledBitmap(frame, SIZE, SIZE, true)
        square.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
        if (square !== frame) square.recycle()
        frame.recycle()
    }

    /** Best Pearson correlation of two 16×16 grids over the four rotations of [b], and that rotation. */
    private fun bestCorrelation(a: ByteArray, b: ByteArray): Pair<Double, Int> {
        var grid = b
        var best = -1.0 to 0
        for (rotation in 0 until 4) {
            val c = correlation(a, grid)
            if (c > best.first) best = c to rotation
            grid = rotate(grid)
        }
        return best
    }

    private fun rotated(grid: ByteArray, times: Int): ByteArray {
        var out = grid
        repeat(times) { out = rotate(out) }
        return out
    }

    private fun rotate(grid: ByteArray): ByteArray {
        val n = FeatureExtractor.TINY_SIZE
        return ByteArray(n * n) { i -> grid[(n - 1 - i % n) * n + i / n] }
    }

    private fun correlation(a: ByteArray, b: ByteArray): Double {
        val x = DoubleArray(a.size) { (a[it].toInt() and 0xFF).toDouble() }
        val y = DoubleArray(b.size) { (b[it].toInt() and 0xFF).toDouble() }
        val mx = x.average()
        val my = y.average()
        var sxy = 0.0
        var sxx = 0.0
        var syy = 0.0
        for (i in x.indices) {
            sxy += (x[i] - mx) * (y[i] - my)
            sxx += (x[i] - mx) * (x[i] - mx)
            syy += (y[i] - my) * (y[i] - my)
        }
        return if (sxx < 1e-9 || syy < 1e-9) 0.0 else sxy / sqrt(sxx * syy)
    }

    private fun MediaMetadataRetriever.number(key: Int): Long = extractMetadata(key)?.toLongOrNull() ?: 0L

    /** Up to [count] items spread evenly across [items], the same ones every run. */
    private fun <T> spread(items: List<T>, count: Int): List<T> =
        if (items.size <= count) items else List(count) { items[it * items.size / count] }

    private companion object {
        const val SIZE = JunkClassifier.INPUT_SIZE
        const val SAMPLE = 300
        val FRAME_POSITIONS = listOf(0.15, 0.5, 0.85)
        val THRESHOLDS = listOf(0.6f, 0.9f, 0.95f, 0.99f)

        /** Same bar as near-identical photos in `SimilarityGrouper`. */
        const val MATCH = 0.95
    }
}
