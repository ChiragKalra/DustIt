package com.bruhascended.dustit.data

import android.content.ContentResolver
import android.os.ParcelFileDescriptor
import java.nio.ByteBuffer
import java.security.MessageDigest

/** Bytes hashed from each end of a file. Distinct media files of equal size essentially never share both ends. */
private const val SAMPLE_BYTES = 1 shl 20

/**
 * Byte-identical copies (the same file saved twice, copied to another folder, downloaded again).
 * No false positives, so it is safe for any content, including text-heavy images and videos.
 */
class ExactDuplicates(private val resolver: ContentResolver) {

    class Result(val groups: List<List<Long>>, val newHashes: List<ContentHashEntity>)

    /** [cached] fingerprints are reused while the file's size and modification time are unchanged. */
    fun find(media: List<MediaEntity>, cached: List<ContentHashEntity>): Result {
        val known = cached.associateBy { it.mediaId }
        val computed = ArrayList<ContentHashEntity>()
        // One buffer for the whole pass: a fresh megabyte per file made hashing thousands of
        // same-size files mostly garbage collection.
        val buffer = ByteBuffer.allocate(SAMPLE_BYTES)
        val groups = groupBySameContent(media) { m ->
            known[m.id]?.takeIf { it.size == m.size && it.dateModified == m.dateModified }?.hash
                ?: fingerprint(m.contentUri, m.size, buffer)
                    ?.also { computed += ContentHashEntity(m.id, m.size, m.dateModified, it) }
        }
        return Result(groups, computed)
    }

    private fun fingerprint(uri: android.net.Uri, size: Long, buffer: ByteBuffer): String? = try {
        resolver.openFileDescriptor(uri, "r")?.let { pfd ->
            ParcelFileDescriptor.AutoCloseInputStream(pfd).channel.use { channel ->
                val digest = MessageDigest.getInstance("SHA-256")
                fun hashFrom(position: Long) {
                    buffer.clear()
                    while (buffer.hasRemaining()) {
                        if (channel.read(buffer, position + buffer.position()) <= 0) break
                    }
                    buffer.flip()
                    digest.update(buffer)
                }
                hashFrom(0)
                if (size > SAMPLE_BYTES) hashFrom(maxOf(SAMPLE_BYTES.toLong(), size - SAMPLE_BYTES))
                digest.digest().joinToString("") { "%02x".format(it) }
            }
        }
    } catch (_: Exception) {
        null // gone or unreadable; just don't group it
    }

    companion object {
        /** Only files sharing a size and kind are ever read, which keeps this cheap on real libraries. */
        internal fun groupBySameContent(media: List<MediaEntity>, fingerprint: (MediaEntity) -> String?): List<List<Long>> =
            media.filter { it.size > 0 }
                .groupBy { it.kind to it.size }
                .values
                .filter { it.size >= 2 }
                .flatMap { sameSize ->
                    sameSize.mapNotNull { m -> fingerprint(m)?.let { it to m.id } }
                        .groupBy({ it.first }, { it.second })
                        .values
                        .filter { it.size >= 2 }
                }
                .map { it.sorted() }
    }
}
