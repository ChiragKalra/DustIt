package com.bruhascended.dustit.whatsapp

import java.time.LocalDate
import java.time.ZoneId

/** The WhatsApp folders that hold a `.nomedia` file, so MediaStore (and every gallery) hides them. */
enum class HiddenFolder {
    /** WhatsApp's own copy of every photo, video and GIF you send. */
    Sent,

    /** Media received in chats whose "Media visibility" is off. */
    Private,

    Stickers,

    /** Round video messages, sent and received. */
    VideoNotes,
}

/**
 * Where WhatsApp keeps media that gallery apps can't see. Paths are relative to primary storage,
 * in MediaStore's `RELATIVE_PATH` style, so the same rules apply to rows from either source.
 */
object WhatsAppLayout {
    const val STORAGE_AUTHORITY = "com.android.externalstorage.documents"
    private const val PRIMARY_VOLUME = "primary"

    class App(val packageName: String, val mediaRoot: String, prefix: String) {
        /** Hidden folders under [mediaRoot], walked recursively. */
        val hiddenFolders: List<Pair<String, HiddenFolder>> = listOf(
            "$prefix Images/Sent" to HiddenFolder.Sent,
            "$prefix Video/Sent" to HiddenFolder.Sent,
            "$prefix Animated Gifs/Sent" to HiddenFolder.Sent,
            "$prefix Images/Private" to HiddenFolder.Private,
            "$prefix Video/Private" to HiddenFolder.Private,
            "$prefix Animated Gifs/Private" to HiddenFolder.Private,
            "$prefix Stickers" to HiddenFolder.Stickers,
            "$prefix Video Notes" to HiddenFolder.VideoNotes,
        )

        /** ExternalStorageProvider's id for [mediaRoot]; also used to open the folder picker there. */
        val mediaRootDocumentId: String get() = "$PRIMARY_VOLUME:$mediaRoot"
    }

    val apps: List<App> = listOf(
        App("com.whatsapp", "Android/media/com.whatsapp/WhatsApp/Media", "WhatsApp"),
        App("com.whatsapp.w4b", "Android/media/com.whatsapp.w4b/WhatsApp Business/Media", "WhatsApp Business"),
    )

    /** The hidden folder [relativePath] (ending in '/') is in, if any. */
    fun hiddenFolderOf(relativePath: String): HiddenFolder? {
        for (app in apps) {
            if (!relativePath.startsWith(app.mediaRoot + "/", ignoreCase = true)) continue
            val rest = relativePath.substring(app.mediaRoot.length + 1)
            for ((folder, kind) in app.hiddenFolders) {
                if (rest.startsWith("$folder/", ignoreCase = true)) return kind
            }
        }
        return null
    }

    /** Anything under a WhatsApp media folder, hidden or not. */
    fun isWhatsAppMedia(relativePath: String): Boolean =
        apps.any { relativePath.startsWith(it.mediaRoot + "/", ignoreCase = true) }

    /**
     * WhatsApp media folders reachable from a folder the user granted: the Media folder itself or
     * any folder above it. Empty means the user picked something else.
     */
    fun appsUnder(treeDocumentId: String): List<App> {
        val parts = treeDocumentId.split(':', limit = 2)
        if (parts.size != 2 || parts[0] != PRIMARY_VOLUME) return emptyList()
        val picked = parts[1].trim('/')
        return apps.filter { app ->
            app.mediaRoot.equals(picked, ignoreCase = true) ||
                (picked.isNotEmpty() && app.mediaRoot.startsWith("$picked/", ignoreCase = true))
        }
    }

    private val NAME_DATE = Regex("""^[A-Z]{3}-(\d{4})(\d{2})(\d{2})-WA\d+""")
    private const val DAY_MS = 24L * 60 * 60 * 1000

    /**
     * When a WhatsApp file was sent or received. Its name carries the date (`IMG-20250131-WA0004`);
     * the modification time adds the hour but is reset when a backup is restored, so it's only
     * trusted when it falls within a day of the date in the name.
     */
    fun dateOf(displayName: String, modifiedMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
        val match = NAME_DATE.find(displayName) ?: return modifiedMillis
        val (year, month, day) = match.destructured
        val named = runCatching {
            LocalDate.of(year.toInt(), month.toInt(), day.toInt()).atStartOfDay(zone).toInstant().toEpochMilli()
        }.getOrNull() ?: return modifiedMillis
        return if (modifiedMillis in named until named + 2 * DAY_MS) modifiedMillis else named
    }
}

/**
 * Stable media id for a file in a hidden folder. MediaStore ids are positive, so these are always
 * negative; derived from the document id (not the tree URI) so re-granting a different parent
 * folder keeps every file's analysis.
 */
fun hiddenMediaId(documentId: String): Long {
    var hash = -0x340d631b7bdddcdbL // FNV-1a 64-bit offset basis
    for (c in documentId) {
        hash = hash xor c.code.toLong()
        hash *= 0x100000001b3L
    }
    return -(hash and Long.MAX_VALUE) - 1
}

/**
 * DustIt's own trash for files in hidden folders, which the system trash can't hold (MediaStore
 * only trashes photos, videos and audio; it indexes these as plain files). A trashed file is
 * renamed in place with a suffix that changes its extension, so neither WhatsApp nor any gallery
 * opens it, and deleted for good after [KEEP_MS]. Everything needed to restore it lives in the
 * name, so nothing is lost if DustIt's database is reset.
 *
 * A suffix rather than a hiding dot-prefix, so the file reads as DustIt's in any file manager and
 * can be renamed back by hand. (Both cost the same: on the test phone `HiddenTrashBenchmark`
 * measured each rename at 42 ms with four in flight, almost all of it Android re-indexing the file.)
 */
object HiddenTrash {
    const val MARKER = ".dustit-trash-"
    const val KEEP_MS = 30L * 24 * 60 * 60 * 1000

    fun name(originalName: String, trashedAt: Long): String = "$originalName$MARKER$trashedAt"

    /** `(trashedAt, originalName)`, or null if [name] isn't a trashed file. */
    fun parse(name: String): Pair<Long, String>? {
        val marker = name.lastIndexOf(MARKER)
        if (marker <= 0) return null
        val trashedAt = name.substring(marker + MARKER.length).toLongOrNull() ?: return null
        return trashedAt to name.substring(0, marker)
    }
}
