package com.screentranslator.android

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.screentranslator.android.pipeline.CardStyle
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CardStyleInstrumentedTest {
    @Test
    fun whiteSurfaceKeepsWhiteBackgroundAndBlueSourceInk() {
        val bitmap = Bitmap.createBitmap(40, 24, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        for (y in 6 until 18) {
            for (x in 10 until 30) bitmap.setPixel(x, y, Color.BLUE)
        }
        val box = Rect(0, 0, 40, 24)
        val bg = CardStyle.sampleBackground(bitmap, box)
        assertEquals(Color.WHITE, bg)
        assertEquals(Color.BLUE, CardStyle.sampleForeground(bitmap, box, bg))
        bitmap.recycle()
    }

    @Test
    fun darkSurfaceKeepsDarkBackgroundAndWhiteSourceInk() {
        val bitmap = Bitmap.createBitmap(40, 24, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.BLACK)
        for (y in 6 until 18) {
            for (x in 10 until 30) bitmap.setPixel(x, y, Color.WHITE)
        }
        val box = Rect(0, 0, 40, 24)
        val bg = CardStyle.sampleBackground(bitmap, box)
        assertEquals(Color.BLACK, bg)
        assertEquals(Color.WHITE, CardStyle.sampleForeground(bitmap, box, bg))
        bitmap.recycle()
    }
}
