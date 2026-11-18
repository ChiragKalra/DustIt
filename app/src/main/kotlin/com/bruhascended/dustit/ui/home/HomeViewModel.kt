package com.bruhascended.dustit.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.bruhascended.dustit.appContainer
import com.bruhascended.dustit.cleanup.Bucket
import com.bruhascended.dustit.cleanup.BucketSummary
import com.bruhascended.dustit.cleanup.CleanupOverview
import com.bruhascended.dustit.cleanup.CleanupRules
import com.bruhascended.dustit.data.MediaRepository
import com.bruhascended.dustit.data.ScanProgress
import com.bruhascended.dustit.scan.BackgroundWork
import com.bruhascended.dustit.scan.ScanReason
import com.bruhascended.dustit.settings.SettingsRepository
import com.bruhascended.dustit.whatsapp.WhatsAppAccess
import com.bruhascended.dustit.whatsapp.WhatsAppLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class WhatsAppHome {
    /** No sign of WhatsApp, or the user said "Not now". */
    Hidden,

    /** WhatsApp is here but its hidden folders aren't shared yet: show the card offering it. */
    Offer,
    Connected,
}

data class HomeUiState(
    val loaded: Boolean = false,
    val summaries: List<BucketSummary> = emptyList(),
    /** Suggestions from WhatsApp's hidden folders, shown in their own section. */
    val whatsAppSummaries: List<BucketSummary> = emptyList(),
    val reclaimableBytes: Long = 0,
    val progress: ScanProgress = ScanProgress(0, 0),
    val scanning: Boolean = false,
    val whatsApp: WhatsAppHome = WhatsAppHome.Hidden,
) {
    val showWhatsAppSection: Boolean get() = whatsApp == WhatsAppHome.Connected || whatsAppSummaries.isNotEmpty()
}

private class Overview(val overview: CleanupOverview, val hasWhatsAppMedia: Boolean)

class HomeViewModel(
    repository: MediaRepository,
    private val work: BackgroundWork,
    private val settings: SettingsRepository,
    whatsApp: WhatsAppAccess,
) : ViewModel() {

    private val whatsAppInstalled = whatsApp.isInstalled()

    val state: StateFlow<HomeUiState> = combine(
        repository.rows.map { rows ->
            Overview(
                CleanupRules.overview(rows, System.currentTimeMillis()),
                // Received WhatsApp media shows up in the gallery, so this also catches WhatsApp
                // installed under a profile or package name DustIt can't query.
                hasWhatsAppMedia = rows.any { WhatsAppLayout.isWhatsAppMedia(it.relativePath) },
            )
        }.flowOn(Dispatchers.Default),
        repository.scanProgress(),
        work.isScanning,
        whatsApp.tree.map { it != null },
        settings.whatsAppPromoDismissed,
    ) { overview, progress, scanning, connected, dismissed ->
        val (whatsAppSummaries, summaries) = overview.overview.summaries.partition { it.bucket is Bucket.WhatsApp }
        HomeUiState(
            loaded = true,
            summaries = summaries,
            whatsAppSummaries = whatsAppSummaries,
            reclaimableBytes = overview.overview.reclaimableBytes,
            progress = progress,
            scanning = scanning,
            whatsApp = when {
                connected -> WhatsAppHome.Connected
                (whatsAppInstalled || overview.hasWhatsAppMedia) && !dismissed -> WhatsAppHome.Offer
                else -> WhatsAppHome.Hidden
            },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    fun refresh() = work.scan(ScanReason.Restart)

    fun dismissWhatsApp() {
        viewModelScope.launch { settings.dismissWhatsAppPromo() }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val container = this[APPLICATION_KEY]!!.appContainer
                HomeViewModel(container.mediaRepository, container.work, container.settings, container.whatsApp)
            }
        }
    }
}
