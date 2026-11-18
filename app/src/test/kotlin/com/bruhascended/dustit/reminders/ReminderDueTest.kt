package com.bruhascended.dustit.reminders

import com.bruhascended.dustit.cleanup.Bucket
import com.bruhascended.dustit.cleanup.BucketSummary
import com.bruhascended.dustit.cleanup.CleanupOverview
import com.bruhascended.dustit.ml.JunkCategory
import org.junit.Assert.assertEquals
import org.junit.Test

class ReminderDueTest {
    private fun summary(bucket: Bucket, pre: Int = 0, optional: Int = 0) =
        BucketSummary(bucket, pre, pre * 10L, optional, optional * 10L, setCount = 0, previews = emptyList())

    @Test
    fun `only followed buckets with something in them are due`() {
        val chats = Bucket.Category(JunkCategory.Chats)
        val overview = CleanupOverview(
            summaries = listOf(summary(chats, pre = 3), summary(Bucket.LargeVideos, optional = 2), summary(Bucket.Similar, pre = 5)),
            reclaimableBytes = 0,
        )
        val due = ReminderWorker.due(overview, followed = setOf(chats.key, Bucket.LargeVideos.key, Bucket.Screenshots.key))
        assertEquals(listOf(chats, Bucket.LargeVideos), due.map { it.bucket })
    }
}
