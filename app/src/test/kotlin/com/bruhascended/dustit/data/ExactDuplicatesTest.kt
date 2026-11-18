package com.bruhascended.dustit.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ExactDuplicatesTest {

    private fun media(id: Long, size: Long, kind: MediaKind = MediaKind.Image) = MediaEntity(
        id = id, kind = kind, displayName = "$id", relativePath = "", mimeType = "", size = size,
        width = 0, height = 0, durationMs = 0, dateTaken = 0, dateModified = 0,
    )

    @Test
    fun `only same-size files are read and equal fingerprints group`() {
        val read = mutableListOf<Long>()
        val contents = mapOf(1L to "a", 2L to "a", 3L to "b", 4L to "z", 5L to "a")
        val groups = ExactDuplicates.groupBySameContent(
            listOf(media(1, 100), media(2, 100), media(3, 100), media(4, 999), media(5, 0)),
        ) { read += it.id; contents[it.id] }
        assertEquals(listOf(listOf(1L, 2L)), groups)
        assertEquals("unique sizes and empty files are never opened", listOf(1L, 2L, 3L), read.sorted())
    }

    @Test
    fun `unreadable files and different kinds are never grouped`() {
        val groups = ExactDuplicates.groupBySameContent(
            listOf(media(1, 100), media(2, 100, MediaKind.Video), media(3, 50), media(4, 50)),
        ) { if (it.id == 4L) null else "same" }
        assertEquals(emptyList<List<Long>>(), groups)
    }
}
