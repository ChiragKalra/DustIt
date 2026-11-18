package com.bruhascended.dustit.cleanup

import com.bruhascended.dustit.data.SimilarityInput
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Groups near-identical photos.
 *
 * Byte-identical files ([ExactDuplicates][com.bruhascended.dustit.data.ExactDuplicates]) are
 * merged in as-is, for any content. Beyond that, **photo-like** images are matched by their 16x16
 * scan thumbnails, confirmed by normalised cross-correlation (NCC). Text-heavy images (chats,
 * documents, notices, memes, forwards, anything in a screenshots folder) are excluded from this:
 * different chats or pages share a layout and look identical at that scale. Candidates come from:
 * - **Duplicates** anywhere in the library: average hashes within [DUPLICATE_MAX_HASH_DISTANCE]
 *   bits, found without comparing every pair by bucketing on each byte of the hash (if two
 *   hashes differ in at most 7 bits, at least one of the 8 bytes is identical).
 * - **Bursts and retakes**: photos taken within [BURST_WINDOW_MS] of each other.
 *
 * Thresholds are first guesses and need tuning against real galleries.
 */
object SimilarityGrouper {
    const val DUPLICATE_MAX_HASH_DISTANCE = 6
    const val DUPLICATE_MIN_CORRELATION = 0.95
    const val BURST_WINDOW_MS = 60_000L
    const val BURST_MIN_CORRELATION = 0.90

    /** Near-flat thumbnails (blank screens, black frames) correlate with noise, so they're never grouped. */
    private const val MIN_CONTRAST = 6.0
    private const val MAX_LOG_ASPECT_DIFFERENCE = 0.05
    private const val BURST_MAX_NEIGHBOURS = 30
    /** Hash bytes shared by this many photos are degenerate (e.g. all-dark), not evidence of duplication. */
    private const val MAX_BUCKET_SIZE = 4_000
    private const val PIXELS = 16 * 16

    /** Whether appearance alone is trustworthy evidence that two images are the same shot. */
    fun isPhotoLike(input: SimilarityInput): Boolean =
        input.category?.isTextHeavy == false && !CleanupRules.isScreenshot(input.relativePath)

    /** Returns sets of 2+ ids that look like the same shot, merged with [exactDuplicates]. */
    fun group(items: List<SimilarityInput>, exactDuplicates: List<List<Long>> = emptyList()): List<List<Long>> {
        val usable = items
            .filter(::isPhotoLike)
            .mapNotNull { Candidate.of(it) }
            .filter { it.contrast >= MIN_CONTRAST }
        val sets = UnionFind()
        exactDuplicates.forEach { ids -> ids.zipWithNext(sets::union) }

        fun tryJoin(a: Int, b: Int, minCorrelation: Double) {
            val x = usable[a]
            val y = usable[b]
            if (sets.find(x.input.id) == sets.find(y.input.id)) return
            if (sameAspect(x, y) && correlation(x, y) >= minCorrelation) sets.union(x.input.id, y.input.id)
        }

        // Duplicates anywhere: bucket by each hash byte, then check the full hash and the thumbnails.
        for (byte in 0 until 8) {
            val buckets = HashMap<Int, MutableList<Int>>()
            usable.forEachIndexed { i, c -> buckets.getOrPut(((c.input.averageHash ushr (byte * 8)) and 0xFF).toInt()) { ArrayList() } += i }
            for (bucket in buckets.values) {
                if (bucket.size < 2 || bucket.size > MAX_BUCKET_SIZE) continue
                for (i in bucket.indices) for (j in i + 1 until bucket.size) {
                    val a = bucket[i]
                    val b = bucket[j]
                    val distance = java.lang.Long.bitCount(usable[a].input.averageHash xor usable[b].input.averageHash)
                    if (distance <= DUPLICATE_MAX_HASH_DISTANCE) tryJoin(a, b, DUPLICATE_MIN_CORRELATION)
                }
            }
        }

        // Bursts: neighbours in capture time.
        val byTime = usable.indices.sortedBy { usable[it].input.dateTaken }
        for (i in byTime.indices) {
            val a = byTime[i]
            var j = i + 1
            while (j < byTime.size && j - i <= BURST_MAX_NEIGHBOURS &&
                usable[byTime[j]].input.dateTaken - usable[a].input.dateTaken <= BURST_WINDOW_MS
            ) {
                tryJoin(a, byTime[j], BURST_MIN_CORRELATION)
                j++
            }
        }

        return sets.groups().filter { it.size >= 2 }.map { it.sorted() }.sortedBy { it.first() }
    }

    private fun sameAspect(a: Candidate, b: Candidate): Boolean {
        val x = a.input
        val y = b.input
        if (x.width <= 0 || x.height <= 0 || y.width <= 0 || y.height <= 0) return true
        val ratio = (x.width.toDouble() / x.height) / (y.width.toDouble() / y.height)
        return abs(ln(ratio)) <= MAX_LOG_ASPECT_DIFFERENCE
    }

    internal fun correlation(a: Candidate, b: Candidate): Double {
        var dot = 0L
        val x = a.input.tiny
        val y = b.input.tiny
        for (i in 0 until PIXELS) dot += (x[i].toInt() and 0xFF) * (y[i].toInt() and 0xFF)
        val covariance = dot - PIXELS * a.mean * b.mean
        return covariance / (a.norm * b.norm)
    }

    internal class Candidate private constructor(val input: SimilarityInput, val mean: Double, val norm: Double) {
        /** Standard deviation of the thumbnail, in 0..255 units. */
        val contrast: Double get() = norm / sqrt(PIXELS.toDouble())

        companion object {
            fun of(input: SimilarityInput): Candidate? {
                if (input.tiny.size != PIXELS) return null
                var sum = 0L
                var sumSq = 0L
                for (b in input.tiny) {
                    val v = b.toInt() and 0xFF
                    sum += v
                    sumSq += v * v
                }
                val mean = sum.toDouble() / PIXELS
                val norm = sqrt((sumSq - PIXELS * mean * mean).coerceAtLeast(0.0))
                return Candidate(input, mean, norm)
            }
        }
    }

    /** Disjoint sets over media ids; ids appear the first time they're unioned. */
    private class UnionFind {
        private val parent = HashMap<Long, Long>()

        fun find(x: Long): Long {
            var root = x
            while (true) {
                val up = parent[root] ?: break
                if (up == root) break
                root = up
            }
            var node = x
            while (node != root) {
                val next = parent[node] ?: break
                parent[node] = root
                node = next
            }
            return root
        }

        fun union(a: Long, b: Long) {
            val ra = find(a)
            val rb = find(b)
            parent.getOrPut(ra) { ra }
            parent.getOrPut(rb) { rb }
            if (ra != rb) parent[ra] = rb
        }

        fun groups(): Collection<List<Long>> = parent.keys.groupBy(::find).values
    }
}
