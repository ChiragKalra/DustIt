package com.bruhascended.dustit.scan

import android.content.Context
import android.os.SystemClock
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.bruhascended.dustit.appContainer
import com.bruhascended.dustit.permissions.MediaAccess
import com.bruhascended.dustit.permissions.mediaAccess

private const val NOTIFICATION_THROTTLE_MS = 1_000L

class ScanWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // Never sync with limited access: MediaStore would report only the picked photos and the
        // index would be cut down to match.
        if (applicationContext.mediaAccess() != MediaAccess.Full) return Result.failure()

        var lastUpdate = 0L
        applicationContext.appContainer.scanner.run { done, total ->
            val now = SystemClock.elapsedRealtime()
            if (done == 0 || done == total || now - lastUpdate >= NOTIFICATION_THROTTLE_MS) {
                lastUpdate = now
                // Promotes the work to a foreground service on the first call, then just updates it.
                trySetForeground(ScanNotifications.foregroundInfo(applicationContext, done, total))
            }
        }
        return Result.success()
    }

    private suspend fun trySetForeground(info: ForegroundInfo) {
        try {
            setForeground(info)
        } catch (_: IllegalStateException) {
            // Android 12+ refuses foreground services started from the background. The scan still
            // runs; it just has no notification and may be stopped sooner by the system.
        }
    }
}
