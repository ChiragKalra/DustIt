package com.bruhascended.dustit.whatsapp

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import com.bruhascended.dustit.data.HiddenFolderEntity
import com.bruhascended.dustit.data.MediaEntity
import com.bruhascended.dustit.data.MediaKind
import com.bruhascended.dustit.data.TrashedFileEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.io.IOException

/** Video Notes nests one folder per week; nothing WhatsApp writes goes deeper than that. */
private const val MAX_DEPTH = 3

/** Enough for the smaller folders to finish while the biggest one lists. */
private const val PARALLEL = 4

private val listing = Dispatchers.IO.limitedParallelism(PARALLEL)

private val FOLDER_PROJECTION = arrayOf(Document.COLUMN_LAST_MODIFIED, Document.COLUMN_MIME_TYPE)

private val PROJECTION = arrayOf(
    Document.COLUMN_DOCUMENT_ID,
    Document.COLUMN_DISPLAY_NAME,
    Document.COLUMN_MIME_TYPE,
    Document.COLUMN_SIZE,
    Document.COLUMN_LAST_MODIFIED,
)

/**
 * Lists and changes files in WhatsApp's hidden folders through a folder the user granted with
 * the system picker. Only the known hidden folders are listed: the visible ones next to them
 * (thousands of received photos) are already in MediaStore and would only slow every scan down.
 */
class HiddenFolderSource(private val resolver: ContentResolver) {

    class Listing(
        val media: List<MediaEntity>,
        val trash: List<TrashedFileEntity>,
        /** Every folder visited, stamped with its current modification time. */
        val folders: List<HiddenFolderEntity>,
        /** Folders that haven't changed since [folders] were last stamped; their files weren't listed. */
        val unchanged: Set<String>,
    )

    /**
     * Lists the hidden folders under [tree], or returns null if WhatsApp's media folder can't be
     * reached at all. Folders whose stamp in [previous] still matches are not listed again (their
     * subfolders are still checked): listing the 13,000-file Sent folder took about 20 s on the
     * test phone, a stamp check about 13 ms.
     *
     * The top-level folders are walked [PARALLEL] at a time, biggest first (Sent photos), so the
     * smaller ones finish while it lists. One after another, a day of WhatsApp use made the first
     * app open spend 41–49 s here on the test phone.
     */
    suspend fun list(tree: Uri, previous: List<HiddenFolderEntity>): Listing? = coroutineScope {
        val known = previous.associateBy { it.relativePath }
        val apps = WhatsAppLayout.appsUnder(DocumentsContract.getTreeDocumentId(tree))
            .filter { Walk(tree, known).exists(it.mediaRootDocumentId) }
        // Returning nothing would drop every indexed file (and its analysis), so an unreachable
        // media folder, or a folder that is there but can't be listed, is a failed listing and
        // not an empty one.
        if (apps.isEmpty()) return@coroutineScope null
        val walks = apps
            .flatMap { app -> app.hiddenFolders.map { (folder, _) -> "${app.mediaRootDocumentId}/$folder" to "${app.mediaRoot}/$folder/" } }
            .map { (documentId, relativePath) ->
                async(listing) {
                    try {
                        Walk(tree, known).apply { visit(documentId, relativePath, depth = 0) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                }
            }
            .awaitAll()
        if (walks.any { it == null }) return@coroutineScope null
        Listing(
            media = walks.flatMap { it!!.media },
            trash = walks.flatMap { it!!.trash },
            folders = walks.flatMap { it!!.folders },
            unchanged = walks.flatMapTo(HashSet()) { it!!.unchanged },
        )
    }

    private class Subfolder(val documentId: String, val relativePath: String, val modified: Long)

    private inner class Walk(private val tree: Uri, private val known: Map<String, HiddenFolderEntity>) {
        val media = ArrayList<MediaEntity>()
        val trash = ArrayList<TrashedFileEntity>()
        val folders = ArrayList<HiddenFolderEntity>()
        val unchanged = HashSet<String>()

        /** [modified] is read before the folder is listed: anything that changes meanwhile makes the next scan look again. */
        fun visit(documentId: String, relativePath: String, depth: Int, modified: Long? = folderModified(documentId)) {
            modified ?: return // not on this phone
            folders += HiddenFolderEntity(relativePath, documentId, tree.toString(), modified)
            val before = known[relativePath]
            val same = before != null && before.documentId == documentId && before.modified == modified
            if (same) unchanged += relativePath
            val subfolders = when {
                !same -> list(documentId, relativePath, files = true)
                // Files can still have changed inside its subfolders, and one listing gives all of
                // their stamps. Video Notes has a folder per week: asking about each of 111 took
                // 1.4 s of every scan on the test phone.
                known.values.any { isDirectChild(relativePath, it.relativePath) } -> list(documentId, relativePath, files = false)
                else -> emptyList()
            }
            if (depth < MAX_DEPTH) subfolders.forEach { visit(it.documentId, it.relativePath, depth + 1, it.modified) }
        }

        fun exists(documentId: String): Boolean = folderModified(documentId) != null

        private fun folderModified(documentId: String): Long? {
            val uri = DocumentsContract.buildDocumentUriUsingTree(tree, documentId)
            return try {
                resolver.query(uri, FOLDER_PROJECTION, null, null, null)?.use { c ->
                    if (c.moveToFirst() && c.getString(1) == Document.MIME_TYPE_DIR) c.getLong(0) else null
                }
            } catch (_: Exception) {
                null
            }
        }

        /** Returns the folder's subfolders, adding its files to [media] and [trash] if [files] is set. */
        private fun list(documentId: String, relativePath: String, files: Boolean): List<Subfolder> {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, documentId)
            val cursor = resolver.query(children, PROJECTION, null, null, null)
                ?: throw IOException("Couldn't list $relativePath")
            val subfolders = ArrayList<Subfolder>()
            cursor.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(2).orEmpty()
                    val size = c.getLong(3)
                    val modified = c.getLong(4)
                    val trashed = HiddenTrash.parse(name)
                    when {
                        mime == Document.MIME_TYPE_DIR -> subfolders += Subfolder(id, "$relativePath$name/", modified)
                        !files -> Unit
                        trashed != null -> trash += TrashedFileEntity(
                            uri = DocumentsContract.buildDocumentUriUsingTree(tree, id).toString(),
                            originalName = trashed.second,
                            relativePath = relativePath,
                            size = size,
                            trashedAt = trashed.first,
                        )
                        name.startsWith(".") -> Unit // .nomedia and other bookkeeping
                        else -> {
                            val kind = when {
                                mime.startsWith("image/") -> MediaKind.Image
                                mime.startsWith("video/") -> MediaKind.Video
                                else -> null
                            } ?: continue
                            media += MediaEntity(
                                id = hiddenMediaId(id),
                                kind = kind,
                                displayName = name,
                                relativePath = relativePath,
                                mimeType = mime,
                                size = size,
                                width = 0,
                                height = 0,
                                durationMs = 0,
                                dateTaken = WhatsAppLayout.dateOf(name, modified),
                                dateModified = modified / 1000,
                                documentUri = DocumentsContract.buildDocumentUriUsingTree(tree, id).toString(),
                            )
                        }
                    }
                }
            }
            return subfolders
        }
    }

    /** Renames a file in place; returns its new URI, or null if it failed (gone, or access was revoked). */
    fun rename(uri: Uri, newName: String): Uri? = try {
        DocumentsContract.renameDocument(resolver, uri, newName)
    } catch (_: Exception) {
        null
    }

    fun delete(uri: Uri): Boolean = try {
        DocumentsContract.deleteDocument(resolver, uri)
    } catch (_: Exception) {
        false
    }
}

/** Whether [child] is a folder directly inside [parent] (both relative paths ending in '/'). */
internal fun isDirectChild(parent: String, child: String): Boolean {
    if (!child.startsWith(parent) || child.length == parent.length) return false
    return child.substring(parent.length).trimEnd('/').let { it.isNotEmpty() && '/' !in it }
}
