package com.bruhascended.dustit.cleanup

import com.bruhascended.dustit.data.MediaKind
import com.bruhascended.dustit.data.MediaRow
import com.bruhascended.dustit.ml.Confidence
import com.bruhascended.dustit.ml.JunkCategory
import com.bruhascended.dustit.whatsapp.HiddenFolder
import com.bruhascended.dustit.whatsapp.WhatsAppLayout

data class ReviewItem(
    val id: Long,
    val kind: MediaKind,
    val sizeBytes: Long,
    val durationMs: Long,
    val dateTaken: Long,
    /** Selected for trash unless the user says otherwise. */
    val preselected: Boolean,
    /** The keeper of a similar-photo set. */
    val isBest: Boolean = false,
    /** Set for files from WhatsApp's hidden folders; they're read and trashed through it. */
    val documentUri: String? = null,
)

enum class SectionKind {
    Likely, Maybe, OldScreenshots, RecentScreenshots, LargeVideos, SimilarSet,

    /** WhatsApp's copies of sent photos whose original is still in the gallery. */
    SentCopies,
    Photos,

    /** Largest first. */
    Videos,
    Stickers,
}

data class ReviewSection(val key: String, val kind: SectionKind, val items: List<ReviewItem>)

data class BucketSummary(
    val bucket: Bucket,
    val preselectedCount: Int,
    val preselectedBytes: Long,
    val optionalCount: Int,
    val optionalBytes: Long,
    /** Number of similar-photo sets; 0 for other buckets. */
    val setCount: Int,
    val previews: List<ReviewItem>,
) {
    /** What the card leads with: the suggested amount, or everything when nothing is suggested. */
    val headlineCount: Int get() = if (preselectedCount > 0) preselectedCount else optionalCount
    val headlineBytes: Long get() = if (preselectedCount > 0) preselectedBytes else optionalBytes
}

data class CleanupOverview(
    /** Buckets with anything in them, biggest first. */
    val summaries: List<BucketSummary>,
    /** Size of everything pre-selected anywhere, counting an item once even if it's in several buckets. */
    val reclaimableBytes: Long,
)

/**
 * The single source of truth for what each bucket contains and what's pre-selected. Pure, so
 * the home cards, review screen and reminders can never disagree.
 */
object CleanupRules {
    const val SCREENSHOT_SUGGEST_AFTER_MS = 30L * 24 * 60 * 60 * 1000
    const val LARGE_VIDEO_BYTES = 50L * 1024 * 1024
    private const val PREVIEW_COUNT = 4

    fun isScreenshot(relativePath: String) = relativePath.contains("screenshot", ignoreCase = true)

    /** Shot on this phone's camera (some phones also keep screenshots under DCIM; those don't count). */
    fun isCameraRoll(relativePath: String) =
        relativePath.startsWith("DCIM/", ignoreCase = true) && !isScreenshot(relativePath)

    /**
     * Chats, memes and forwards reach a phone through apps or screenshots; the camera can't
     * produce them. On a real 18,815-photo library the model still put 2.9% of camera photos in
     * these classes at 0.9+, so camera-roll photos are shown for review but never pre-selected.
     */
    private val neverFromCamera = setOf(JunkCategory.Chats, JunkCategory.Memes, JunkCategory.Greetings)

    fun sections(bucket: Bucket, rows: List<MediaRow>, now: Long): List<ReviewSection> = when (bucket) {
        is Bucket.Category -> {
            val (likely, maybe) = rows
                .filter { it.kind == MediaKind.Image && it.category == bucket.category && (it.score ?: 0f) >= Confidence.MAYBE }
                .sortedByDescending { it.dateTaken }
                .partition {
                    it.score!! >= Confidence.LIKELY &&
                        !(bucket.category in neverFromCamera && isCameraRoll(it.relativePath))
                }
            listOfNotNull(
                section("likely", SectionKind.Likely, likely.map { it.toItem(preselected = true) }),
                section("maybe", SectionKind.Maybe, maybe.map { it.toItem(preselected = false) }),
            )
        }

        Bucket.Screenshots -> {
            val (old, recent) = rows
                .filter { it.kind == MediaKind.Image && isScreenshot(it.relativePath) }
                .sortedByDescending { it.dateTaken }
                .partition { now - it.dateTaken >= SCREENSHOT_SUGGEST_AFTER_MS }
            listOfNotNull(
                section("old", SectionKind.OldScreenshots, old.map { it.toItem(preselected = true) }),
                section("recent", SectionKind.RecentScreenshots, recent.map { it.toItem(preselected = false) }),
            )
        }

        Bucket.LargeVideos -> listOfNotNull(
            section(
                "videos",
                SectionKind.LargeVideos,
                rows.filter { it.kind == MediaKind.Video && it.size >= LARGE_VIDEO_BYTES }
                    .sortedByDescending { it.size }
                    .map { it.toItem(preselected = false) },
            ),
        )

        Bucket.Similar -> similarSets(rows)
            .map { set ->
                ReviewSection(
                    key = "set-${set.groupId}",
                    kind = SectionKind.SimilarSet,
                    items = listOf(set.best.toItem(preselected = false, isBest = true)) +
                        set.rest.sortedByDescending { it.dateTaken }.map { it.toItem(preselected = true) },
                )
            }
            .sortedByDescending { set -> set.items.maxOf { it.dateTaken } }

        Bucket.WhatsAppVideos -> listOfNotNull(
            section(
                "videos",
                SectionKind.Videos,
                rows.filter { it.kind == MediaKind.Video && WhatsAppLayout.isReceivedMedia(it.relativePath) }
                    .largestFirst()
                    .map { it.toItem(preselected = false) },
            ),
        )

        Bucket.WhatsAppSent -> {
            val sent = rows.filter { WhatsAppLayout.hiddenFolderOf(it.relativePath) == HiddenFolder.Sent }
            val (photos, videos) = sent.partition { it.kind == MediaKind.Image }
            val inGallery = sentCopiesInGallery(rows)
            val (copies, onlyHere) = photos.partition { it.id in inGallery }
            listOfNotNull(
                section("copies", SectionKind.SentCopies, copies.newestFirst().map { it.toItem(preselected = true) }),
                section("photos", SectionKind.Photos, onlyHere.newestFirst().map { it.toItem(preselected = false) }),
                section("videos", SectionKind.Videos, videos.largestFirst().map { it.toItem(preselected = false) }),
            )
        }

        Bucket.WhatsAppPrivate -> {
            val (photos, videos) = rows
                .filter { WhatsAppLayout.hiddenFolderOf(it.relativePath) == HiddenFolder.Private }
                .partition { it.kind == MediaKind.Image }
            listOfNotNull(
                section("photos", SectionKind.Photos, photos.newestFirst().map { it.toItem(preselected = false) }),
                section("videos", SectionKind.Videos, videos.largestFirst().map { it.toItem(preselected = false) }),
            )
        }

        Bucket.WhatsAppVideoNotes -> listOfNotNull(
            section(
                "videos",
                SectionKind.Videos,
                rows.filter { WhatsAppLayout.hiddenFolderOf(it.relativePath) == HiddenFolder.VideoNotes }
                    .largestFirst()
                    .map { it.toItem(preselected = false) },
            ),
        )

        Bucket.WhatsAppStickers -> listOfNotNull(
            section(
                "stickers",
                SectionKind.Stickers,
                rows.filter { WhatsAppLayout.hiddenFolderOf(it.relativePath) == HiddenFolder.Stickers }
                    .newestFirst()
                    .map { it.toItem(preselected = false) },
            ),
        )
    }

    private class SimilarSet(val groupId: Long, val best: MediaRow, val rest: List<MediaRow>)

    private fun similarSets(rows: List<MediaRow>): List<SimilarSet> = rows
        .filter { it.similarGroup != null }
        .groupBy { it.similarGroup!! }
        .filterValues { it.size >= 2 }
        .map { (groupId, members) ->
            // Keep the biggest original; among equal sizes (bursts), the sharpest shot. Files from
            // hidden folders have no recorded dimensions, so a gallery original always beats them.
            val best = members.maxWith(
                compareBy<MediaRow>({ it.width.toLong() * it.height }, { it.sharpness ?: 0f }, { it.size }, { it.id }),
            )
            SimilarSet(groupId, best, members.filter { it.id != best.id })
        }

    /**
     * Ids of WhatsApp "Sent" photos that are an extra copy of a photo the gallery still shows:
     * the photo kept from their similar set lives outside WhatsApp's hidden folders. A Sent photo
     * whose original was deleted is never in this set, so the only copy is never suggested here.
     */
    private fun sentCopiesInGallery(rows: List<MediaRow>): Set<Long> = similarSets(rows)
        .filter { WhatsAppLayout.hiddenFolderOf(it.best.relativePath) == null }
        .flatMap { set -> set.rest.filter { WhatsAppLayout.hiddenFolderOf(it.relativePath) == HiddenFolder.Sent } }
        .mapTo(HashSet()) { it.id }

    private fun List<MediaRow>.newestFirst() = sortedByDescending { it.dateTaken }

    private fun List<MediaRow>.largestFirst() = sortedByDescending { it.size }

    fun overview(rows: List<MediaRow>, now: Long): CleanupOverview {
        val reclaimable = HashMap<Long, Long>()
        val summaries = Bucket.all.mapNotNull { bucket ->
            val sections = sections(bucket, rows, now)
            val items = sections.flatMap { it.items }
            if (items.isEmpty()) return@mapNotNull null
            val (pre, optional) = items.partition { it.preselected }
            pre.forEach { reclaimable[it.id] = it.sizeBytes }
            BucketSummary(
                bucket = bucket,
                preselectedCount = pre.size,
                preselectedBytes = pre.sumOf { it.sizeBytes },
                optionalCount = optional.size,
                optionalBytes = optional.sumOf { it.sizeBytes },
                setCount = if (bucket == Bucket.Similar) sections.size else 0,
                previews = pre.ifEmpty { optional }.take(PREVIEW_COUNT),
            )
        }
        return CleanupOverview(
            summaries = summaries.sortedByDescending { it.headlineBytes },
            reclaimableBytes = reclaimable.values.sum(),
        )
    }

    private fun section(key: String, kind: SectionKind, items: List<ReviewItem>) =
        if (items.isEmpty()) null else ReviewSection(key, kind, items)

    private fun MediaRow.toItem(preselected: Boolean, isBest: Boolean = false) = ReviewItem(
        id = id,
        kind = kind,
        sizeBytes = size,
        durationMs = durationMs,
        dateTaken = dateTaken,
        preselected = preselected,
        isBest = isBest,
        documentUri = documentUri,
    )
}
