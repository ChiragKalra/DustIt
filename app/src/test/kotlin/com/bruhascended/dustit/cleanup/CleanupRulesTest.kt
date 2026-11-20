package com.bruhascended.dustit.cleanup

import com.bruhascended.dustit.data.MediaKind
import com.bruhascended.dustit.data.MediaRow
import com.bruhascended.dustit.ml.JunkCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CleanupRulesTest {
    private val now = 1_000L * 24 * 60 * 60 * 1000
    private val day = 24L * 60 * 60 * 1000
    private val mb = 1024L * 1024

    private fun row(
        id: Long,
        kind: MediaKind = MediaKind.Image,
        size: Long = 1 * mb,
        path: String = "Pictures/Saved/",
        age: Long = 0,
        category: JunkCategory? = null,
        score: Float? = null,
        width: Int = 1000,
        height: Int = 1000,
        sharpness: Float = 0f,
        group: Long? = null,
    ) = MediaRow(id, kind, size, path, now - age, width, height, durationMs = 0, category, score, sharpness, group)

    private fun ids(sections: List<ReviewSection>) = sections.map { s -> s.kind to s.items.map { it.id } }

    @Test
    fun `classifier buckets split likely from maybe and drop low scores`() {
        val rows = listOf(
            row(1, category = JunkCategory.Chats, score = 0.95f),
            row(2, category = JunkCategory.Chats, score = 0.7f),
            row(3, category = JunkCategory.Chats, score = 0.5f),
            row(4, category = JunkCategory.Memes, score = 0.99f),
        )
        val sections = CleanupRules.sections(Bucket.Category(JunkCategory.Chats), rows, now)
        assertEquals(listOf(SectionKind.Likely to listOf(1L), SectionKind.Maybe to listOf(2L)), ids(sections))
        assertTrue(sections[0].items.single().preselected)
        assertFalse(sections[1].items.single().preselected)
    }

    @Test
    fun `camera photos are never pre-selected as chats, memes or forwards`() {
        // Regression from a real library: the model called 2.9% of camera photos memes at 0.9+.
        val rows = listOf(
            row(1, path = "DCIM/Camera/", category = JunkCategory.Memes, score = 0.97f),
            row(2, path = "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/", category = JunkCategory.Memes, score = 0.97f),
            row(3, path = "DCIM/Screenshots/", category = JunkCategory.Memes, score = 0.97f),
        )
        val memes = CleanupRules.sections(Bucket.Category(JunkCategory.Memes), rows, now)
        assertEquals(listOf(SectionKind.Likely to listOf(2L, 3L), SectionKind.Maybe to listOf(1L)), ids(memes).map { (k, v) -> k to v.sorted() })

        // Documents and blurry shots do come from the camera, so they keep their normal treatment.
        val docs = CleanupRules.sections(
            Bucket.Category(JunkCategory.Documents),
            listOf(row(4, path = "DCIM/Camera/", category = JunkCategory.Documents, score = 0.97f)),
            now,
        )
        assertEquals(listOf(SectionKind.Likely to listOf(4L)), ids(docs))
    }

    @Test
    fun `old screenshots are suggested and recent ones are optional`() {
        val rows = listOf(
            row(1, path = "Pictures/Screenshots/", age = 40 * day),
            row(2, path = "DCIM/Screenshots/", age = 2 * day),
            row(3, path = "Pictures/Saved/", age = 40 * day),
        )
        val sections = CleanupRules.sections(Bucket.Screenshots, rows, now)
        assertEquals(listOf(SectionKind.OldScreenshots to listOf(1L), SectionKind.RecentScreenshots to listOf(2L)), ids(sections))
    }

    @Test
    fun `large videos are biggest first and never pre-selected`() {
        val rows = listOf(
            row(1, kind = MediaKind.Video, size = 60 * mb),
            row(2, kind = MediaKind.Video, size = 900 * mb),
            row(3, kind = MediaKind.Video, size = 10 * mb),
            row(4, kind = MediaKind.Image, size = 80 * mb),
        )
        val sections = CleanupRules.sections(Bucket.LargeVideos, rows, now)
        assertEquals(listOf(SectionKind.LargeVideos to listOf(2L, 1L)), ids(sections))
        assertTrue(sections.single().items.none { it.preselected })
    }

    @Test
    fun `WhatsApp videos are received videos of any size, biggest first, none selected`() {
        val media = "Android/media/com.whatsapp/WhatsApp/Media"
        val rows = listOf(
            row(1, kind = MediaKind.Video, size = 3 * mb, path = "$media/WhatsApp Video/"),
            row(2, kind = MediaKind.Video, size = 70 * mb, path = "$media/WhatsApp Video/"),
            row(3, kind = MediaKind.Video, size = 9 * mb, path = "Android/media/com.whatsapp.w4b/WhatsApp Business/Media/WhatsApp Business Video/"),
            // Not received: sent copies and video messages live in hidden folders with their own groups.
            row(4, kind = MediaKind.Video, size = 20 * mb, path = "$media/WhatsApp Video/Sent/"),
            row(5, kind = MediaKind.Video, size = 20 * mb, path = "$media/WhatsApp Video Notes/202545/"),
            // Not videos, or not from WhatsApp.
            row(6, kind = MediaKind.Image, size = 5 * mb, path = "$media/WhatsApp Images/"),
            row(7, kind = MediaKind.Video, size = 40 * mb, path = "DCIM/Camera/"),
        )
        val sections = CleanupRules.sections(Bucket.WhatsAppVideos, rows, now)
        assertEquals(listOf(SectionKind.Videos to listOf(2L, 3L, 1L)), ids(sections))
        assertTrue(sections.single().items.none { it.preselected })
    }

    @Test
    fun `similar sets keep the largest then sharpest and suggest the rest`() {
        val rows = listOf(
            row(1, group = 1, width = 1000, height = 1000, sharpness = 50f),
            row(2, group = 1, width = 1000, height = 1000, sharpness = 90f),
            row(3, group = 1, width = 500, height = 500, sharpness = 500f),
            row(4, group = 4),
        )
        val sets = CleanupRules.sections(Bucket.Similar, rows, now)
        assertEquals("singleton sets are dropped", 1, sets.size)
        val items = sets.single().items
        assertEquals(2L, items.first().id)
        assertTrue(items.first().isBest)
        assertFalse(items.first().preselected)
        assertTrue(items.drop(1).all { it.preselected && !it.isBest })
    }

    @Test
    fun `overview counts an item once even when it is in two buckets`() {
        val chatScreenshot = row(1, path = "Pictures/Screenshots/", age = 40 * day, size = 3 * mb, category = JunkCategory.Chats, score = 0.97f)
        val meme = row(2, size = 1 * mb, category = JunkCategory.Memes, score = 0.95f)
        val overview = CleanupRules.overview(listOf(chatScreenshot, meme), now)
        assertEquals(4 * mb, overview.reclaimableBytes)
        assertEquals(
            listOf(Bucket.Category(JunkCategory.Chats), Bucket.Screenshots, Bucket.Category(JunkCategory.Memes)),
            overview.summaries.map { it.bucket },
        )
    }

    @Test
    fun `summaries lead with optional items when nothing is suggested`() {
        val overview = CleanupRules.overview(listOf(row(1, kind = MediaKind.Video, size = 200 * mb)), now)
        val summary = overview.summaries.single()
        assertEquals(Bucket.LargeVideos, summary.bucket)
        assertEquals(1, summary.headlineCount)
        assertEquals(200 * mb, summary.headlineBytes)
        assertEquals("optional items are not counted as clutter", 0L, overview.reclaimableBytes)
    }

    private val sent = "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/Sent/"
    private val sentVideo = "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Video/Sent/"
    private val private = "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/Private/"
    private val stickers = "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Stickers/"

    @Test
    fun `sent copies are suggested only while the original is still in the gallery`() {
        val rows = listOf(
            // Camera original and WhatsApp's copy of it (no dimensions on record, so the original is kept).
            row(1, path = "DCIM/Camera/", group = 1, width = 4000, height = 3000),
            row(2, path = sent, group = 1, width = 0, height = 0, age = 1 * day),
            // Sent twice, original deleted: the copies are a set of their own, and neither is "in your gallery".
            row(3, path = sent, group = 3, width = 0, height = 0, age = 2 * day),
            row(4, path = sent, group = 3, width = 0, height = 0, age = 3 * day),
            // Sent photo that matches nothing, and a sent video.
            row(5, path = sent, age = 4 * day),
            row(6, path = sentVideo, kind = MediaKind.Video, size = 30 * mb),
        )
        val sections = CleanupRules.sections(Bucket.WhatsAppSent, rows, now)
        assertEquals(
            listOf(SectionKind.SentCopies to listOf(2L), SectionKind.Photos to listOf(3L, 4L, 5L), SectionKind.Videos to listOf(6L)),
            ids(sections),
        )
        assertTrue(sections[0].items.all { it.preselected })
        assertTrue(sections.drop(1).flatMap { it.items }.none { it.preselected })
    }

    @Test
    fun `other hidden folders are offered but never pre-selected`() {
        val rows = listOf(
            row(1, path = private, age = 2 * day),
            row(2, path = private, age = 1 * day),
            row(3, path = private.replace("Images", "Video"), kind = MediaKind.Video, size = 5 * mb),
            row(4, path = stickers),
            row(5, path = "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Video Notes/202545/", kind = MediaKind.Video),
            row(6, path = "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/"), // visible: in no WhatsApp bucket
        )
        assertEquals(
            listOf(SectionKind.Photos to listOf(2L, 1L), SectionKind.Videos to listOf(3L)),
            ids(CleanupRules.sections(Bucket.WhatsAppPrivate, rows, now)),
        )
        assertEquals(listOf(SectionKind.Stickers to listOf(4L)), ids(CleanupRules.sections(Bucket.WhatsAppStickers, rows, now)))
        assertEquals(listOf(SectionKind.Videos to listOf(5L)), ids(CleanupRules.sections(Bucket.WhatsAppVideoNotes, rows, now)))
        val overview = CleanupRules.overview(rows, now)
        assertEquals(0L, overview.reclaimableBytes)
        assertTrue(Bucket.whatsApp.flatMap { CleanupRules.sections(it, rows, now) }.flatMap { it.items }.none { it.id == 6L })
    }
}
