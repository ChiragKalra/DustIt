package com.bruhascended.dustit.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FeatureExtractorTest {
    private val size = 224

    private fun image(gray: (x: Int, y: Int) -> Int) = IntArray(size * size) { i ->
        val v = gray(i % size, i / size).coerceIn(0, 255)
        (0xFF shl 24) or (v shl 16) or (v shl 8) or v
    }

    @Test
    fun `luminance uses Rec 601 weights`() {
        assertEquals(255, FeatureExtractor.luminance(0xFFFFFFFF.toInt()))
        assertEquals(0, FeatureExtractor.luminance(0xFF000000.toInt()))
        assertEquals(76, FeatureExtractor.luminance(0xFFFF0000.toInt()))
    }

    @Test
    fun `tiny thumbnail and hash follow the image layout`() {
        val features = FeatureExtractor.extract(image { x, _ -> if (x < size / 2) 255 else 0 }, size)
        val tiny = features.tiny.map { it.toInt() and 0xFF }
        for (y in 0 until 16) for (x in 0 until 16) {
            assertEquals("cell ($x,$y)", if (x < 8) 255 else 0, tiny[y * 16 + x])
        }
        // Bright left half of every 8-cell hash row.
        assertEquals(0x0F0F0F0F0F0F0F0FL, features.averageHash)
    }

    @Test
    fun `sharpness is zero when flat and drops when blurred`() {
        assertEquals(0f, FeatureExtractor.extract(image { _, _ -> 128 }, size).sharpness, 0f)
        val sharp = FeatureExtractor.extract(image { x, y -> if ((x / 4 + y / 4) % 2 == 0) 255 else 0 }, size).sharpness
        val soft = FeatureExtractor.extract(image { x, _ -> x }, size).sharpness
        assertTrue("checkerboard ($sharp) should be sharper than a gradient ($soft)", sharp > soft * 100)
    }
}
