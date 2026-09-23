package com.screentranslator.android.pipeline

object VideoTiming {
    const val PERIOD_MS = 500L

    const val MIN_GAP_MS = 150L

    fun delayAfterPassMs(elapsedMs: Long): Long =
        maxOf(MIN_GAP_MS, PERIOD_MS - elapsedMs)
}
