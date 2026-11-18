package com.bruhascended.dustit.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SyncPlanTest {

    private fun media(id: Long, modified: Long = 100, size: Long = 1_000, path: String = "DCIM/") = MediaEntity(
        id = id,
        kind = MediaKind.Image,
        displayName = "IMG_$id.jpg",
        relativePath = path,
        mimeType = "image/jpeg",
        size = size,
        width = 100,
        height = 100,
        durationMs = 0,
        dateTaken = modified * 1000,
        dateModified = modified,
    )

    @Test
    fun `identical state needs no work`() {
        val rows = listOf(media(1), media(2))
        assertEquals(SyncPlan(emptyList(), emptyList(), emptyList()), planSync(rows, rows))
    }

    @Test
    fun `new images are inserted and keep no stale classification`() {
        val plan = planSync(local = listOf(media(1)), mediaStore = listOf(media(1), media(2)))
        assertEquals(listOf(media(2)), plan.upserts)
        assertEquals(emptyList<Long>(), plan.contentChangedIds)
        assertEquals(emptyList<Long>(), plan.removedIds)
    }

    @Test
    fun `images gone from MediaStore are removed`() {
        val plan = planSync(local = listOf(media(1), media(2)), mediaStore = listOf(media(2)))
        assertEquals(listOf(1L), plan.removedIds)
        assertEquals(emptyList<MediaEntity>(), plan.upserts)
    }

    @Test
    fun `edited images are updated and flagged for re-classification`() {
        val plan = planSync(
            local = listOf(media(1), media(2)),
            mediaStore = listOf(media(1, modified = 200), media(2, size = 2_000)),
        )
        assertEquals(listOf(media(1, modified = 200), media(2, size = 2_000)), plan.upserts)
        assertEquals(listOf(1L, 2L), plan.contentChangedIds)
    }

    @Test
    fun `moved images are updated but keep their classification`() {
        val plan = planSync(local = listOf(media(1)), mediaStore = listOf(media(1, path = "Pictures/")))
        assertEquals(listOf(media(1, path = "Pictures/")), plan.upserts)
        assertEquals(emptyList<Long>(), plan.contentChangedIds)
    }
}
