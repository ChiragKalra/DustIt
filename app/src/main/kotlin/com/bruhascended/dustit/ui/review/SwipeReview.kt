package com.bruhascended.dustit.ui.review

import android.text.format.DateUtils
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.bruhascended.dustit.R
import com.bruhascended.dustit.cleanup.ReviewItem
import com.bruhascended.dustit.data.MediaKind
import com.bruhascended.dustit.ui.formatBytes
import com.bruhascended.dustit.ui.formatCount
import com.bruhascended.dustit.ui.image.mediaModel
import kotlinx.coroutines.launch

/** Fraction of the card width a drag must travel to count as a decision. */
private const val DECISION_THRESHOLD = 0.3f

/** Swipe left to trash, right to keep. Decisions are written into the same selection the grid uses. */
@Composable
fun SwipeReview(
    state: ReviewUiState,
    onDecide: (ReviewItem, trash: Boolean) -> Unit,
    onUndo: () -> Unit,
    onTrash: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val session = state.swipe ?: return
    val current = state.swipeRemaining.firstOrNull()
    if (current == null) {
        SwipeFinished(state, onTrash, onExit, modifier)
        return
    }
    val next = state.swipeRemaining.getOrNull(1)
    val done = session.history.size
    val total = done + state.swipeRemaining.size

    Column(modifier.padding(16.dp)) {
        Text(
            stringResource(R.string.swipe_progress, formatCount(done + 1), formatCount(total)),
            style = MaterialTheme.typography.labelLarge,
        )
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(progress = { done.toFloat() / total }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(16.dp))

        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val widthPx = with(LocalDensity.current) { maxWidth.toPx() }
            val scope = rememberCoroutineScope()
            // Keyed by item so each new card starts centred.
            val offsetX = remember(current.id) { Animatable(0f) }

            fun decide(trash: Boolean) {
                scope.launch {
                    offsetX.animateTo(if (trash) -widthPx * 1.5f else widthPx * 1.5f)
                    onDecide(current, trash)
                }
            }

            if (next != null) {
                SwipeCard(
                    item = next,
                    selected = state.selection.isSelected(next),
                    progress = 0f,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = 0.94f
                            scaleY = 0.94f
                            alpha = 0.7f
                        },
                )
            }
            SwipeCard(
                item = current,
                selected = state.selection.isSelected(current),
                progress = offsetX.value / (widthPx * DECISION_THRESHOLD),
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationX = offsetX.value
                        rotationZ = offsetX.value / widthPx * 12f
                    }
                    .pointerInput(current.id) {
                        detectDragGestures(
                            onDragEnd = {
                                when {
                                    offsetX.value < -widthPx * DECISION_THRESHOLD -> decide(trash = true)
                                    offsetX.value > widthPx * DECISION_THRESHOLD -> decide(trash = false)
                                    else -> scope.launch { offsetX.animateTo(0f) }
                                }
                            },
                            onDragCancel = { scope.launch { offsetX.animateTo(0f) } },
                        ) { change, drag ->
                            change.consume()
                            scope.launch { offsetX.snapTo(offsetX.value + drag.x) }
                        }
                    },
            )

            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledIconButton(
                    onClick = { decide(trash = true) },
                    modifier = Modifier.size(64.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                ) { Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.cd_swipe_trash)) }
                IconButton(
                    onClick = onUndo,
                    enabled = session.history.isNotEmpty(),
                    colors = IconButtonDefaults.iconButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                ) { Icon(painterResource(R.drawable.ic_undo), contentDescription = stringResource(R.string.cd_swipe_undo)) }
                FilledIconButton(
                    onClick = { decide(trash = false) },
                    modifier = Modifier.size(64.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                ) { Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.cd_swipe_keep)) }
            }
        }
    }
}

/** [progress] is the signed drag distance relative to the decision threshold: -1 = trash, +1 = keep. */
@Composable
private fun SwipeCard(item: ReviewItem, selected: Boolean, progress: Float, modifier: Modifier) {
    Card(modifier.padding(bottom = 96.dp), shape = RoundedCornerShape(24.dp)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AsyncImage(
                model = mediaModel(item),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
            if (item.kind == MediaKind.Video) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.align(Alignment.Center).size(64.dp),
                )
            }
            Stamp(
                text = stringResource(R.string.swipe_keep_label),
                color = Color(0xFF4CAF50),
                alpha = progress.coerceIn(0f, 1f),
                modifier = Modifier.align(Alignment.TopStart).padding(24.dp).rotate(-12f),
            )
            Stamp(
                text = stringResource(R.string.swipe_trash_label),
                color = Color(0xFFF44336),
                alpha = (-progress).coerceIn(0f, 1f),
                modifier = Modifier.align(Alignment.TopEnd).padding(24.dp).rotate(12f),
            )
            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.5f))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Text(
                    stringResource(if (item.preselected) R.string.swipe_suggested_trash else R.string.swipe_suggested_keep),
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    "${DateUtils.formatDateTime(LocalContext.current, item.dateTaken, DateUtils.FORMAT_SHOW_DATE)} · ${formatBytes(item.sizeBytes)}" +
                        if (selected) " · " + stringResource(R.string.cd_selected) else "",
                    color = Color.White.copy(alpha = 0.8f),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun Stamp(text: String, color: Color, alpha: Float, modifier: Modifier) {
    if (alpha < 0.01f) return
    Text(
        text,
        color = color,
        fontWeight = FontWeight.Black,
        style = MaterialTheme.typography.headlineMedium,
        modifier = modifier
            .graphicsLayer { this.alpha = alpha }
            .border(3.dp, color, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 4.dp),
    )
}

@Composable
private fun SwipeFinished(state: ReviewUiState, onTrash: () -> Unit, onExit: () -> Unit, modifier: Modifier) {
    Column(
        modifier.padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.swipe_done_title), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        val count = state.selected.size
        Text(
            pluralStringResource(R.plurals.review_selected_count, count, formatCount(count)) +
                " · " + stringResource(R.string.swipe_done_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        if (count > 0) {
            Button(onClick = onTrash, enabled = !state.trashing) { Text(stringResource(R.string.review_trash)) }
            Spacer(Modifier.height(8.dp))
        }
        OutlinedButton(onClick = onExit) { Text(stringResource(R.string.swipe_back_to_grid)) }
    }
}
