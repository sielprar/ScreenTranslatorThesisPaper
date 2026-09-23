package com.screentranslator.android.pipeline

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoTimingTest {
    @Test
    fun shortPassWaitsForTheTickPeriod() {
        assertEquals(380L, VideoTiming.delayAfterPassMs(elapsedMs = 120L))
    }

    @Test
    fun longPassStartsTheNextTickAfterTheMinimumGap() {
        assertEquals(VideoTiming.MIN_GAP_MS, VideoTiming.delayAfterPassMs(elapsedMs = 1_400L))
        assertEquals(VideoTiming.MIN_GAP_MS, VideoTiming.delayAfterPassMs(elapsedMs = 450L))
    }
}
