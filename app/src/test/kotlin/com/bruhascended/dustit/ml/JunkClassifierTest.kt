package com.bruhascended.dustit.ml

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

class JunkClassifierTest {

    @Test
    fun `enum order matches the labels embedded in the model`() {
        // TFLite metadata appends a zip with the label file to the flatbuffer.
        val model = File("src/main/assets/junk_classifier.tflite")
        val labels = ZipFile(model).use { zip ->
            zip.getInputStream(zip.getEntry("label_names.txt")).bufferedReader().readLines().filter { it.isNotBlank() }
        }
        assertEquals(labels, JunkCategory.entries.map { it.modelLabel })
    }

    @Test
    fun `pixels are packed as RGB floats in 0 to 255`() {
        val pixels = intArrayOf(0xFF102030.toInt(), 0x80FFFFFF.toInt())
        val out = FloatArray(6)
        packRgb(pixels, out)
        assertArrayEquals(floatArrayOf(16f, 32f, 48f, 255f, 255f, 255f), out, 0f)
    }

    @Test
    fun `prediction picks the highest score`() {
        val scores = FloatArray(JunkCategory.entries.size) { 0.01f }.apply { this[JunkCategory.Chats.ordinal] = 0.92f }
        assertEquals(Prediction(JunkCategory.Chats, 0.92f), Prediction.fromScores(scores))
    }
}
