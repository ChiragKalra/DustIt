package com.bruhascended.dustit.debug

import android.app.Activity
import android.os.Bundle
import android.os.SystemClock
import android.provider.DocumentsContract
import android.util.Log
import com.bruhascended.dustit.appContainer
import com.bruhascended.dustit.whatsapp.HiddenTrash
import com.bruhascended.dustit.whatsapp.WhatsAppLayout
import com.bruhascended.dustit.whatsapp.hiddenMediaId
import kotlinx.coroutines.launch

private const val TAG = "DustItProbe"
private const val PREFIX = "DUSTIT-TEST-"

/**
 * Debug builds only. Tests a big batch of WhatsApp file changes the way a user starts one: from
 * the foreground, after which the user leaves the app. Touches only its own `DUSTIT-TEST-*` files
 * in WhatsApp's Sent folder, so it is safe on a phone with real data. Needs WhatsApp's folders
 * connected in the app.
 *
 *   adb shell am start -n com.bruhascended.dustit/.debug.HiddenFilesProbe --es action create --ei count 600
 *   (or --es action index, if the files exist but aren't indexed yet)
 *   adb shell am start -n com.bruhascended.dustit/.debug.HiddenFilesProbe --es action trash --ei count 600
 *   adb shell input keyevent KEYCODE_HOME     # leave at once; the batch should keep going
 *   adb shell am start -n com.bruhascended.dustit/.debug.HiddenFilesProbe --es action cleanup
 *   adb logcat -s DustItProbe:I DustIt:I
 */
class HiddenFilesProbe : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val action = intent.getStringExtra("action")
        val count = intent.getIntExtra("count", 600)
        val container = applicationContext.appContainer
        val resolver = contentResolver
        val work = container.scope.launch {
            val tree = container.whatsApp.currentTree() ?: run {
                Log.w(TAG, "WhatsApp's folders aren't connected")
                return@launch
            }
            val app = WhatsAppLayout.appsUnder(DocumentsContract.getTreeDocumentId(tree)).first()
            val sentId = "${app.mediaRootDocumentId}/WhatsApp Images/Sent"
            val start = SystemClock.elapsedRealtime()
            when (action) {
                "create" -> {
                    val sent = DocumentsContract.buildDocumentUriUsingTree(tree, sentId)
                    val bytes = ByteArray(20_000) { (it * 31).toByte() }
                    repeat(count) { i ->
                        val uri = DocumentsContract.createDocument(resolver, sent, "image/jpeg", "$PREFIX$i.jpg")
                        uri?.let { resolver.openOutputStream(it)?.use { out -> out.write(bytes) } }
                    }
                    container.mediaRepository.syncHiddenFolders(tree)
                    Log.i(TAG, "created and indexed $count files in ${SystemClock.elapsedRealtime() - start} ms")
                }
                "index" -> {
                    container.mediaRepository.syncHiddenFolders(tree)
                    Log.i(TAG, "indexed in ${SystemClock.elapsedRealtime() - start} ms")
                }
                "trash" -> {
                    val ids = List(count) { hiddenMediaId("$sentId/$PREFIX$it.jpg") }
                    Log.i(TAG, "trash: starting $count")
                    val moved = container.mediaRepository.trashHidden(ids) { done ->
                        if (done % 100 == 0) Log.i(TAG, "trash: $done done after ${SystemClock.elapsedRealtime() - start} ms")
                    }
                    Log.i(TAG, "trash: moved ${moved.size} in ${SystemClock.elapsedRealtime() - start} ms")
                }
                "cleanup" -> {
                    // Deletes only this probe's files, under their own or their trashed name.
                    val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, sentId)
                    val mine = ArrayList<String>()
                    resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
                        while (c.moveToNext()) {
                            val name = c.getString(1) ?: continue
                            val original = HiddenTrash.parse(name)?.second ?: name
                            if (original.startsWith(PREFIX)) mine += c.getString(0)
                        }
                    }
                    val deleted = mine.count { runCatching { DocumentsContract.deleteDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, it)) }.getOrDefault(false) }
                    container.mediaRepository.syncHiddenFolders(tree)
                    Log.i(TAG, "cleanup: deleted $deleted of ${mine.size} probe files in ${SystemClock.elapsedRealtime() - start} ms")
                }
                else -> Log.w(TAG, "unknown action $action")
            }
        }
        if (action == "trash") {
            // Stay visible briefly, as the review screen is when the user taps "Move to trash"
            // (Android only lets a visible app start a foreground service), then leave.
            window.decorView.postDelayed(::finish, 1_500)
        } else {
            // Setup and cleanup stay on screen until done: in the background OxygenOS would freeze them.
            work.invokeOnCompletion { runOnUiThread(::finish) }
        }
    }
}
