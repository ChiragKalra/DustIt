package com.bruhascended.dustit.ui.review

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import androidx.compose.ui.unit.toIntRect
import com.bruhascended.dustit.cleanup.ReviewItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** Callbacks for [gridSelectionGestures]. */
interface GridGestureHandler {
    fun onTap(item: ReviewItem)
    fun onPreview(item: ReviewItem)
    fun onRangeStart(anchor: ReviewItem)
    fun onRangeTo(anchor: ReviewItem, current: ReviewItem)
    fun onRangeEnd()
}

private class DragSession(val anchor: ReviewItem, var position: Offset) {
    var current: ReviewItem = anchor
    var moved = false
}

/**
 * All pointer handling for the review grid, in one place so gestures can't fight each other:
 * - tap: toggle the item
 * - long-press then drag: set every item between the anchor and the finger to the opposite of
 *   the anchor's state, auto-scrolling near the edges
 * - long-press then release without leaving the item: preview it
 *
 * Cells carry no pointer handlers of their own (only semantics actions for accessibility).
 */
@Composable
fun Modifier.gridSelectionGestures(
    gridState: LazyGridState,
    itemForKey: (Any) -> ReviewItem?,
    handler: GridGestureHandler,
): Modifier {
    val currentItemForKey by rememberUpdatedState(itemForKey)
    val currentHandler by rememberUpdatedState(handler)
    val haptics = LocalHapticFeedback.current
    val edgePx = with(LocalDensity.current) { 72.dp.toPx() }
    val sessionRef = remember { arrayOfNulls<DragSession>(1) }
    var scrollSpeed by remember { mutableFloatStateOf(0f) }

    fun itemAt(position: Offset): ReviewItem? = gridState.layoutInfo.visibleItemsInfo
        .firstOrNull { it.size.toIntRect().contains(position.round() - it.offset) }
        ?.key
        ?.let(currentItemForKey)

    fun dragTo(session: DragSession, position: Offset) {
        session.position = position
        val item = itemAt(position) ?: return
        if (item.id == session.current.id) return
        session.current = item
        session.moved = true
        currentHandler.onRangeTo(session.anchor, item)
    }

    // Keep scrolling while the finger rests near an edge, extending the range as rows pass under it.
    LaunchedEffect(scrollSpeed) {
        if (scrollSpeed == 0f) return@LaunchedEffect
        while (isActive) {
            gridState.scrollBy(scrollSpeed)
            sessionRef[0]?.let { dragTo(it, it.position) }
            delay(16)
        }
    }

    return pointerInput(gridState) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val anchor = itemAt(down.position) ?: return@awaitEachGesture
            val longPress = awaitLongPressOrCancellation(down.id)

            if (longPress == null) {
                // Lifted before the long-press timeout (a tap), or the grid claimed it as a scroll.
                val up = currentEvent.changes.firstOrNull { it.id == down.id }
                if (up != null && up.changedToUp() && !up.isConsumed) {
                    up.consume()
                    currentHandler.onTap(anchor)
                }
                return@awaitEachGesture
            }

            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            val session = DragSession(anchor, longPress.position)
            sessionRef[0] = session
            currentHandler.onRangeStart(anchor)
            var released = false
            try {
                while (true) {
                    // Initial pass: claim the events before the grid's own scroll handling sees them.
                    val change = awaitPointerEvent(PointerEventPass.Initial).changes
                        .firstOrNull { it.id == longPress.id } ?: break
                    change.consume()
                    if (!change.pressed) {
                        released = true
                        break
                    }
                    val y = change.position.y
                    val height = size.height.toFloat()
                    scrollSpeed = when {
                        y < edgePx -> -(edgePx - y) / 4f
                        y > height - edgePx -> (y - (height - edgePx)) / 4f
                        else -> 0f
                    }
                    dragTo(session, change.position)
                }
            } finally {
                scrollSpeed = 0f
                sessionRef[0] = null
                currentHandler.onRangeEnd()
            }
            if (released && !session.moved) currentHandler.onPreview(anchor)
        }
    }
}
