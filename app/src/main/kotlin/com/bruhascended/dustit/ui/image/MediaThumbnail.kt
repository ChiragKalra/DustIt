package com.bruhascended.dustit.ui.image

import android.media.MediaMetadataRetriever
import android.net.Uri
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.key.Keyer
import coil3.request.Options
import coil3.size.pxOrElse
import com.bruhascended.dustit.cleanup.ReviewItem
import com.bruhascended.dustit.data.MediaKind
import com.bruhascended.dustit.data.contentUri
import java.io.IOException
import android.util.Size as AndroidSize

/**
 * Coil model that loads through MediaStore's thumbnail cache. Used for video frames only:
 * MediaStore keeps a ~70 KB JPEG on disk for each thumbnail it makes, so photos are instead
 * decoded straight from the original at the size needed (see [mediaModel]).
 */
data class MediaThumbnail(val uri: Uri)

/**
 * Coil model for a video in a hidden folder. MediaStore has no thumbnail for those, so a frame is
 * read straight from the file (and kept only in Coil's memory cache).
 */
data class VideoFrame(val uri: Uri)

/** What to hand Coil to show an item at any size without growing the phone's storage. */
fun mediaModel(id: Long, kind: MediaKind, documentUri: String? = null): Any {
    val uri = contentUri(id, kind, documentUri)
    return when {
        kind == MediaKind.Image -> uri
        documentUri != null -> VideoFrame(uri)
        else -> MediaThumbnail(uri)
    }
}

fun mediaModel(item: ReviewItem): Any = mediaModel(item.id, item.kind, item.documentUri)

private const val FALLBACK_PX = 256

class MediaThumbnailFetcher(
    private val data: MediaThumbnail,
    private val options: Options,
) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val size = AndroidSize(
            options.size.width.pxOrElse { FALLBACK_PX },
            options.size.height.pxOrElse { FALLBACK_PX },
        )
        val bitmap = options.context.contentResolver.loadThumbnail(data.uri, size, null)
        return ImageFetchResult(image = bitmap.asImage(), isSampled = true, dataSource = DataSource.DISK)
    }

    class Factory : Fetcher.Factory<MediaThumbnail> {
        override fun create(data: MediaThumbnail, options: Options, imageLoader: ImageLoader): Fetcher =
            MediaThumbnailFetcher(data, options)
    }
}

class MediaThumbnailKeyer : Keyer<MediaThumbnail> {
    override fun key(data: MediaThumbnail, options: Options): String = "media-thumbnail:${data.uri}"
}

class VideoFrameFetcher(
    private val data: VideoFrame,
    private val options: Options,
) : Fetcher {
    override suspend fun fetch(): FetchResult {
        // Frames are scaled to fit inside the box, so ask for twice the cell to leave room for cropping.
        val width = options.size.width.pxOrElse { FALLBACK_PX } * 2
        val height = options.size.height.pxOrElse { FALLBACK_PX } * 2
        val frame = MediaMetadataRetriever().use { retriever ->
            retriever.setDataSource(options.context, data.uri)
            retriever.getScaledFrameAtTime(-1, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, width, height)
        } ?: throw IOException("No frame in ${data.uri}")
        return ImageFetchResult(image = frame.asImage(), isSampled = true, dataSource = DataSource.DISK)
    }

    class Factory : Fetcher.Factory<VideoFrame> {
        override fun create(data: VideoFrame, options: Options, imageLoader: ImageLoader): Fetcher =
            VideoFrameFetcher(data, options)
    }
}

class VideoFrameKeyer : Keyer<VideoFrame> {
    override fun key(data: VideoFrame, options: Options): String = "video-frame:${data.uri}"
}
