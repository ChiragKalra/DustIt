package com.bruhascended.dustit.ml

/** Cheap, model-free descriptors used to find near-identical photos and pick the best of a set. */
class ImageFeatures(
    /** 16x16 grayscale, row-major, values 0..255 stored as unsigned bytes. */
    val tiny: ByteArray,
    /** 8x8 average hash of [tiny]; bit `y * 8 + x` is set when that cell is brighter than the mean. */
    val averageHash: Long,
    /** Variance of the Laplacian over the full input; higher means sharper. */
    val sharpness: Float,
)

object FeatureExtractor {
    const val TINY_SIZE = 16

    /** [pixels] are ARGB, row-major, [size]x[size]; [size] must be a multiple of [TINY_SIZE]. */
    fun extract(pixels: IntArray, size: Int): ImageFeatures {
        require(pixels.size == size * size && size % TINY_SIZE == 0) { "Need a ${TINY_SIZE}-divisible square" }
        val gray = IntArray(pixels.size) { luminance(pixels[it]) }
        val tiny = downsample(gray, size, TINY_SIZE)
        return ImageFeatures(
            tiny = ByteArray(tiny.size) { tiny[it].toByte() },
            averageHash = averageHash(tiny),
            sharpness = laplacianVariance(gray, size),
        )
    }

    internal fun luminance(argb: Int): Int {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return (r * 299 + g * 587 + b * 114) / 1000
    }

    /** Box-filter average of [gray] ([size]x[size]) down to [target]x[target]. */
    internal fun downsample(gray: IntArray, size: Int, target: Int): IntArray {
        val block = size / target
        val sums = IntArray(target * target)
        for (y in 0 until size) {
            val row = (y / block) * target
            for (x in 0 until size) sums[row + x / block] += gray[y * size + x]
        }
        val area = block * block
        return IntArray(sums.size) { sums[it] / area }
    }

    /** 8x8 average hash of a 16x16 image (each cell is a 2x2 block). */
    internal fun averageHash(tiny: IntArray): Long {
        val cells = downsample(tiny, TINY_SIZE, 8)
        val mean = cells.average()
        var hash = 0L
        for (i in cells.indices) if (cells[i] > mean) hash = hash or (1L shl i)
        return hash
    }

    internal fun laplacianVariance(gray: IntArray, size: Int): Float {
        var sum = 0.0
        var sumSq = 0.0
        var n = 0
        for (y in 1 until size - 1) {
            for (x in 1 until size - 1) {
                val i = y * size + x
                val lap = (gray[i - 1] + gray[i + 1] + gray[i - size] + gray[i + size] - 4 * gray[i]).toDouble()
                sum += lap
                sumSq += lap * lap
                n++
            }
        }
        val mean = sum / n
        return (sumSq / n - mean * mean).toFloat()
    }
}
