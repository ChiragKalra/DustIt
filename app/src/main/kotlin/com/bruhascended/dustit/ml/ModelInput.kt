package com.bruhascended.dustit.ml

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri

/**
 * Loads [uri] as a [JunkClassifier.INPUT_SIZE]-square ARGB_8888 bitmap, or null if it can't be read.
 *
 * Decodes the original at a reduced sample size, which is cheap for JPEG (it scales while
 * decoding). Deliberately avoids `ContentResolver.loadThumbnail`: MediaStore saves a ~70 KB JPEG
 * for every thumbnail it generates, so scanning a whole library that way would add gigabytes to
 * the phone we're trying to clean.
 *
 * The image is stretched (not cropped) to a square, matching how the model was trained and how
 * the original app fed it.
 */
fun loadModelInput(resolver: ContentResolver, uri: Uri): Bitmap? {
    val size = JunkClassifier.INPUT_SIZE
    val decoded = try {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetSampleSize(sampleSize(info.size.width, info.size.height, size))
        }
    } catch (_: Exception) {
        return null // deleted mid-scan, corrupt, or an unsupported format
    }
    val argb = if (decoded.config == Bitmap.Config.ARGB_8888) {
        decoded
    } else {
        decoded.copy(Bitmap.Config.ARGB_8888, false).also { decoded.recycle() } ?: return null
    }
    val scaled = Bitmap.createScaledBitmap(argb, size, size, true)
    if (scaled !== argb) argb.recycle()
    return scaled
}

private val RAW_MIME_TYPES = setOf(
    "image/x-adobe-dng",
    "image/x-canon-cr2",
    "image/x-canon-cr3",
    "image/x-nikon-nef",
    "image/x-nikon-nrw",
    "image/x-sony-arw",
    "image/x-panasonic-rw2",
    "image/x-olympus-orf",
    "image/x-fuji-raf",
    "image/x-pentax-pef",
    "image/x-samsung-srw",
)

/**
 * Camera raw files, by the type MediaStore records. They are never analysed: a raw file is
 * someone's deliberate original, and on a real library the model labelled them "Memes" at up to
 * 0.89, while "Similar photos" would pair each with its JPEG twin and pre-select one for deletion.
 *
 * This can't be decided from the decoder: ImageDecoder decodes a raw file's embedded JPEG preview
 * and reports it as `image/jpeg`.
 */
fun isRawPhoto(mimeType: String): Boolean = mimeType.lowercase() in RAW_MIME_TYPES

/** Largest power-of-two reduction that keeps both sides at least [target] px (1 if already smaller). */
internal fun sampleSize(width: Int, height: Int, target: Int): Int =
    Integer.highestOneBit((minOf(width, height) / target).coerceAtLeast(1))
