package com.bruhascended.dustit.ui.review

import androidx.activity.result.IntentSenderRequest
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.bruhascended.dustit.appContainer
import com.bruhascended.dustit.cleanup.Bucket
import com.bruhascended.dustit.cleanup.CleanupRules
import com.bruhascended.dustit.cleanup.ReviewItem
import com.bruhascended.dustit.cleanup.ReviewSection
import com.bruhascended.dustit.data.MediaRepository
import com.bruhascended.dustit.data.MediaStoreSource
import com.bruhascended.dustit.settings.SettingsRepository
import com.bruhascended.dustit.ui.ARG_BUCKET
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * URIs per system trash dialog. Every URI travels in one binder transaction (about 1 MB shared
 * per process), so very large selections are split into several confirmations.
 */
private const val TRASH_BATCH = 1_000

/** A pass through the bucket one item at a time; decisions are written straight into the selection. */
data class SwipeSession(val queue: List<Long>, val history: List<SwipeStep> = emptyList())

data class SwipeStep(val id: Long, val wasSelected: Boolean)

/**
 * A trash in progress. [total] is 0 while the system dialog is up; it counts files from hidden
 * folders once DustIt moves them itself, which can take a while for thousands of files.
 */
data class TrashProgress(val done: Int = 0, val total: Int = 0)

data class ReviewUiState(
    val bucket: Bucket,
    val loaded: Boolean = false,
    val sections: List<ReviewSection> = emptyList(),
    val selection: ReviewSelection = ReviewSelection(),
    val trash: TrashProgress? = null,
    val reminderOn: Boolean = false,
    val swipe: SwipeSession? = null,
) {
    val trashing: Boolean get() = trash != null

    /** All items in display order. */
    val items: List<ReviewItem> by lazy { sections.flatMap { it.items } }
    val byId: Map<Long, ReviewItem> by lazy { items.associateBy { it.id } }
    val selected: List<ReviewItem> by lazy { items.filter(selection::isSelected) }
    val selectedBytes: Long by lazy { selected.sumOf { it.sizeBytes } }
    val isEmpty: Boolean get() = items.isEmpty()

    /** Items still to swipe, skipping anything trashed since the session started. */
    val swipeRemaining: List<ReviewItem> by lazy {
        val session = swipe ?: return@lazy emptyList()
        val decided = session.history.mapTo(HashSet()) { it.id }
        session.queue.filter { it !in decided }.mapNotNull(byId::get)
    }
}

sealed interface ReviewMessage {
    data class Trashed(val count: Int, val bytes: Long) : ReviewMessage
    data class Reminder(val on: Boolean) : ReviewMessage
}

class ReviewViewModel(
    savedStateHandle: SavedStateHandle,
    private val repository: MediaRepository,
    private val settings: SettingsRepository,
    /** Outlives this screen, so a long trash of hidden-folder files isn't cut short by leaving it. */
    private val appScope: CoroutineScope,
) : ViewModel() {

    private val bucket = checkNotNull(Bucket.fromKey(savedStateHandle[ARG_BUCKET])) { "Unknown bucket" }
    private val selection = MutableStateFlow(ReviewSelection())
    private val trash = MutableStateFlow<TrashProgress?>(null)
    private val swipe = MutableStateFlow<SwipeSession?>(null)

    val state: StateFlow<ReviewUiState> = combine(
        repository.rows.map { CleanupRules.sections(bucket, it, System.currentTimeMillis()) }.flowOn(Dispatchers.Default),
        selection,
        trash,
        settings.reminderBuckets.map { bucket.key in it },
        swipe,
    ) { sections, selection, trash, reminderOn, swipe ->
        ReviewUiState(bucket, loaded = true, sections, selection, trash, reminderOn, swipe)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReviewUiState(bucket))

    private val trashRequestChannel = Channel<IntentSenderRequest>(Channel.BUFFERED)

    /** Each request must be launched with StartIntentSenderForResult and answered via [onTrashResult]. */
    val trashRequests: Flow<IntentSenderRequest> = trashRequestChannel.receiveAsFlow()

    private val messageChannel = Channel<ReviewMessage>(Channel.BUFFERED)
    val messages: Flow<ReviewMessage> = messageChannel.receiveAsFlow()

    // region Selection

    fun toggle(item: ReviewItem) = selection.update { it.toggle(item) }

    fun setAll(items: List<ReviewItem>, selected: Boolean) = selection.update { it.withAll(items, selected) }

    private var rangeBase: ReviewSelection? = null
    private var rangeTarget = false

    /** Long-press landed on [anchor]; dragging will set everything it passes over to the opposite of the anchor's state. */
    fun startRange(anchor: ReviewItem) {
        rangeBase = selection.value
        rangeTarget = !selection.value.isSelected(anchor)
    }

    /** Applies the range from scratch each time, so dragging back over items restores them. */
    fun updateRange(anchor: ReviewItem, current: ReviewItem) {
        val base = rangeBase ?: return
        val items = state.value.items
        val from = items.indexOfFirst { it.id == anchor.id }
        val to = items.indexOfFirst { it.id == current.id }
        if (from < 0 || to < 0) return
        selection.value = base.withAll(items.subList(minOf(from, to), maxOf(from, to) + 1), rangeTarget)
    }

    fun endRange() {
        rangeBase = null
    }

    // endregion

    // region Swipe

    fun startSwipe() {
        swipe.value = SwipeSession(queue = state.value.items.map { it.id })
    }

    fun stopSwipe() {
        swipe.value = null
    }

    fun swipeDecide(item: ReviewItem, trash: Boolean) {
        val wasSelected = selection.value.isSelected(item)
        selection.update { it.withAll(listOf(item), trash) }
        swipe.update { it?.copy(history = it.history + SwipeStep(item.id, wasSelected)) }
    }

    fun swipeUndo() {
        val session = swipe.value ?: return
        val last = session.history.lastOrNull() ?: return
        state.value.byId[last.id]?.let { item -> selection.update { it.withAll(listOf(item), last.wasSelected) } }
        swipe.value = session.copy(history = session.history.dropLast(1))
    }

    // endregion

    fun toggleReminder() {
        val on = !state.value.reminderOn
        viewModelScope.launch {
            settings.setReminder(bucket.key, on)
            messageChannel.send(ReviewMessage.Reminder(on))
        }
    }

    // region Trash

    private val pendingBatches = ArrayDeque<List<ReviewItem>>()
    private var pendingHidden: List<ReviewItem> = emptyList()
    private var inFlight: List<ReviewItem>? = null
    private var trashedCount = 0
    private var trashedBytes = 0L

    /**
     * Gallery items go through the system trash dialog in batches. Files from WhatsApp's hidden
     * folders can't (see `HiddenTrash`), so DustIt moves them to its own trash after the last
     * dialog is approved; declining any dialog cancels the rest, hidden files included.
     */
    fun trashSelected() {
        if (trash.value != null) return
        val items = state.value.selected
        if (items.isEmpty()) return
        trash.value = TrashProgress()
        trashedCount = 0
        trashedBytes = 0
        val (hidden, gallery) = items.partition { it.documentUri != null }
        pendingHidden = hidden
        pendingBatches.clear()
        pendingBatches.addAll(gallery.chunked(TRASH_BATCH))
        launchNextBatch()
    }

    fun onTrashResult(approved: Boolean) {
        val batch = inFlight ?: return
        inFlight = null
        if (!approved) {
            pendingBatches.clear()
            pendingHidden = emptyList()
            finishTrashing()
            return
        }
        viewModelScope.launch {
            val ids = batch.map { it.id }
            repository.forget(ids)
            selection.update { it.without(ids) }
            trashedCount += batch.size
            trashedBytes += batch.sumOf { it.sizeBytes }
            launchNextBatch()
        }
    }

    private fun launchNextBatch() {
        val batch = pendingBatches.removeFirstOrNull() ?: return trashHidden()
        inFlight = batch
        val pendingIntent = repository.createTrashRequest(batch.map { MediaStoreSource.uri(it.id, it.kind) })
        trashRequestChannel.trySend(IntentSenderRequest.Builder(pendingIntent.intentSender).build())
    }

    private fun trashHidden() {
        val items = pendingHidden
        pendingHidden = emptyList()
        if (items.isEmpty()) return finishTrashing()
        // The file work happens off the main thread inside the repository; the bookkeeping here
        // stays on it, like the rest of this class.
        appScope.launch(Dispatchers.Main.immediate) {
            trash.value = TrashProgress(0, items.size)
            val moved = repository.trashHidden(items.map { it.id }) { done -> trash.value = TrashProgress(done, items.size) }
            val movedIds = moved.toHashSet()
            selection.update { it.without(moved) }
            items.filter { it.id in movedIds }.let { done ->
                trashedCount += done.size
                trashedBytes += done.sumOf { it.sizeBytes }
            }
            finishTrashing()
        }
    }

    private fun finishTrashing() {
        if (trashedCount > 0) messageChannel.trySend(ReviewMessage.Trashed(trashedCount, trashedBytes))
        trash.value = null
    }

    // endregion

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val container = this[APPLICATION_KEY]!!.appContainer
                ReviewViewModel(createSavedStateHandle(), container.mediaRepository, container.settings, container.scope)
            }
        }
    }
}
