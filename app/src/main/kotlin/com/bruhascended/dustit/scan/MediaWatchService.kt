package com.bruhascended.dustit.scan

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import com.bruhascended.dustit.appContainer
import com.bruhascended.dustit.data.MediaStoreSource
import com.bruhascended.dustit.permissions.MediaAccess
import com.bruhascended.dustit.permissions.mediaAccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Far above the ids WorkManager hands out (counting up from 0), which share one space before Android 14. */
private const val JOB_ID = 0x44757374

/** Let a burst of new photos (or a big copy) settle before scanning once. */
private const val SETTLE_MS = 10_000L
private const val MAX_WAIT_MS = 120_000L

/**
 * Scans whenever MediaStore photos or videos are added or changed, even with the app closed.
 *
 * A plain JobScheduler job rather than WorkManager work: on every cold start WorkManager found
 * job records left by finished work, rescheduled everything it held, and so cancelled the very
 * job that had just woken the app. A change made with the app closed never led to a scan.
 *
 * The scan runs inside this job, not as a queued [ScanWorker], so the process has a running job
 * for as long as the scan takes. That is enough on stock Android. OxygenOS still stops a background
 * job about 5 s in (11 s from a cold start), so this scan only syncs and analyses the gallery;
 * WhatsApp's hidden folders and regrouping wait for the app to open. Anything still cut short is
 * picked up by the next scan.
 */
class MediaWatchService : JobService() {
    private var scan: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val container = appContainer
        scan = container.scope.launch {
            try {
                // WhatsApp's hidden folders and regrouping wait for the app to open: see Scanner.run.
                if (mediaAccess() == MediaAccess.Full) container.scanner.run(background = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("DustIt", "background scan failed", e)
            }
            // Triggers fire once. Scheduling the next watch before finishing this one is what
            // carries over changes that arrived during the scan.
            schedule(this@MediaWatchService)
            jobFinished(params, false)
        }
        return true // still scanning
    }

    /**
     * Android is stopping the job: it ran too long, or was frozen until it timed out. What the
     * scan saved is kept and the next scan finishes the rest. Keep watching, but let the next
     * change start that scan rather than a retry timer, which on a phone that freezes every
     * background scan would retry for ever.
     */
    override fun onStopJob(params: JobParameters): Boolean {
        scan?.cancel()
        if (watching) schedule(this)
        return false
    }

    companion object {
        /** False once the user turns background scans off, so stopping the job doesn't re-arm it. */
        @Volatile
        private var watching = true

        /** Does nothing if the watcher is already waiting or scanning, so a pending change isn't dropped. */
        fun watch(context: Context, enabled: Boolean) {
            watching = enabled
            val scheduler = context.getSystemService(JobScheduler::class.java)
            when {
                !enabled -> scheduler.cancel(JOB_ID)
                scheduler.getPendingJob(JOB_ID) == null -> schedule(context)
            }
        }

        private fun schedule(context: Context) {
            val descendants = JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, MediaWatchService::class.java))
                .addTriggerContentUri(JobInfo.TriggerContentUri(MediaStoreSource.images, descendants))
                .addTriggerContentUri(JobInfo.TriggerContentUri(MediaStoreSource.videos, descendants))
                .setTriggerContentUpdateDelay(SETTLE_MS)
                .setTriggerContentMaxDelay(MAX_WAIT_MS)
                .build()
            context.getSystemService(JobScheduler::class.java).schedule(job)
        }
    }
}

/** Content-triggered jobs don't survive a restart of the phone, so the watcher is set up again after one. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        val container = context.appContainer
        container.scope.launch {
            try {
                container.settings.applyToBackgroundWork()
            } finally {
                pending.finish()
            }
        }
    }
}
