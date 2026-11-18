package com.bruhascended.dustit.reminders

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.bruhascended.dustit.appContainer
import com.bruhascended.dustit.cleanup.BucketSummary
import com.bruhascended.dustit.cleanup.CleanupOverview
import com.bruhascended.dustit.cleanup.CleanupRules
import com.bruhascended.dustit.permissions.MediaAccess
import com.bruhascended.dustit.permissions.mediaAccess
import kotlinx.coroutines.flow.first

/** Weekly: posts one notification per bucket the user follows that has something to clean. */
class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (applicationContext.mediaAccess() != MediaAccess.Full) return Result.success()
        val container = applicationContext.appContainer
        val followed = container.settings.reminderBuckets.first()
        if (followed.isEmpty()) return Result.success()

        val overview = CleanupRules.overview(container.mediaRepository.rowsOnce(), System.currentTimeMillis())
        due(overview, followed).forEach { ReminderNotifications.post(applicationContext, it) }
        return Result.success()
    }

    companion object {
        /** Followed buckets that currently have something to show. */
        fun due(overview: CleanupOverview, followed: Set<String>): List<BucketSummary> =
            overview.summaries.filter { it.bucket.key in followed && it.headlineCount > 0 }
    }
}
