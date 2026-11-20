package com.bruhascended.dustit.whatsapp

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.bruhascended.dustit.MainActivity
import com.bruhascended.dustit.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.concurrent.atomic.AtomicLong

/**
 * Keeps DustIt running while it moves, restores or deletes files in WhatsApp's hidden folders,
 * and shows how far it got. Each file costs tens of milliseconds (Android re-indexes it), so
 * thousands take minutes, and OxygenOS freezes an app 5 to 11 s after the user leaves it: without
 * a foreground service a big batch would stop part-way until the app was next opened.
 *
 * The work runs in the caller's coroutine ([keepRunning]); the service only holds the process in
 * the foreground.
 */
class HiddenFilesService : Service() {

    enum class Task(@param:StringRes val title: Int) {
        Trash(R.string.notification_files_trash),
        Restore(R.string.notification_files_restore),
        Delete(R.string.notification_files_delete),
    }

    private class Progress(val task: Task, val done: Int, val total: Int)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var updates: Job? = null
    private var lastStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Required after startForegroundService even if the work has already finished.
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(current()), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        lastStartId = startId
        if (updates == null) {
            updates = scope.launch {
                running.collect { jobs ->
                    if (jobs.isEmpty()) {
                        // By start id, so a batch that started meanwhile keeps the service.
                        if (stopSelfResult(lastStartId)) ServiceCompat.stopForeground(this@HiddenFilesService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    } else {
                        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(current()))
                        delay(UPDATE_MS) // the system drops notification updates that come faster
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /** The batch started first; null once everything is done. */
    private fun current(): Progress? = running.value.minByOrNull { it.key }?.value

    private fun notification(progress: Progress?) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_stat_dustit)
        .setContentTitle(getString(progress?.task?.title ?: Task.Trash.title))
        .apply {
            if (progress != null) {
                val numbers = NumberFormat.getIntegerInstance()
                setContentText(getString(R.string.notification_files_progress, numbers.format(progress.done), numbers.format(progress.total)))
                setProgress(progress.total, progress.done, false)
            }
        }
        .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setSilent(true)
        .build()

    companion object {
        private const val CHANNEL_ID = "hidden_files"
        private const val NOTIFICATION_ID = 2
        private const val UPDATE_MS = 500L

        /**
         * Smaller batches take about 4 s at four renames in flight (42 ms each on the test phone),
         * so they finish before the app could be frozen and don't flash a notification.
         */
        const val MIN_FILES = 100

        /** Batches in progress, by start order. Usually one; restore and delete can overlap a trash. */
        private val running = MutableStateFlow<Map<Long, Progress>>(emptyMap())
        private val nextId = AtomicLong()

        fun createChannel(context: Context) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_files),
                NotificationManager.IMPORTANCE_LOW,
            )
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        /**
         * Runs [block] with DustIt held in the foreground when [total] files is a big batch.
         * [block] reports files done through the function it's given.
         */
        suspend fun <T> keepRunning(context: Context, task: Task, total: Int, block: suspend (report: (Int) -> Unit) -> T): T {
            if (total < MIN_FILES) return block {}
            val id = nextId.incrementAndGet()
            running.update { it + (id to Progress(task, 0, total)) }
            try {
                ContextCompat.startForegroundService(context, Intent(context, HiddenFilesService::class.java))
            } catch (_: IllegalStateException) {
                // Started while DustIt was already in the background (Android 12+ refuses that).
                // The work still runs; it just isn't protected from being frozen.
            }
            try {
                return block { done -> running.update { jobs -> if (id in jobs) jobs + (id to Progress(task, done, total)) else jobs } }
            } finally {
                running.update { it - id }
            }
        }
    }
}
