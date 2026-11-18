package com.bruhascended.dustit.ml

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelInputTest {
    @Test
    fun `raw photos are recognised by the type MediaStore records`() {
        assertEquals(true, isRawPhoto("image/x-adobe-dng"))
        assertEquals(true, isRawPhoto("IMAGE/X-SONY-ARW"))
        assertEquals(false, isRawPhoto("image/jpeg"))
        assertEquals(false, isRawPhoto("image/x-ms-bmp")) // "x-" alone doesn't mean raw
    }

    @Test
    fun `sample size is the largest power of two that keeps the short side at least the target`() {
        assertEquals(1, sampleSize(100, 80, 224))      // already small: never upsample-by-sampling
        assertEquals(1, sampleSize(447, 600, 224))     // 447 / 2 would drop below 224
        assertEquals(2, sampleSize(448, 600, 224))
        assertEquals(4, sampleSize(1600, 1200, 224))   // typical WhatsApp photo -> 400x300
        assertEquals(8, sampleSize(4000, 3000, 224))   // 12 MP -> 500x375
        assertEquals(16, sampleSize(8160, 6120, 224))  // 50 MP
        assertEquals(4, sampleSize(1272, 2772, 224))   // tall screenshot: the short side decides
    }
}
