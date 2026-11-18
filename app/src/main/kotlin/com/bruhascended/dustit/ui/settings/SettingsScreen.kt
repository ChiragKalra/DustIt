package com.bruhascended.dustit.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.bruhascended.dustit.R
import com.bruhascended.dustit.appContainer
import com.bruhascended.dustit.cleanup.Bucket
import com.bruhascended.dustit.data.MediaRepository
import com.bruhascended.dustit.data.TrashSummary
import com.bruhascended.dustit.scan.BackgroundWork
import com.bruhascended.dustit.scan.ScanReason
import com.bruhascended.dustit.settings.SettingsRepository
import com.bruhascended.dustit.ui.formatBytes
import com.bruhascended.dustit.ui.formatCount
import com.bruhascended.dustit.ui.whatsapp.rememberWhatsAppConnect
import com.bruhascended.dustit.whatsapp.WhatsAppAccess
import com.bruhascended.dustit.whatsapp.WhatsAppLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
    val autoScan: Boolean = true,
    val reminders: Set<String> = emptySet(),
    val whatsApp: WhatsAppSettings = WhatsAppSettings(),
)

data class WhatsAppSettings(
    val available: Boolean = false,
    val connected: Boolean = false,
    val trash: TrashSummary = TrashSummary(0, 0),
    /** Restoring or deleting trashed files; buttons are disabled meanwhile. */
    val busy: Boolean = false,
) {
    val visible: Boolean get() = available || connected || trash.count > 0
}

class SettingsViewModel(
    private val settings: SettingsRepository,
    private val whatsApp: WhatsAppAccess,
    private val repository: MediaRepository,
    private val work: BackgroundWork,
    /** Restoring or deleting thousands of files takes minutes; leaving Settings mustn't stop it. */
    private val appScope: CoroutineScope,
) : ViewModel() {
    private val whatsAppInstalled = whatsApp.isInstalled()
    private val disconnecting = MutableStateFlow(false)
    private val busy = combine(disconnecting, repository.hiddenTrashBusy) { a, b -> a || b }

    /** Same test as the home screen's offer, so Settings always offers what home does. */
    private val whatsAppAvailable = repository.rows
        .map { rows -> whatsAppInstalled || rows.any { WhatsAppLayout.isWhatsAppMedia(it.relativePath) } }
        .flowOn(Dispatchers.Default)

    private val whatsAppState = combine(whatsAppAvailable, whatsApp.tree.map { it != null }, repository.hiddenTrash, busy) {
            available, connected, trash, busy ->
        WhatsAppSettings(available, connected, trash, busy)
    }

    val state: StateFlow<SettingsUiState> = combine(settings.autoScan, settings.reminderBuckets, whatsAppState, ::SettingsUiState)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun setAutoScan(enabled: Boolean) {
        viewModelScope.launch { settings.setAutoScan(enabled) }
    }

    fun setReminder(bucket: Bucket, enabled: Boolean) {
        viewModelScope.launch { settings.setReminder(bucket.key, enabled) }
    }

    fun restoreTrash() {
        appScope.launch {
            if (repository.restoreHiddenTrash() > 0) work.scan(ScanReason.Changed)
        }
    }

    fun emptyTrash() {
        appScope.launch { repository.emptyHiddenTrash() }
    }

    /**
     * Trashed files can only be purged while DustIt has access, so they're put back first rather
     * than left behind as files nobody would ever clear.
     */
    fun disconnectWhatsApp() {
        if (disconnecting.value) return
        disconnecting.value = true
        appScope.launch {
            try {
                repository.restoreHiddenTrash()
                whatsApp.disconnect()
                repository.syncHiddenFolders(null)
                work.scan(ScanReason.Changed)
            } finally {
                disconnecting.value = false
            }
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val container = this[APPLICATION_KEY]!!.appContainer
                SettingsViewModel(container.settings, container.whatsApp, container.mediaRepository, container.work, container.scope)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            item { SectionTitle(stringResource(R.string.settings_scanning)) }
            item {
                SwitchRow(
                    title = stringResource(R.string.settings_auto_scan),
                    body = stringResource(R.string.settings_auto_scan_body),
                    checked = state.autoScan,
                    onCheckedChange = viewModel::setAutoScan,
                )
            }
            if (state.whatsApp.visible) {
                item { SectionTitle(stringResource(R.string.settings_whatsapp)) }
                item { WhatsAppRows(state.whatsApp, viewModel) }
            }
            item { SectionTitle(stringResource(R.string.settings_reminders), stringResource(R.string.settings_reminders_body)) }
            // WhatsApp suggestions can only fill up once its folders are shared.
            items(Bucket.all.filter { it !is Bucket.WhatsApp || state.whatsApp.connected }, key = { it.key }) { bucket ->
                SwitchRow(
                    title = stringResource(bucket.title),
                    body = stringResource(bucket.description),
                    checked = bucket.key in state.reminders,
                    onCheckedChange = { viewModel.setReminder(bucket, it) },
                )
            }
        }
    }
}

@Composable
private fun WhatsAppRows(whatsApp: WhatsAppSettings, viewModel: SettingsViewModel) {
    val connect = rememberWhatsAppConnect()
    var confirmDisconnect by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val trash = whatsApp.trash

    Column {
        ActionRow(
            title = stringResource(R.string.settings_wa_folders),
            body = stringResource(if (whatsApp.connected) R.string.settings_wa_connected else R.string.settings_wa_not_connected),
        ) {
            if (whatsApp.connected) {
                TextButton(onClick = { confirmDisconnect = true }, enabled = !whatsApp.busy) {
                    Text(stringResource(R.string.settings_wa_disconnect))
                }
            } else {
                FilledTonalButton(onClick = connect) { Text(stringResource(R.string.wa_offer_allow)) }
            }
        }
        if (whatsApp.connected || trash.count > 0) {
            ActionRow(
                title = stringResource(R.string.settings_wa_trash),
                body = if (trash.count > 0) {
                    stringResource(
                        R.string.settings_wa_trash_body,
                        pluralStringResource(R.plurals.item_count, trash.count, formatCount(trash.count)),
                        formatBytes(trash.bytes),
                    )
                } else {
                    stringResource(R.string.settings_wa_trash_empty)
                },
            )
            if (trash.count > 0) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = viewModel::restoreTrash, enabled = !whatsApp.busy) {
                        Text(stringResource(R.string.settings_wa_restore))
                    }
                    TextButton(onClick = { confirmDelete = true }, enabled = !whatsApp.busy) {
                        Text(stringResource(R.string.settings_wa_delete_now))
                    }
                }
            }
        }
    }

    if (confirmDisconnect) {
        AlertDialog(
            onDismissRequest = { confirmDisconnect = false },
            title = { Text(stringResource(R.string.wa_disconnect_title)) },
            text = {
                Text(
                    if (trash.count > 0) {
                        pluralStringResource(R.plurals.wa_disconnect_body_trash, trash.count, formatCount(trash.count))
                    } else {
                        stringResource(R.string.wa_disconnect_body)
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmDisconnect = false; viewModel.disconnectWhatsApp() }) {
                    Text(stringResource(R.string.settings_wa_disconnect))
                }
            },
            dismissButton = { TextButton(onClick = { confirmDisconnect = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(pluralStringResource(R.plurals.wa_delete_now_title, trash.count, formatCount(trash.count))) },
            text = { Text(stringResource(R.string.wa_delete_now_body)) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; viewModel.emptyTrash() }) {
                    Text(stringResource(R.string.wa_delete_now_confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun ActionRow(title: String, body: String, action: (@Composable () -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        action?.invoke()
    }
}

@Composable
private fun SectionTitle(title: String, body: String? = null) {
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        if (body != null) {
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SwitchRow(title: String, body: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}
