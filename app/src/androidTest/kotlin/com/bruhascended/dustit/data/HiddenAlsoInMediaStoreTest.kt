package com.bruhascended.dustit.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** The rule background scans rely on instead of listing WhatsApp's folders. */
@RunWith(AndroidJUnit4::class)
class HiddenAlsoInMediaStoreTest {
    private val db = Room.inMemoryDatabaseBuilder(
        InstrumentationRegistry.getInstrumentation().targetContext,
        DustDatabase::class.java,
    ).build()
    private val dao = db.mediaDao()

    private val sent = "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/Sent/"

    private fun row(id: Long, path: String, name: String, hidden: Boolean) = MediaEntity(
        id = id,
        kind = MediaKind.Image,
        displayName = name,
        relativePath = path,
        mimeType = "image/jpeg",
        size = 1,
        width = 0,
        height = 0,
        durationMs = 0,
        dateTaken = 0,
        dateModified = 0,
        documentUri = if (hidden) "content://doc/$id" else null,
    )

    @After
    fun close() = db.close()

    @Test
    fun onlyHiddenRowsForAFileMediaStoreAlsoShows() = runBlocking {
        dao.applySync(
            SyncPlan(
                upserts = listOf(
                    row(-1, sent, "IMG-1.jpg", hidden = true), // MediaStore shows it too
                    row(1, sent, "IMG-1.jpg", hidden = false),
                    row(-2, sent, "IMG-2.jpg", hidden = true), // hidden only
                    row(2, "DCIM/Camera/", "IMG-2.jpg", hidden = false), // same name, other folder
                ),
                removedIds = emptyList(),
                contentChangedIds = emptyList(),
            ),
        )
        assertEquals(listOf(-1L), dao.hiddenAlsoInMediaStore())
        dao.forget(dao.hiddenAlsoInMediaStore())
        assertEquals(emptyList<Long>(), dao.hiddenAlsoInMediaStore())
        assertEquals(listOf(-2L), dao.hiddenMedia().map { it.id })
    }
}
