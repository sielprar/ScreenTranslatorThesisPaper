package com.screentranslator.android.pipeline

import android.graphics.Bitmap

private const val OCR_MAX_LONG_SIDE = 2400

fun Bitmap.downscaleForOcr(maxLongSide: Int = OCR_MAX_LONG_SIDE): Pair<Bitmap, Float> {
    val longSide = maxOf(width, height)
    if (longSide <= maxLongSide) return this to 1f
    val scale = maxLongSide.toFloat() / longSide
    val w = (width * scale).toInt().coerceAtLeast(1)
    val h = (height * scale).toInt().coerceAtLeast(1)
    val scaled = Bitmap.createScaledBitmap(this, w, h, true)
    return scaled to scale
}
