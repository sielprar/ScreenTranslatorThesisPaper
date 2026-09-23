package com.screentranslator.android.pipeline

import android.graphics.Bitmap
import android.graphics.Color

object PerceptualHash {
    private const val W = 17
    private const val H = 16

    const val SIMILAR_THRESHOLD: Int = 3

    fun dHash(bitmap: Bitmap): LongArray {
        val small = Bitmap.createScaledBitmap(bitmap, W, H, true)
        val pixels = IntArray(W * H)
        small.getPixels(pixels, 0, W, 0, 0, W, H)
        if (small !== bitmap) small.recycle()

        val hash = LongArray(4)
        var bit = 0
        for (y in 0 until H) {
            val rowOffset = y * W
            for (x in 0 until W - 1) {
                val left = luma(pixels[rowOffset + x])
                val right = luma(pixels[rowOffset + x + 1])
                if (left > right) {
                    hash[bit ushr 6] = hash[bit ushr 6] or (1L shl (bit and 63))
                }
                bit++
            }
        }
        return hash
    }

    fun hamming(a: LongArray, b: LongArray): Int {
        var sum = 0
        for (i in a.indices) sum += java.lang.Long.bitCount(a[i] xor b[i])
        return sum
    }

    private fun luma(argb: Int): Int {
        val r = Color.red(argb)
        val g = Color.green(argb)
        val b = Color.blue(argb)
        return (r * 76 + g * 150 + b * 30) ushr 8
    }
}
