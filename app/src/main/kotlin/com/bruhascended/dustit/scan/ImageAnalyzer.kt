package com.bruhascended.dustit.scan

import android.content.Context
import android.graphics.Bitmap
import com.bruhascended.dustit.data.AnalysisEntity
import com.bruhascended.dustit.ml.FeatureExtractor
import com.bruhascended.dustit.ml.JunkClassifier
import java.io.Closeable

/** Runs the classifier and the similarity features on one decoded image. Not thread-safe. */
class ImageAnalyzer(context: Context) : Closeable {
    private val classifier = JunkClassifier(context)
    private val pixels = IntArray(SIZE * SIZE)

    /** [bitmap] must be [JunkClassifier.INPUT_SIZE]-square ARGB_8888 (see `loadModelInput`). */
    fun analyze(mediaId: Long, bitmap: Bitmap): AnalysisEntity {
        bitmap.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
        val prediction = classifier.classify(pixels)
        val features = FeatureExtractor.extract(pixels, SIZE)
        return AnalysisEntity(
            mediaId = mediaId,
            category = prediction.category,
            score = prediction.score,
            sharpness = features.sharpness,
            averageHash = features.averageHash,
            tiny = features.tiny,
            version = VERSION,
        )
    }

    override fun close() = classifier.close()

    companion object {
        private const val SIZE = JunkClassifier.INPUT_SIZE

        /** Bump when the model, its preprocessing or the features change; older rows are then re-analysed. */
        const val VERSION = 6

        fun unreadable(mediaId: Long) = AnalysisEntity(
            mediaId = mediaId,
            category = null,
            score = 0f,
            sharpness = 0f,
            averageHash = 0L,
            tiny = null,
            version = VERSION,
        )
    }
}
