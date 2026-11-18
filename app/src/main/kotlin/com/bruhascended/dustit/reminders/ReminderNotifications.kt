package com.bruhascended.dustit.reminders

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.text.format.Formatter
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.bruhascended.dustit.MainActivity
import com.bruhascended.dustit.R
import com.bruhascended.dustit.cleanup.Bucket
import com.bruhascended.dustit.cleanup.BucketSummary
import java.text.NumberFormat

object ReminderNotifications {
    const val CHANNEL_ID = "reminders"

    fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_reminders),
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    // areNotificationsEnabled() covers the Android 13+ permission and the per-app switch on older versions.
    @SuppressLint("MissingPermission")
    fun post(context: Context, summary: BucketSummary) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        val bucket = summary.bucket
        val count = NumberFormat.getIntegerInstance().format(summary.headlineCount)
        val items = context.resources.getQuantityString(
            when (bucket) {
                Bucket.LargeVideos -> R.plurals.video_count
                is Bucket.WhatsApp -> R.plurals.item_count // mixes photos, videos and stickers
                else -> R.plurals.photo_count
            },
            summary.headlineCount,
            count,
        )
        val text = context.getString(
            R.string.notification_reminder_text,
            items,
            Formatter.formatShortFileSize(context, summary.headlineBytes),
        )
        val open = PendingIntent.getActivity(
            context,
            // Request codes must differ per bucket: PendingIntents that differ only in extras are merged.
            bucket.key.hashCode(),
            MainActivity.openBucketIntent(context, bucket.key),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_dustit)
            .setContentTitle(context.getString(bucket.title))
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        manager.notify(bucket.key, 0, notification)
    }
}
