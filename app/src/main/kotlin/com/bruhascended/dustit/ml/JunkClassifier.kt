package com.bruhascended.dustit.ml

import android.content.Context
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

data class Prediction(val category: JunkCategory, val score: Float) {
    companion object {
        fun fromScores(scores: FloatArray): Prediction {
            require(scores.size == JunkCategory.entries.size) { "Expected ${JunkCategory.entries.size} scores, got ${scores.size}" }
            val best = scores.indices.maxBy { scores[it] }
            return Prediction(JunkCategory.entries[best], scores[best])
        }
    }
}

/**
 * MobileNetV3-Small classifier (from OrganizoCV) that sorts a photo into one of [JunkCategory].
 *
 * Input is a 224x224 RGB image as float32 in 0..255; the model rescales internally.
 * Not thread-safe: use one instance per thread, and close it when done.
 */
class JunkClassifier(context: Context, numThreads: Int = DEFAULT_THREADS) : Closeable {

    private val interpreter = Interpreter(
        loadModel(context),
        Interpreter.Options().setNumThreads(numThreads),
    )
    private val input: ByteBuffer = ByteBuffer
        .allocateDirect(Float.SIZE_BYTES * INPUT_SIZE * INPUT_SIZE * 3)
        .order(ByteOrder.nativeOrder())
    private val rgb = FloatArray(INPUT_SIZE * INPUT_SIZE * 3)
    private val output = Array(1) { FloatArray(JunkCategory.entries.size) }

    init {
        val inShape = interpreter.getInputTensor(0).shape()
        val outShape = interpreter.getOutputTensor(0).shape()
        check(inShape.contentEquals(intArrayOf(1, INPUT_SIZE, INPUT_SIZE, 3))) { "Unexpected input shape ${inShape.contentToString()}" }
        check(outShape.contentEquals(intArrayOf(1, JunkCategory.entries.size))) { "Unexpected output shape ${outShape.contentToString()}" }
    }

    /** [pixels] are ARGB, row-major, [INPUT_SIZE]x[INPUT_SIZE] (see [loadModelInput]). */
    fun classify(pixels: IntArray): Prediction {
        require(pixels.size == INPUT_SIZE * INPUT_SIZE) { "Expected ${INPUT_SIZE}x$INPUT_SIZE pixels" }
        packRgb(pixels, rgb)
        input.rewind()
        // One bulk copy. Putting values one at a time through the buffer view measured ~9 ms per
        // image on a Snapdragon 8s Gen 4, twice the cost of the inference itself.
        input.asFloatBuffer().put(rgb)
        interpreter.run(input, output)
        return Prediction.fromScores(output[0])
    }

    override fun close() = interpreter.close()

    companion object {
        const val INPUT_SIZE = 224

        private const val MODEL_ASSET = "junk_classifier.tflite"
        /**
         * Measured on a Snapdragon 8s Gen 4: 4.6 ms per image with 1, 2 or 4 threads. The model is
         * too small to benefit, so one thread leaves the other cores to the decoders.
         */
        const val DEFAULT_THREADS = 1

        private fun loadModel(context: Context): MappedByteBuffer =
            context.assets.openFd(MODEL_ASSET).use { fd ->
                FileInputStream(fd.fileDescriptor).use { stream ->
                    stream.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
                }
            }
    }
}

/** Unpacks ARGB pixels into interleaved R, G, B floats in 0..255, the layout the model expects. */
internal fun packRgb(pixels: IntArray, out: FloatArray) {
    var o = 0
    for (p in pixels) {
        out[o++] = ((p shr 16) and 0xFF).toFloat()
        out[o++] = ((p shr 8) and 0xFF).toFloat()
        out[o++] = (p and 0xFF).toFloat()
    }
}
