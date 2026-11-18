package com.bruhascended.dustit.cleanup

import androidx.annotation.StringRes
import com.bruhascended.dustit.R
import com.bruhascended.dustit.ml.JunkCategory

/** One kind of cleanup suggestion: a home card and its review screen. */
sealed interface Bucket {
    /** Stable id used in navigation, settings and notifications. */
    val key: String

    @get:StringRes val title: Int

    @get:StringRes val description: Int

    /** Photos the classifier put in a clutter category. */
    data class Category(val category: JunkCategory) : Bucket {
        override val key = "category-${category.name}"
        override val title = category.title
        override val description = category.description
    }

    /** Sets of near-identical photos; everything but the best of each set is suggested. */
    data object Similar : Bucket {
        override val key = "similar"
        override val title = R.string.bucket_similar
        override val description = R.string.bucket_similar_desc
    }

    /** Anything saved in a Screenshots folder; older ones are suggested. */
    data object Screenshots : Bucket {
        override val key = "screenshots"
        override val title = R.string.bucket_screenshots
        override val description = R.string.bucket_screenshots_desc
    }

    /** Big videos. Never pre-selected: they're often the ones people care about most. */
    data object LargeVideos : Bucket {
        override val key = "large-videos"
        override val title = R.string.bucket_large_videos
        override val description = R.string.bucket_large_videos_desc
    }

    /** Files in WhatsApp's hidden folders. Only shown once the user has shared those folders. */
    sealed interface WhatsApp : Bucket

    /** WhatsApp's copy of everything sent from this phone; copies of photos still in the gallery are suggested. */
    data object WhatsAppSent : WhatsApp {
        override val key = "whatsapp-sent"
        override val title = R.string.bucket_wa_sent
        override val description = R.string.bucket_wa_sent_desc
    }

    /** Media from chats whose media is kept out of the gallery. */
    data object WhatsAppPrivate : WhatsApp {
        override val key = "whatsapp-private"
        override val title = R.string.bucket_wa_private
        override val description = R.string.bucket_wa_private_desc
    }

    data object WhatsAppVideoNotes : WhatsApp {
        override val key = "whatsapp-video-notes"
        override val title = R.string.bucket_wa_video_notes
        override val description = R.string.bucket_wa_video_notes_desc
    }

    data object WhatsAppStickers : WhatsApp {
        override val key = "whatsapp-stickers"
        override val title = R.string.bucket_wa_stickers
        override val description = R.string.bucket_wa_stickers_desc
    }

    companion object {
        val whatsApp: List<WhatsApp> = listOf(WhatsAppSent, WhatsAppPrivate, WhatsAppVideoNotes, WhatsAppStickers)

        val all: List<Bucket> = JunkCategory.clutter.map(::Category) + listOf(Similar, Screenshots, LargeVideos) + whatsApp

        fun fromKey(key: String?): Bucket? = all.firstOrNull { it.key == key }
    }
}
