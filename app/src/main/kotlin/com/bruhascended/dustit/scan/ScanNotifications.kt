package com.bruhascended.dustit.scan

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import com.bruhascended.dustit.MainActivity
import com.bruhascended.dustit.R
import java.text.NumberFormat

object ScanNotifications {
    private const val CHANNEL_ID = "scan"
    private const val NOTIFICATION_ID = 1

    fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_scan),
            NotificationManager.IMPORTANCE_LOW,
        )
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun foregroundInfo(context: Context, done: Int, total: Int): ForegroundInfo {
        val openApp = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val numbers = NumberFormat.getIntegerInstance()
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_dustit)
            .setContentTitle(context.getString(R.string.notification_scan_title))
            .apply {
                if (total > 0) {
                    setContentText(context.getString(R.string.notification_scan_progress, numbers.format(done), numbers.format(total)))
                }
            }
            .setProgress(total, done, total == 0)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }
}
