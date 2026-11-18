package com.bruhascended.dustit.scan

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.await
import com.bruhascended.dustit.reminders.ReminderWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

private const val SCAN = "scan"
private const val REMINDERS = "reminders"

/** Why a scan is being asked for. */
enum class ScanReason {
    /** The app came to the front. */
    Open,

    /** Something changed that a scan already under way may have read past. */
    Changed,

    /** Start over, whatever is under way. */
    Restart,
}

/** A scan WorkManager still holds. [attempts] above zero means it was started and then interrupted. */
internal data class HeldScan(val running: Boolean, val attempts: Int)

internal enum class ScanAction { Nothing, Start, QueueAfterRunning }

/**
 * What a request should do about the scans already held. At most one scan runs with one more
 * queued behind it, and nothing is ever left waiting behind an interrupted scan: on a phone that
 * kills background work, a scan interrupted ten times sat out a four-hour retry delay with every
 * later request (325 of them) queued behind it, and opening the app did nothing.
 */
internal fun scanAction(reason: ScanReason, held: List<HeldScan>): ScanAction {
    val (running, waiting) = held.partition { it.running }
    return when {
        reason == ScanReason.Restart -> ScanAction.Start
        running.isNotEmpty() ->
            if (reason == ScanReason.Changed && waiting.isEmpty()) ScanAction.QueueAfterRunning else ScanAction.Nothing
        // A single scan that has never started is about to, and will see whatever changed.
        waiting.size == 1 && waiting[0].attempts == 0 -> ScanAction.Nothing
        // Nothing held, or only what an interrupted scan left behind: start afresh, now.
        else -> ScanAction.Start
    }
}

/** Everything DustIt runs in the background. */
class BackgroundWork(context: Context, private val scope: CoroutineScope) {
    private val context = context.applicationContext
    private val workManager = WorkManager.getInstance(context)
    private val scanRequests = Mutex()

    /** Asks for a scan from the app; returns before it is queued. */
    fun scan(reason: ScanReason = ScanReason.Open) {
        scope.launch { requestScan(reason) }
    }

    private suspend fun requestScan(reason: ScanReason) = scanRequests.withLock {
        // Read through WorkManager's own queue (not the Flow), so this runs after it has reset
        // scans that were running when the process last died.
        val held = withContext(Dispatchers.IO) { workManager.getWorkInfosForUniqueWork(SCAN).get() }
            .filterNot { it.state.isFinished }
            .map { HeldScan(running = it.state == WorkInfo.State.RUNNING, attempts = it.runAttemptCount) }
        val policy = when (scanAction(reason, held)) {
            ScanAction.Nothing -> return@withLock
            ScanAction.Start -> ExistingWorkPolicy.REPLACE
            ScanAction.QueueAfterRunning -> ExistingWorkPolicy.APPEND_OR_REPLACE
        }
        // Not expedited: it starts at once anyway while the app is open, and a first scan runs
        // for longer than Android allows an expedited job, which it counts against the app (the
        // test phone used up its five overruns a day). An interrupted scan keeps its analysed
        // photos, so it retries soon rather than backing off for hours.
        val request = OneTimeWorkRequestBuilder<ScanWorker>()
            .setBackoffCriteria(BackoffPolicy.LINEAR, WorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
            .build()
        workManager.enqueueUniqueWork(SCAN, policy, request).await()
    }

    val isScanning: Flow<Boolean> = workManager.getWorkInfosForUniqueWorkFlow(SCAN)
        .map { infos -> infos.any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED } }
        .distinctUntilChanged()

    /** Scans with [MediaWatchService] whenever photos or videos are added or changed, even with the app closed. */
    fun watchForNewMedia(enabled: Boolean) = MediaWatchService.watch(context, enabled)

    fun scheduleReminders(enabled: Boolean) {
        if (enabled) {
            val request = PeriodicWorkRequestBuilder<ReminderWorker>(7, TimeUnit.DAYS)
                .setInitialDelay(7, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .build()
            workManager.enqueueUniquePeriodicWork(REMINDERS, ExistingPeriodicWorkPolicy.KEEP, request)
        } else {
            workManager.cancelUniqueWork(REMINDERS)
        }
    }
}
