package com.bruhascended.dustit.reminders

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.core.app.NotificationManagerCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.bruhascended.dustit.appContainer
import com.bruhascended.dustit.cleanup.Bucket
import com.bruhascended.dustit.cleanup.CleanupRules
import com.bruhascended.dustit.permissions.MediaAccess
import com.bruhascended.dustit.permissions.mediaAccess
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End to end on a real device: scan the device's photos, follow some buckets, run the real
 * worker, and check which reminders were posted. Skips if the device has no clutter at all.
 */
@RunWith(AndroidJUnit4::class)
class ReminderWorkerSmokeTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private val followed = mutableSetOf<String>()

    @Before
    fun ensureAccess() {
        val missing = listOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.POST_NOTIFICATIONS,
        ).filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            // Some phones (OnePlus/OPPO, Xiaomi) refuse grants from test automation unless a
            // developer-settings switch is on; in that case grant access in the app and re-run.
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            runCatching { missing.forEach { automation.grantRuntimePermission(context.packageName, it) } }
        }
        assumeTrue("needs full photo access; grant it in the app first", context.mediaAccess() == MediaAccess.Full)
        assumeTrue("needs notifications allowed", NotificationManagerCompat.from(context).areNotificationsEnabled())
    }

    @Test
    fun postsOneNotificationPerFollowedBucketWithContent() = runBlocking {
        val container = context.appContainer
        container.scanner.run { _, _ -> }
        val overview = CleanupRules.overview(container.mediaRepository.rowsOnce(), System.currentTimeMillis())
        val nonEmpty = overview.summaries.map { it.bucket }
        assumeTrue("needs some clutter on the device", nonEmpty.isNotEmpty())

        val expected = nonEmpty.take(2).map { it.key }.toSet()
        followed += expected
        Bucket.all.firstOrNull { it !in nonEmpty }?.let { followed += it.key } // followed but empty: no reminder
        followed.forEach { container.settings.setReminder(it, true) }
        notifications.cancelAll()

        val result = TestListenableWorkerBuilder<ReminderWorker>(context).build().doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        // notify() is asynchronous, so give the system a moment before reading what's showing.
        fun posted() = notifications.activeNotifications
            .filter { it.notification.channelId == ReminderNotifications.CHANNEL_ID }
            // Android adds its own summary when one app has several notifications showing.
            .filter { it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 }
            .map { it.tag }
            .toSet()
        val deadline = SystemClock.elapsedRealtime() + 3_000
        while (posted() != expected && SystemClock.elapsedRealtime() < deadline) delay(100)
        assertEquals(expected, posted())
    }

    @After
    fun tearDown() = runBlocking {
        followed.forEach { context.appContainer.settings.setReminder(it, false) }
        notifications.cancelAll()
    }
}
