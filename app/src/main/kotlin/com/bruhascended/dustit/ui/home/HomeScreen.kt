package com.bruhascended.dustit.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.bruhascended.dustit.R
import com.bruhascended.dustit.cleanup.Bucket
import com.bruhascended.dustit.cleanup.BucketSummary
import com.bruhascended.dustit.ui.formatBytes
import com.bruhascended.dustit.ui.formatCount
import com.bruhascended.dustit.ui.image.mediaModel
import com.bruhascended.dustit.ui.photoCount
import com.bruhascended.dustit.ui.whatsapp.rememberWhatsAppConnect

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenBucket: (Bucket) -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: HomeViewModel = viewModel(factory = HomeViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val connectWhatsApp = rememberWhatsAppConnect()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = viewModel::refresh, enabled = !state.scanning) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.action_refresh))
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.action_settings))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "summary") { SummaryHeader(state) }
            if (state.whatsApp == WhatsAppHome.Offer) {
                item(key = "whatsapp-offer") { WhatsAppOfferCard(onConnect = connectWhatsApp, onDismiss = viewModel::dismissWhatsApp) }
            }
            if (state.showWhatsAppSection) {
                item(key = "whatsapp-heading") {
                    SectionHeading(stringResource(R.string.wa_section_title), stringResource(R.string.wa_section_subtitle))
                }
                items(state.whatsAppSummaries, key = { it.bucket.key }) { summary ->
                    SuggestionCard(summary, onClick = { onOpenBucket(summary.bucket) })
                }
                if (state.whatsAppSummaries.isEmpty()) {
                    item(key = "whatsapp-empty") {
                        Text(
                            stringResource(if (state.scanning) R.string.wa_section_looking else R.string.wa_section_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }
                }
                if (state.summaries.isNotEmpty()) {
                    item(key = "gallery-heading") { SectionHeading(stringResource(R.string.home_gallery_title)) }
                }
            }
            items(state.summaries, key = { it.bucket.key }) { summary ->
                SuggestionCard(summary, onClick = { onOpenBucket(summary.bucket) })
            }
            if (state.loaded && !state.scanning && state.summaries.isEmpty() && state.whatsAppSummaries.isEmpty()) {
                item(key = "empty") { AllCleanMessage() }
            }
        }
    }
}

@Composable
private fun SummaryHeader(state: HomeUiState) {
    val progress = state.progress
    Column(Modifier.padding(horizontal = 4.dp, vertical = 8.dp)) {
        if (state.reclaimableBytes > 0) {
            Text(formatBytes(state.reclaimableBytes), style = MaterialTheme.typography.displayMedium)
            Text(
                stringResource(R.string.home_reclaimable_label),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else if (state.scanning || !state.loaded) {
            Text(stringResource(R.string.home_looking), style = MaterialTheme.typography.headlineSmall)
        }
        if (state.scanning) {
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.home_scanning), style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(8.dp))
            if (progress.total > 0 && !progress.isComplete) {
                LinearProgressIndicator(
                    progress = { progress.analyzed.toFloat() / progress.total },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.home_scan_progress, formatCount(progress.analyzed), formatCount(progress.total)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun SuggestionCard(summary: BucketSummary, onClick: () -> Unit) {
    val (headline, detail) = summaryLines(summary)
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            ThumbnailMosaic(summary.previews.map { mediaModel(it) })
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(summary.bucket.title), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(2.dp))
                Text(headline, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (detail != null) {
                    Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** "214 photos · 380 MB" plus an optional second line, worded for each kind of bucket. */
@Composable
private fun summaryLines(summary: BucketSummary): Pair<String, String?> {
    val count = summary.headlineCount
    val formatted = formatCount(count)
    val what = when {
        summary.bucket == Bucket.LargeVideos || summary.bucket == Bucket.WhatsAppVideos ->
            pluralStringResource(R.plurals.video_count, count, formatted)
        summary.bucket == Bucket.Similar -> pluralStringResource(R.plurals.extra_photo_count, count, formatted)
        summary.preselectedCount == 0 -> pluralStringResource(R.plurals.double_check_only, count, formatted)
        else -> photoCount(count)
    }
    val detail = when {
        summary.bucket == Bucket.Similar ->
            pluralStringResource(R.plurals.similar_set_count, summary.setCount, formatCount(summary.setCount))
        summary.preselectedCount > 0 && summary.optionalCount > 0 ->
            pluralStringResource(R.plurals.double_check_more, summary.optionalCount, formatCount(summary.optionalCount))
        else -> null
    }
    return stringResource(R.string.suggestion_summary, what, formatBytes(summary.headlineBytes)) to detail
}

/** Up to four previews; fewer previews get bigger tiles instead of leaving gaps. */
@Composable
private fun ThumbnailMosaic(models: List<Any>) {
    val gap = Arrangement.spacedBy(2.dp)
    Row(
        modifier = Modifier
            .size(88.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        horizontalArrangement = gap,
    ) {
        when (models.size) {
            0 -> Unit
            1 -> Tile(models[0])
            2 -> models.forEach { Tile(it) }
            3 -> {
                Tile(models[0])
                Column(Modifier.weight(1f), verticalArrangement = gap) { Tile(models[1]); Tile(models[2]) }
            }
            else -> {
                Column(Modifier.weight(1f), verticalArrangement = gap) { Tile(models[0]); Tile(models[2]) }
                Column(Modifier.weight(1f), verticalArrangement = gap) { Tile(models[1]); Tile(models[3]) }
            }
        }
    }
}

@Composable
private fun RowScope.Tile(model: Any) = Thumbnail(model, Modifier.weight(1f).fillMaxHeight())

@Composable
private fun ColumnScope.Tile(model: Any) = Thumbnail(model, Modifier.weight(1f).fillMaxWidth())

@Composable
private fun Thumbnail(model: Any, modifier: Modifier) {
    AsyncImage(
        model = model,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier,
    )
}

@Composable
private fun SectionHeading(title: String, subtitle: String? = null) {
    Column(Modifier.padding(start = 4.dp, end = 4.dp, top = 12.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        if (subtitle != null) {
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The way into WhatsApp's hidden folders: what they are, and one tap to the system folder picker. */
@Composable
private fun WhatsAppOfferCard(onConnect: () -> Unit, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.wa_offer_title), style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.wa_offer_body), style = MaterialTheme.typography.bodyMedium)
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.limited_access_not_now)) }
                Spacer(Modifier.width(8.dp))
                Button(onClick = onConnect) { Text(stringResource(R.string.wa_offer_allow)) }
            }
        }
    }
}

@Composable
private fun AllCleanMessage() {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp, horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.home_all_clean_title), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.home_all_clean_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
