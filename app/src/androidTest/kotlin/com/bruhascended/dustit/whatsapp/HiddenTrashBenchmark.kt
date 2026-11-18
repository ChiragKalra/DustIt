package com.bruhascended.dustit.whatsapp

import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

private const val TAG = "DustItBench"

/**
 * How long DustIt's trash takes per file. Uses the folder the app was granted (connect WhatsApp's
 * folders in the app first) and works on its own files, created in the real Sent folder because
 * the cost may depend on how full it is. Everything it creates is deleted again.
 *
 * Run: am instrument -w -e class com.bruhascended.dustit.whatsapp.HiddenTrashBenchmark \
 *        com.bruhascended.dustit.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class HiddenTrashBenchmark {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver = context.contentResolver

    @Test
    fun renameAndDeleteCost() = runBlocking {
        val tree = resolver.persistedUriPermissions.firstOrNull { it.isWritePermission }?.uri
        assumeTrue("Connect WhatsApp's folders in the app first", tree != null)
        tree!!
        val app = WhatsAppLayout.appsUnder(DocumentsContract.getTreeDocumentId(tree)).first()
        val sent = DocumentsContract.buildDocumentUriUsingTree(tree, "${app.mediaRootDocumentId}/WhatsApp Images/Sent")
        val count = 200
        val bytes = ByteArray(40_000) { (it * 31).toByte() }

        var files = (0 until count).map { i ->
            val uri = DocumentsContract.createDocument(resolver, sent, "image/jpeg", "DUSTIT-BENCH-$i.jpg")!!
            resolver.openOutputStream(uri)!!.use { it.write(bytes) }
            uri to "DUSTIT-BENCH-$i.jpg"
        }
        try {
            val variants = listOf<Pair<String, (String) -> String>>(
                "prefix" to { name -> ".dustit-trash-1700000000000-$name" },
                "suffix" to { name -> "$name.dustit-trash-1700000000000" },
            )
            for ((label, trashName) in variants) {
                for (threads in listOf(1, 4, 8)) {
                    val (away, trashed) = timed(files.map { it.first to trashName(it.second) }, threads)
                    val (back, restored) = timed(trashed.zip(files) { t, f -> t to f.second }, threads)
                    files = restored.zip(files) { uri, f -> uri to f.second }
                    Log.i(TAG, "trash rename $label x$threads: ${away / count} ms/file away, ${back / count} ms/file back ($count files)")
                }
            }
        } finally {
            val start = SystemClock.elapsedRealtime()
            files.forEach { runCatching { DocumentsContract.deleteDocument(resolver, it.first) } }
            Log.i(TAG, "trash delete x1: ${(SystemClock.elapsedRealtime() - start) / count} ms/file")
        }
    }

    /** Renames each `(uri, newName)` with [threads] workers; returns total ms and the new URIs in order. */
    private suspend fun timed(renames: List<Pair<Uri, String>>, threads: Int): Pair<Long, List<Uri>> = coroutineScope {
        val dispatcher = Dispatchers.IO.limitedParallelism(threads)
        val start = SystemClock.elapsedRealtime()
        val result = renames.map { (uri, name) ->
            async(dispatcher) { DocumentsContract.renameDocument(resolver, uri, name)!! }
        }.awaitAll()
        (SystemClock.elapsedRealtime() - start) to result
    }
}
