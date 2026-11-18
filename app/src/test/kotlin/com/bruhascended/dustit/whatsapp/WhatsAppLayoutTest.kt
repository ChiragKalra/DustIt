package com.bruhascended.dustit.whatsapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

class WhatsAppLayoutTest {
    private val media = "Android/media/com.whatsapp/WhatsApp/Media"
    private val business = "Android/media/com.whatsapp.w4b/WhatsApp Business/Media"

    @Test
    fun `hidden folders are recognised for both apps, nested folders included`() {
        assertEquals(HiddenFolder.Sent, WhatsAppLayout.hiddenFolderOf("$media/WhatsApp Images/Sent/"))
        assertEquals(HiddenFolder.Sent, WhatsAppLayout.hiddenFolderOf("$media/WhatsApp Video/Sent/"))
        assertEquals(HiddenFolder.Sent, WhatsAppLayout.hiddenFolderOf("$media/WhatsApp Animated Gifs/Sent/"))
        assertEquals(HiddenFolder.Private, WhatsAppLayout.hiddenFolderOf("$media/WhatsApp Images/Private/"))
        assertEquals(HiddenFolder.Stickers, WhatsAppLayout.hiddenFolderOf("$media/WhatsApp Stickers/"))
        assertEquals(HiddenFolder.VideoNotes, WhatsAppLayout.hiddenFolderOf("$media/WhatsApp Video Notes/202545/"))
        assertEquals(HiddenFolder.Sent, WhatsAppLayout.hiddenFolderOf("$business/WhatsApp Business Images/Sent/"))
    }

    @Test
    fun `visible WhatsApp folders and look-alikes elsewhere are not hidden folders`() {
        assertNull(WhatsAppLayout.hiddenFolderOf("$media/WhatsApp Images/"))
        assertNull(WhatsAppLayout.hiddenFolderOf("$media/WhatsApp Video/"))
        assertNull(WhatsAppLayout.hiddenFolderOf("$media/WhatsApp Images/Sentimental/"))
        assertNull(WhatsAppLayout.hiddenFolderOf("Pictures/WhatsApp Images/Sent/"))
        assertNull(WhatsAppLayout.hiddenFolderOf("DCIM/Camera/"))
        assertTrue(WhatsAppLayout.isWhatsAppMedia("$media/WhatsApp Images/"))
        assertFalse(WhatsAppLayout.isWhatsAppMedia("DCIM/Camera/"))
    }

    @Test
    fun `granted folder must be the media folder or above it`() {
        assertEquals(listOf("com.whatsapp"), WhatsAppLayout.appsUnder("primary:$media").map { it.packageName })
        assertEquals(listOf("com.whatsapp"), WhatsAppLayout.appsUnder("primary:Android/media/com.whatsapp").map { it.packageName })
        assertEquals(2, WhatsAppLayout.appsUnder("primary:Android/media").size)
        // Too deep, the wrong app, or another volume.
        assertTrue(WhatsAppLayout.appsUnder("primary:$media/WhatsApp Images").isEmpty())
        assertTrue(WhatsAppLayout.appsUnder("primary:Android/media/org.telegram.messenger").isEmpty())
        assertTrue(WhatsAppLayout.appsUnder("1234-5678:$media").isEmpty())
        assertTrue(WhatsAppLayout.appsUnder("garbage").isEmpty())
    }

    @Test
    fun `date comes from the name unless the file time is on that day`() {
        val zone = ZoneOffset.UTC
        fun millis(y: Int, m: Int, d: Int, h: Int = 0) = LocalDateTime.of(y, m, d, h, 0).toInstant(zone).toEpochMilli()
        // Same day: keep the precise time.
        assertEquals(millis(2025, 1, 31, 14), WhatsAppLayout.dateOf("IMG-20250131-WA0004.jpg", millis(2025, 1, 31, 14), zone))
        // Restored from a backup a year later: the name wins.
        assertEquals(millis(2025, 1, 31), WhatsAppLayout.dateOf("VID-20250131-WA0001.mp4", millis(2026, 3, 2), zone))
        // No date in the name, or an impossible one.
        assertEquals(42L, WhatsAppLayout.dateOf("sticker.webp", 42L, zone))
        assertEquals(42L, WhatsAppLayout.dateOf("IMG-20251399-WA0001.jpg", 42L, zone))
    }

    @Test
    fun `hidden ids are negative, stable and distinct`() {
        val a = hiddenMediaId("primary:$media/WhatsApp Images/Sent/IMG-20250131-WA0004.jpg")
        val b = hiddenMediaId("primary:$media/WhatsApp Images/Sent/IMG-20250131-WA0005.jpg")
        assertTrue(a < 0 && b < 0)
        assertNotEquals(a, b)
        assertEquals(a, hiddenMediaId("primary:$media/WhatsApp Images/Sent/IMG-20250131-WA0004.jpg"))
        assertTrue((0 until 10_000).map { hiddenMediaId("primary:x/$it") }.all { it < 0 })
    }

    @Test
    fun `trash names round-trip, including names with dashes and dots`() {
        val name = HiddenTrash.name("IMG-20250131-WA0004.jpg", 1_731_000_000_000)
        assertFalse("no longer opens as an image", name.endsWith(".jpg"))
        assertEquals(1_731_000_000_000 to "IMG-20250131-WA0004.jpg", HiddenTrash.parse(name))
        assertEquals(5L to "a.dustit-trash-x.jpg", HiddenTrash.parse(HiddenTrash.name("a.dustit-trash-x.jpg", 5)))
        assertNull(HiddenTrash.parse(".nomedia"))
        assertNull(HiddenTrash.parse("IMG-20250131-WA0004.jpg"))
        assertNull(HiddenTrash.parse("x.jpg.dustit-trash-notanumber"))
        assertNull(HiddenTrash.parse(".dustit-trash-123"))
    }

    @Test
    fun `direct child folders`() {
        val notes = "$media/WhatsApp Video Notes/"
        assertTrue(isDirectChild(notes, "${notes}202545/"))
        assertFalse(isDirectChild(notes, "${notes}202545/deeper/"))
        assertFalse(isDirectChild(notes, notes))
        assertFalse(isDirectChild(notes, "$media/WhatsApp Video/"))
    }
}
