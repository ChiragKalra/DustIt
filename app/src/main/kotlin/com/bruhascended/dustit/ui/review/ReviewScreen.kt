package com.bruhascended.dustit.ui.review

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.bruhascended.dustit.R
import com.bruhascended.dustit.cleanup.ReviewItem
import com.bruhascended.dustit.data.MediaKind
import com.bruhascended.dustit.data.contentUri
import com.bruhascended.dustit.ui.formatBytes
import com.bruhascended.dustit.ui.formatCount
import com.bruhascended.dustit.ui.image.mediaModel
import android.text.format.Formatter as FileSizeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(
    onBack: () -> Unit,
    viewModel: ReviewViewModel = viewModel(factory = ReviewViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resources = LocalResources.current
    val snackbarHostState = remember { SnackbarHostState() }
    var previewing by remember { mutableStateOf<ReviewItem?>(null) }
    val swiping = state.swipe != null

    val trashLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        viewModel.onTrashResult(it.resultCode == Activity.RESULT_OK)
    }
    LaunchedEffect(viewModel) {
        viewModel.trashRequests.collect { trashLauncher.launch(it) }
    }
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message ->
            val text = when (message) {
                is ReviewMessage.Trashed -> resources.getQuantityString(
                    R.plurals.review_trashed,
                    message.count,
                    formatCount(message.count),
                    FileSizeFormatter.formatShortFileSize(context, message.bytes),
                )
                is ReviewMessage.Reminder -> resources.getString(
                    if (message.on) R.string.review_reminder_on else R.string.review_reminder_off,
                    resources.getString(state.bucket.title),
                )
            }
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(text, duration = SnackbarDuration.Long)
        }
    }
    BackHandler(enabled = swiping) { viewModel.stopSwipe() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(state.bucket.title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
                actions = {
                    if (!state.isEmpty || swiping) {
                        TextButton(onClick = { if (swiping) viewModel.stopSwipe() else viewModel.startSwipe() }) {
                            Text(stringResource(if (swiping) R.string.review_mode_grid else R.string.review_mode_swipe))
                        }
                    }
                    IconButton(onClick = viewModel::toggleReminder) {
                        Icon(
                            if (state.reminderOn) Icons.Filled.Notifications else Icons.Outlined.Notifications,
                            contentDescription = stringResource(if (state.reminderOn) R.string.cd_reminder_on else R.string.cd_reminder_off),
                            tint = if (state.reminderOn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        },
        bottomBar = {
            if (!state.isEmpty) TrashBar(state, onTrash = viewModel::trashSelected)
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        val modifier = Modifier
            .fillMaxSize()
            .padding(padding)
        when {
            !state.loaded -> Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            swiping -> SwipeReview(
                state = state,
                onDecide = viewModel::swipeDecide,
                onUndo = viewModel::swipeUndo,
                onTrash = viewModel::trashSelected,
                onExit = viewModel::stopSwipe,
                modifier = modifier,
            )
            state.isEmpty -> AllClear(onBack, modifier)
            else -> ReviewGrid(state, viewModel, onPreview = { previewing = it }, modifier = modifier)
        }
    }

    previewing?.let { item ->
        PreviewDialog(
            item = item,
            selected = state.selection.isSelected(item),
            onToggle = { viewModel.toggle(item) },
            onDismiss = { previewing = null },
        )
    }
}

@Composable
private fun TrashBar(state: ReviewUiState, onTrash: () -> Unit) {
    Surface(tonalElevation = 3.dp, shadowElevation = 3.dp) {
        Row(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                val count = state.selected.size
                val progress = state.trash?.takeIf { it.total > 0 }
                Text(
                    pluralStringResource(R.plurals.review_selected_count, count, formatCount(count)),
                    style = MaterialTheme.typography.titleMedium,
                )
                if (progress != null) {
                    Text(
                        stringResource(R.string.review_trash_progress, formatCount(progress.done), formatCount(progress.total)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else if (count > 0) {
                    Text(
                        stringResource(R.string.review_frees, formatBytes(state.selectedBytes)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Button(onClick = onTrash, enabled = state.selected.isNotEmpty() && !state.trashing) {
                Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.review_trash))
            }
        }
    }
}

@Composable
private fun AllClear(onBack: () -> Unit, modifier: Modifier) {
    Column(
        modifier.padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.review_all_clear_title), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.review_all_clear_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        FilledTonalButton(onClick = onBack) { Text(stringResource(R.string.review_back_home)) }
    }
}

/** Full-screen look at one item, with the same select toggle as the grid. Videos can be played externally. */
@Composable
private fun PreviewDialog(
    item: ReviewItem,
    selected: Boolean,
    onToggle: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val uri = contentUri(item.id, item.kind, item.documentUri)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable(onClick = onDismiss),
        ) {
            AsyncImage(
                model = mediaModel(item),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .safeDrawingPadding()
                    .padding(24.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (item.kind == MediaKind.Video) {
                    FilledTonalButton(onClick = {
                        val play = Intent(Intent.ACTION_VIEW)
                            .setDataAndType(uri, "video/*")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        try {
                            context.startActivity(play)
                        } catch (_: ActivityNotFoundException) {
                            // No video player installed; nothing sensible to do.
                        }
                    }) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.preview_play))
                    }
                }
                FilledTonalButton(onClick = onToggle) {
                    if (selected) {
                        Icon(Icons.Filled.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(stringResource(if (selected) R.string.preview_selected else R.string.preview_not_selected))
                }
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .safeDrawingPadding()
                    .padding(8.dp)
                    .clip(CircleShape),
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back), tint = Color.White)
            }
        }
    }
}
