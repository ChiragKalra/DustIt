package com.bruhascended.dustit.ui.review

import com.bruhascended.dustit.cleanup.ReviewItem

/**
 * Records only what the user changed from each item's default, so items that stream in while
 * a scan is still running land in the right state without extra bookkeeping.
 */
data class ReviewSelection(
    /** Pre-selected items the user chose to keep. */
    val kept: Set<Long> = emptySet(),
    /** Not-pre-selected items the user chose to trash. */
    val picked: Set<Long> = emptySet(),
) {
    fun isSelected(item: ReviewItem): Boolean =
        if (item.preselected) item.id !in kept else item.id in picked

    fun toggle(item: ReviewItem): ReviewSelection = withAll(listOf(item), !isSelected(item))

    /** Sets every item in [items] to [selected], leaving everything else alone. */
    fun withAll(items: List<ReviewItem>, selected: Boolean): ReviewSelection {
        val (pre, optional) = items.partition { it.preselected }
        val preIds = pre.mapTo(HashSet()) { it.id }
        val optionalIds = optional.mapTo(HashSet()) { it.id }
        return copy(
            kept = if (selected) kept - preIds else kept + preIds,
            picked = if (selected) picked + optionalIds else picked - optionalIds,
        )
    }

    /** Forgets choices about items that no longer exist, keeping choices about everything else. */
    fun without(ids: Collection<Long>): ReviewSelection {
        val gone = ids.toSet()
        return copy(kept = kept - gone, picked = picked - gone)
    }
}
