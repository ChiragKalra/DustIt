package com.bruhascended.dustit.ui.review

import android.text.format.DateUtils
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.bruhascended.dustit.R
import com.bruhascended.dustit.cleanup.ReviewItem
import com.bruhascended.dustit.cleanup.ReviewSection
import com.bruhascended.dustit.cleanup.SectionKind
import com.bruhascended.dustit.data.MediaKind
import com.bruhascended.dustit.ui.formatBytes
import com.bruhascended.dustit.ui.formatCount
import com.bruhascended.dustit.ui.image.mediaModel

@Composable
fun ReviewGrid(
    state: ReviewUiState,
    viewModel: ReviewViewModel,
    onPreview: (ReviewItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val gridState = rememberLazyGridState()
    val handler = remember(viewModel) {
        object : GridGestureHandler {
            override fun onTap(item: ReviewItem) = viewModel.toggle(item)
            override fun onPreview(item: ReviewItem) = onPreview(item)
            override fun onRangeStart(anchor: ReviewItem) = viewModel.startRange(anchor)
            override fun onRangeTo(anchor: ReviewItem, current: ReviewItem) = viewModel.updateRange(anchor, current)
            override fun onRangeEnd() = viewModel.endRange()
        }
    }
    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Adaptive(minSize = 104.dp),
        modifier = modifier.gridSelectionGestures(
            gridState = gridState,
            itemForKey = { key -> (key as? Long)?.let(state.byId::get) },
            handler = handler,
        ),
        contentPadding = PaddingValues(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item(key = "hint", span = { GridItemSpan(maxLineSpan) }) {
            Text(
                stringResource(R.string.review_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        for (section in state.sections) {
            item(key = "header-${section.key}", span = { GridItemSpan(maxLineSpan) }) {
                SectionHeader(section, state.selection, onSetAll = viewModel::setAll)
            }
            items(section.items, key = { it.id }) { item ->
                GridCell(
                    item = item,
                    selected = state.selection.isSelected(item),
                    onToggle = { viewModel.toggle(item) },
                    onPreview = { onPreview(item) },
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(
    section: ReviewSection,
    selection: ReviewSelection,
    onSetAll: (List<ReviewItem>, Boolean) -> Unit,
) {
    val count = section.items.size
    val title = when (section.kind) {
        SectionKind.Likely -> stringResource(R.string.review_likely_title)
        SectionKind.Maybe -> stringResource(R.string.review_maybe_title)
        SectionKind.OldScreenshots -> stringResource(R.string.review_old_screenshots_title)
        SectionKind.RecentScreenshots -> stringResource(R.string.review_recent_screenshots_title)
        SectionKind.LargeVideos -> stringResource(R.string.review_large_videos_title)
        SectionKind.SimilarSet -> null
        SectionKind.SentCopies -> stringResource(R.string.review_sent_copies_title)
        SectionKind.Photos -> stringResource(R.string.review_photos_title)
        SectionKind.Videos -> stringResource(R.string.review_videos_title)
        SectionKind.Stickers -> stringResource(R.string.review_stickers_title)
    }
    val heading = title?.let { stringResource(R.string.section_with_count, it, formatCount(count)) }
        ?: pluralStringResource(R.plurals.similar_set_title, count, formatCount(count))
    val subtitle = when (section.kind) {
        SectionKind.Likely, SectionKind.OldScreenshots -> stringResource(R.string.review_likely_subtitle)
        SectionKind.Maybe -> stringResource(R.string.review_maybe_subtitle)
        SectionKind.RecentScreenshots -> stringResource(R.string.review_recent_screenshots_subtitle)
        SectionKind.LargeVideos, SectionKind.Videos -> stringResource(R.string.review_large_videos_subtitle)
        SectionKind.SentCopies -> stringResource(R.string.review_sent_copies_subtitle)
        SectionKind.Photos, SectionKind.Stickers -> stringResource(R.string.review_recent_screenshots_subtitle)
        SectionKind.SimilarSet -> DateUtils.formatDateTime(
            LocalContext.current,
            section.items.maxOf { it.dateTaken },
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH,
        )
    }
    val allSelected = section.items.all(selection::isSelected)
    Row(
        Modifier.padding(start = 8.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(heading, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = { onSetAll(section.items, !allSelected) }) {
            Text(stringResource(if (allSelected) R.string.review_deselect_all else R.string.review_select_all))
        }
    }
}

@Composable
private fun GridCell(
    item: ReviewItem,
    selected: Boolean,
    onToggle: () -> Unit,
    onPreview: () -> Unit,
) {
    // Scale via graphicsLayer rather than padding so the image's layout size (and Coil request) stays fixed.
    val scale by animateFloatAsState(if (selected) 0.86f else 1f, label = "selectionScale")
    val stateText = stringResource(if (selected) R.string.cd_selected else R.string.cd_not_selected)
    val toggleLabel = stringResource(R.string.cd_toggle)
    val previewLabel = stringResource(R.string.cd_preview)
    val videoLabel = stringResource(R.string.cd_video)
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .semantics(mergeDescendants = true) {
                role = Role.Checkbox
                stateDescription = if (item.kind == MediaKind.Video) "$videoLabel, $stateText" else stateText
                onClick(toggleLabel) { onToggle(); true }
                onLongClick(previewLabel) { onPreview(); true }
            },
    ) {
        AsyncImage(
            model = mediaModel(item),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    shape = RoundedCornerShape(((1f - scale) * 80).dp)
                    clip = true
                },
        )
        SelectionBadge(selected, Modifier.align(Alignment.TopStart).padding(6.dp))
        if (item.isBest) {
            Chip(Modifier.align(Alignment.BottomStart).padding(6.dp)) {
                Icon(Icons.Filled.Star, contentDescription = null, tint = Color(0xFFFFD54F), modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(2.dp))
                Text(stringResource(R.string.review_best), color = Color.White, style = MaterialTheme.typography.labelSmall)
            }
        }
        if (item.kind == MediaKind.Video) {
            Chip(Modifier.align(Alignment.BottomEnd).padding(6.dp)) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                // Files from hidden folders have no duration on record; their size is what matters here.
                val size = formatBytes(item.sizeBytes)
                Text(
                    if (item.durationMs > 0) "${DateUtils.formatElapsedTime(item.durationMs / 1000)} · $size" else size,
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@Composable
private fun Chip(modifier: Modifier, content: @Composable () -> Unit) {
    Row(
        modifier
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
            .padding(horizontal = 5.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

@Composable
private fun SelectionBadge(selected: Boolean, modifier: Modifier = Modifier) {
    if (selected) {
        Icon(
            Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = modifier
                .size(24.dp)
                .background(MaterialTheme.colorScheme.surface, CircleShape),
        )
    } else {
        Box(
            modifier
                .size(22.dp)
                .background(Color.Black.copy(alpha = 0.25f), CircleShape)
                .border(2.dp, Color.White, CircleShape),
        )
    }
}
