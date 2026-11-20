package com.bruhascended.dustit

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkManager
import com.bruhascended.dustit.data.MediaKind
import com.bruhascended.dustit.data.MediaStoreSource
import com.bruhascended.dustit.ml.FeatureExtractor
import com.bruhascended.dustit.ml.JunkClassifier
import com.bruhascended.dustit.ml.loadModelInput
import com.bruhascended.dustit.whatsapp.HiddenFolderSource
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Times each scan phase on this device's own library. Not a pass/fail test: read the
 * `DustItBench` log lines. Needs photo access already granted to the installed app.
 *
 *   adb shell am instrument -w -e class com.bruhascended.dustit.ScanBenchmark \
 *       com.bruhascended.dustit.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class ScanBenchmark {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val resolver = context.contentResolver

    private fun log(message: String) = Log.i("DustItBench", message)

    private inline fun perItemMs(count: Int, block: () -> Unit): Double {
        val start = SystemClock.elapsedRealtimeNanos()
        block()
        return (SystemClock.elapsedRealtimeNanos() - start) / 1e6 / count
    }

    @Test
    fun measureScanPhases() {
        val all = ArrayList<Long>()
        resolver.query(MediaStoreSource.images, arrayOf(MediaStore.MediaColumns._ID), null, null, null)?.use { c ->
            while (c.moveToNext()) all += c.getLong(0)
        }
        assumeTrue("needs a photo library", all.size >= SAMPLE)
        // Evenly spread across the library so camera shots, screenshots and chat media are all represented.
        val ids = List(SAMPLE) { all[it * all.size / SAMPLE] }
        val uris = ids.map { MediaStoreSource.uri(it, MediaKind.Image) }
        log("library=${all.size} sample=$SAMPLE cores=${Runtime.getRuntime().availableProcessors()}")

        log("open via content uri: %.2f ms/file".format(perItemMs(SAMPLE) { uris.forEach { resolver.openFileDescriptor(it, "r")?.close() } }))

        // Same files by path (through FUSE), to see how much of the cost is the provider round trip.
        val paths = ArrayList<String>()
        resolver.query(
            MediaStoreSource.images,
            arrayOf(MediaStore.MediaColumns.DATA),
            "${MediaStore.MediaColumns._ID} IN (${ids.joinToString(",")})",
            null,
            null,
        )?.use { c -> while (c.moveToNext()) c.getString(0)?.let(paths::add) }
        log("open via file path: %.2f ms/file".format(perItemMs(paths.size) { paths.forEach { java.io.FileInputStream(it).close() } }))

        // Codec cost alone: bytes already in memory.
        val bytes = paths.map { java.io.File(it).readBytes() }
        log("avg file size: ${bytes.sumOf { it.size.toLong() } / bytes.size / 1024} KB")
        val size = JunkClassifier.INPUT_SIZE
        var decodedPx = 0L
        log("decode from memory (sampled): %.2f ms/file".format(perItemMs(bytes.size) {
            bytes.forEach { b ->
                runCatching {
                    android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(java.nio.ByteBuffer.wrap(b))) { d, info, _ ->
                        d.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                        d.setTargetSampleSize(Integer.highestOneBit((minOf(info.size.width, info.size.height) / size).coerceAtLeast(1)))
                    }
                }.getOrNull()?.let { decodedPx += it.width.toLong() * it.height; it.recycle() }
            }
        }))
        log("avg decoded size: ${decodedPx / bytes.size / 1000} kpx")

        val inputs = ArrayList<Bitmap>(SAMPLE)
        log("open+decode+resize (scan path, 1 thread): %.2f ms/file".format(perItemMs(SAMPLE) { uris.forEach { u -> loadModelInput(resolver, u)?.let(inputs::add) } }))
        log("decoded ${inputs.size} of $SAMPLE")

        // The real scan decodes on several threads at once.
        for (workers in intArrayOf(4, 6, 8)) {
            val pool = java.util.concurrent.Executors.newFixedThreadPool(workers)
            val ms = perItemMs(SAMPLE) {
                uris.map { u -> pool.submit { loadModelInput(resolver, u)?.recycle() } }.forEach { it.get() }
            }
            pool.shutdown()
            log("scan-path decode, $workers threads: %.2f ms/file wall (%.0f files/s)".format(ms, 1000 / ms))
        }

        val pixels = inputs.map { b -> IntArray(size * size).also { b.getPixels(it, 0, size, 0, 0, size, size) } }
        log("features: %.2f ms/image".format(perItemMs(pixels.size) { pixels.forEach { FeatureExtractor.extract(it, size) } }))

        for (threads in intArrayOf(1, 2, 4)) {
            JunkClassifier(context, threads).use { classifier ->
                repeat(20) { classifier.classify(pixels[it % pixels.size]) } // warm-up
                val ms = perItemMs(pixels.size) { pixels.forEach { classifier.classify(it) } }
                log("classify (pack + inference) threads=$threads: %.2f ms/image".format(ms))
            }
        }
    }

    /**
     * Two whole scans of this device's library as the app runs them (the first picks up whatever
     * changed, the second has nothing to do), then one as the background watcher runs it. Read the
     * `DustIt` "scan:" log lines. Queued scans are cancelled first so none runs alongside.
     */
    @Test
    fun wholeScan(): Unit = runBlocking {
        WorkManager.getInstance(context).cancelUniqueWork("scan").result.get()
        val scanner = context.appContainer.scanner
        repeat(2) {
            val start = SystemClock.elapsedRealtime()
            scanner.run()
            log("whole scan ${it + 1}: ${SystemClock.elapsedRealtime() - start} ms")
        }
        val start = SystemClock.elapsedRealtime()
        scanner.run(background = true)
        log("background scan: ${SystemClock.elapsedRealtime() - start} ms")
    }

    /**
     * Lists every WhatsApp hidden folder from scratch, as if none had been seen before: the worst
     * case for an app open. One folder after another this took 52–62 s on the test phone.
     */
    @Test
    fun fullHiddenListing(): Unit = runBlocking {
        val tree = context.appContainer.whatsApp.currentTree()
        assumeTrue("Connect WhatsApp's folders in the app first", tree != null)
        repeat(2) {
            val start = SystemClock.elapsedRealtime()
            val listing = HiddenFolderSource(resolver).list(tree!!, emptyList())
            log("full hidden listing ${it + 1}: ${SystemClock.elapsedRealtime() - start} ms, ${listing?.media?.size} files in ${listing?.folders?.size} folders")
        }
    }

    /** What do raw files and very large JPEGs cost to decode, and what does ImageDecoder call them? */
    @Test
    fun diagnoseHeavyDecodes() {
        val cols = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.WIDTH, MediaStore.MediaColumns.HEIGHT)
        fun probe(label: String, where: String, limit: Int) {
            val rows = ArrayList<Triple<Long, String, Long>>()
            resolver.query(MediaStoreSource.images, cols, where, null, "${MediaStore.MediaColumns.WIDTH}*${MediaStore.MediaColumns.HEIGHT} DESC")?.use { c ->
                while (c.moveToNext() && rows.size < limit) rows += Triple(c.getLong(0), c.getString(1).orEmpty(), c.getLong(2) * c.getLong(3))
            }
            for ((id, storeMime, px) in rows) {
                var decoderMime = "?"
                var headerSize = "?"
                val before = android.os.Debug.getNativeHeapSize()
                val start = SystemClock.elapsedRealtimeNanos()
                val result = runCatching {
                    android.graphics.ImageDecoder.decodeBitmap(
                        android.graphics.ImageDecoder.createSource(resolver, MediaStoreSource.uri(id, MediaKind.Image)),
                    ) { d, info, _ ->
                        decoderMime = info.mimeType
                        headerSize = "${info.size.width}x${info.size.height}"
                        d.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                        d.setTargetSampleSize(Integer.highestOneBit((minOf(info.size.width, info.size.height) / 224).coerceAtLeast(1)))
                    }
                }
                val ms = (SystemClock.elapsedRealtimeNanos() - start) / 1e6
                val grew = (android.os.Debug.getNativeHeapSize() - before) / (1024 * 1024)
                val out = result.getOrNull()?.let { "${it.width}x${it.height}".also { _ -> it.recycle() } } ?: "FAILED ${result.exceptionOrNull()?.javaClass?.simpleName}"
                log("$label store=$storeMime (%.0f MP) decoder=$decoderMime header=$headerSize -> $out in %.0f ms, native heap grew $grew MB".format(px / 1e6, ms))
            }
        }
        probe("raw", "${MediaStore.MediaColumns.MIME_TYPE} LIKE 'image/x-%'", 4)
        probe("big", "${MediaStore.MediaColumns.MIME_TYPE} = 'image/jpeg'", 4)
    }

    private companion object {
        const val SAMPLE = 300
    }
}
