package com.bruhascended.dustit.ui.review

import com.bruhascended.dustit.cleanup.ReviewItem
import com.bruhascended.dustit.data.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewSelectionTest {

    private fun item(id: Long, preselected: Boolean) =
        ReviewItem(id, MediaKind.Image, sizeBytes = 10, durationMs = 0, dateTaken = 0, preselected = preselected)

    private val pre = item(1, preselected = true)
    private val pre2 = item(2, preselected = true)
    private val optional = item(3, preselected = false)

    @Test
    fun `items start in their default state`() {
        val selection = ReviewSelection()
        assertTrue(selection.isSelected(pre))
        assertFalse(selection.isSelected(optional))
    }

    @Test
    fun `toggle flips each kind of item and back`() {
        val toggled = ReviewSelection().toggle(pre).toggle(optional)
        assertFalse(toggled.isSelected(pre))
        assertTrue(toggled.isSelected(optional))
        assertEquals(ReviewSelection(), toggled.toggle(pre).toggle(optional))
    }

    @Test
    fun `items that arrive later get their default state`() {
        val selection = ReviewSelection().toggle(pre)
        assertTrue(selection.isSelected(item(99, preselected = true)))
    }

    @Test
    fun `trashing other items keeps the user's choices about the rest`() {
        val selection = ReviewSelection().toggle(pre).toggle(optional).without(listOf(pre2.id, optional.id))
        assertFalse("explicitly kept item must stay kept", selection.isSelected(pre))
        assertEquals(setOf(pre.id), selection.kept)
        assertEquals(emptySet<Long>(), selection.picked)
    }

    @Test
    fun `withAll only touches the given items`() {
        val items = listOf(pre, optional)
        val none = ReviewSelection().withAll(items, selected = false)
        assertFalse(none.isSelected(pre))
        assertFalse(none.isSelected(optional))
        assertTrue("items outside the list keep their default", none.isSelected(pre2))

        val all = none.withAll(items, selected = true)
        assertTrue(all.isSelected(pre))
        assertTrue(all.isSelected(optional))
    }

    @Test
    fun `range drags re-apply from the base so shrinking the range restores items`() {
        // What ReviewViewModel.updateRange does: always start from the selection before the drag.
        val ordered = listOf(pre, pre2, optional)
        val base = ReviewSelection()
        val wide = base.withAll(ordered.subList(0, 3), selected = false)
        val narrow = base.withAll(ordered.subList(0, 1), selected = false)
        assertFalse(wide.isSelected(pre2))
        assertTrue("dragging back must restore items that left the range", narrow.isSelected(pre2))
    }
}
