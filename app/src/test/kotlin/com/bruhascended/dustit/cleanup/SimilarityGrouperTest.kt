package com.bruhascended.dustit.cleanup

import com.bruhascended.dustit.data.SimilarityInput
import com.bruhascended.dustit.ml.FeatureExtractor
import com.bruhascended.dustit.ml.JunkCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SimilarityGrouperTest {
    private val day = 24L * 60 * 60 * 1000

    private fun tiny(f: (x: Int, y: Int) -> Int) = IntArray(256) { f(it % 16, it / 16).coerceIn(0, 255) }

    private fun input(
        id: Long,
        pixels: IntArray,
        at: Long,
        width: Int = 3000,
        height: Int = 4000,
        category: JunkCategory = JunkCategory.Other,
        path: String = "DCIM/Camera/",
    ) = SimilarityInput(
        id = id,
        category = category,
        relativePath = path,
        dateTaken = at,
        width = width,
        height = height,
        averageHash = FeatureExtractor.averageHash(pixels),
        tiny = ByteArray(256) { pixels[it].toByte() },
        sharpness = 0f,
    )

    private val diagonal = tiny { x, y -> (x + y) * 8 }
    private val rings = tiny { x, y -> ((x - 8) * (x - 8) + (y - 8) * (y - 8)) * 2 }
    private val stripes = tiny { x, _ -> if (x % 4 < 2) 200 else 30 }

    private fun noisy(base: IntArray, amplitude: Int, seed: Int): IntArray {
        val random = Random(seed)
        return IntArray(base.size) { (base[it] + random.nextInt(-amplitude, amplitude + 1)).coerceIn(0, 255) }
    }

    @Test
    fun `re-saved copies are grouped however far apart in time`() {
        val groups = SimilarityGrouper.group(
            listOf(
                input(1, diagonal, at = 0),
                input(2, noisy(diagonal, 4, seed = 1), at = 300 * day),
                input(3, rings, at = 100 * day),
            ),
        )
        assertEquals(listOf(listOf(1L, 2L)), groups)
    }

    @Test
    fun `burst shots are grouped only when taken close together`() {
        val retake = noisy(diagonal, 38, seed = 7)
        val corr = SimilarityGrouper.correlation(candidate(diagonal), candidate(retake))
        assertTrue("precondition: a retake is similar but not a duplicate (corr=$corr)", corr in 0.90..0.95)

        val close = SimilarityGrouper.group(listOf(input(1, diagonal, at = 0), input(2, retake, at = 20_000)))
        val apart = SimilarityGrouper.group(listOf(input(1, diagonal, at = 0), input(2, retake, at = day)))
        assertEquals(listOf(listOf(1L, 2L)), close)
        assertEquals(emptyList<List<Long>>(), apart)
    }

    @Test
    fun `different shots taken together stay apart`() {
        val groups = SimilarityGrouper.group(
            listOf(input(1, diagonal, at = 0), input(2, rings, at = 1_000), input(3, stripes, at = 2_000)),
        )
        assertEquals(emptyList<List<Long>>(), groups)
    }

    @Test
    fun `blank frames are never grouped`() {
        val blank = tiny { _, _ -> 250 }
        val groups = SimilarityGrouper.group(listOf(input(1, blank, at = 0), input(2, blank, at = 1_000)))
        assertEquals(emptyList<List<Long>>(), groups)
    }

    @Test
    fun `different aspect ratios are not duplicates`() {
        val groups = SimilarityGrouper.group(
            listOf(input(1, diagonal, at = 0), input(2, diagonal, at = 1_000, width = 4000, height = 3000)),
        )
        assertEquals(emptyList<List<Long>>(), groups)
    }

    @Test
    fun `groups are transitive`() {
        val groups = SimilarityGrouper.group(
            listOf(
                input(1, diagonal, at = 0),
                input(2, noisy(diagonal, 3, seed = 2), at = 5_000),
                input(3, noisy(diagonal, 3, seed = 3), at = 10_000),
            ),
        )
        assertEquals(listOf(listOf(1L, 2L, 3L)), groups)
    }

    @Test
    fun `text-heavy images are never matched by appearance`() {
        // Regression: distinct chats and document pages share a layout and looked identical at 16x16.
        val chats = SimilarityGrouper.group(
            listOf(
                input(1, diagonal, at = 0, category = JunkCategory.Chats),
                input(2, diagonal, at = 1_000, category = JunkCategory.Chats),
            ),
        )
        val pages = SimilarityGrouper.group(
            listOf(
                input(1, diagonal, at = 0, category = JunkCategory.Documents),
                input(2, diagonal, at = 1_000, category = JunkCategory.Documents),
            ),
        )
        val screenshots = SimilarityGrouper.group(
            listOf(
                input(1, diagonal, at = 0, path = "Pictures/Screenshots/"),
                input(2, diagonal, at = 1_000, path = "Pictures/Screenshots/"),
            ),
        )
        assertEquals(emptyList<List<Long>>(), chats + pages + screenshots)
    }

    @Test
    fun `exact duplicates are grouped for any content and merge with appearance matches`() {
        val groups = SimilarityGrouper.group(
            items = listOf(
                input(1, diagonal, at = 0),
                input(2, noisy(diagonal, 3, seed = 4), at = 5_000),
                input(3, rings, at = 0, category = JunkCategory.Chats),
            ),
            // 2 has a byte-identical copy 9 (e.g. a video or unanalysed file); 3 and 8 are identical chats.
            exactDuplicates = listOf(listOf(2L, 9L), listOf(3L, 8L)),
        )
        assertEquals(listOf(listOf(1L, 2L, 9L), listOf(3L, 8L)), groups)
    }

    private fun candidate(pixels: IntArray) = SimilarityGrouper.Candidate.of(input(0, pixels, at = 0))!!
}
