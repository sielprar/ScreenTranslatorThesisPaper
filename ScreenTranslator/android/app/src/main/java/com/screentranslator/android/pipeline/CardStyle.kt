package com.screentranslator.android.pipeline

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect

object CardStyle {
    const val VIDEO_BACKGROUND: Int = 0xB3000000.toInt()
    const val VIDEO_FOREGROUND: Int = 0xFFFFFFFF.toInt()

    private const val REGION_HEIGHT_TO_FONT_FACTOR = 0.6f

    private const val MIN_FONT_SP = 8f
    private const val MAX_FONT_SP = 48f

    fun fontSpFromRegionHeight(regionHeightPx: Int, density: Float): Float {
        val sp = regionHeightPx * REGION_HEIGHT_TO_FONT_FACTOR / density
        return sp.coerceIn(MIN_FONT_SP, MAX_FONT_SP)
    }

    fun sampleBackground(bitmap: Bitmap, region: Rect): Int {
        val left = region.left.coerceAtLeast(0)
        val top = region.top.coerceAtLeast(0)
        val right = (region.right - 1).coerceAtMost(bitmap.width - 1)
        val bottom = (region.bottom - 1).coerceAtMost(bitmap.height - 1)
        if (right <= left || bottom <= top) return DEFAULT_BG

        val reds = ArrayList<Int>()
        val greens = ArrayList<Int>()
        val blues = ArrayList<Int>()
        fun add(pixel: Int) {
            reds.add(Color.red(pixel))
            greens.add(Color.green(pixel))
            blues.add(Color.blue(pixel))
        }

        var x = left
        while (x <= right) {
            val topPx = bitmap.getPixel(x, top)
            val botPx = bitmap.getPixel(x, bottom)
            add(topPx)
            add(botPx)
            x += BORDER_STRIDE
        }
        var y = top + BORDER_STRIDE
        while (y < bottom) {
            val leftPx = bitmap.getPixel(left, y)
            val rightPx = bitmap.getPixel(right, y)
            add(leftPx)
            add(rightPx)
            y += BORDER_STRIDE
        }
        if (reds.isEmpty()) return DEFAULT_BG
        reds.sort()
        greens.sort()
        blues.sort()
        val middle = reds.size / 2
        return Color.argb(
            255,
            reds[middle],
            greens[middle],
            blues[middle],
        )
    }

    private const val BORDER_STRIDE = 4

    fun sampleForeground(bitmap: Bitmap, region: Rect, background: Int): Int {
        val left = region.left.coerceAtLeast(0)
        val top = region.top.coerceAtLeast(0)
        val right = region.right.coerceAtMost(bitmap.width)
        val bottom = region.bottom.coerceAtMost(bitmap.height)
        if (right <= left || bottom <= top) return textColorFor(background)
        val candidates = ArrayList<Pair<Int, Int>>()
        for (y in top until bottom step 2) {
            for (x in left until right step 2) {
                val pixel = bitmap.getPixel(x, y)
                val difference = maxOf(
                    kotlin.math.abs(Color.red(pixel) - Color.red(background)),
                    kotlin.math.abs(Color.green(pixel) - Color.green(background)),
                    kotlin.math.abs(Color.blue(pixel) - Color.blue(background)),
                )
                if (difference >= 60) candidates.add(difference to pixel)
            }
        }
        if (candidates.isEmpty()) return textColorFor(background)
        candidates.sortByDescending { it.first }
        val strongest = candidates.take((candidates.size / 3).coerceAtLeast(1))
        val reds = strongest.map { Color.red(it.second) }.sorted()
        val greens = strongest.map { Color.green(it.second) }.sorted()
        val blues = strongest.map { Color.blue(it.second) }.sorted()
        val middle = strongest.size / 2
        val foreground = Color.rgb(reds[middle], greens[middle], blues[middle])
        return if (kotlin.math.abs(luma(foreground) - luma(background)) >= 70) {
            foreground
        } else textColorFor(background)
    }

    fun textColorFor(bgColor: Int): Int {
        val r = Color.red(bgColor)
        val g = Color.green(bgColor)
        val b = Color.blue(bgColor)
        val perceptualLuma = (r * 299 + g * 587 + b * 114) / 1000
        return if (perceptualLuma > READABLE_LUMA_THRESHOLD) Color.BLACK else Color.WHITE
    }

    private fun luma(argb: Int): Int {
        val r = Color.red(argb)
        val g = Color.green(argb)
        val b = Color.blue(argb)
        return (r * 76 + g * 150 + b * 30) ushr 8
    }

    private const val READABLE_LUMA_THRESHOLD = 128
    private val DEFAULT_BG = Color.argb(255, 240, 240, 240)
}
