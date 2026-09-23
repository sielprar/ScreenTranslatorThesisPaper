package com.screentranslator.android

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.screentranslator.android.pipeline.AccessibilityHarvester
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccessibilityHarvesterInstrumentedTest {
    @Test
    fun iconOnlyContentDescriptionDoesNotBecomeText() {
        val icon = AccessibilityNodeInfo.obtain().apply {
            packageName = "com.android.chrome"
            className = "android.widget.ImageButton"
            contentDescription = "Новая вкладка"
            setBoundsInScreen(Rect(100, 100, 200, 200))
            isVisibleToUser = true
        }
        assertTrue(AccessibilityHarvester.harvest(icon).isEmpty())
        icon.recycle()
    }

    @Test
    fun playStoreListingStillYieldsVisibleLabels() {
        val listing = AccessibilityNodeInfo.obtain().apply {
            packageName = "com.android.vending"
            className = "android.view.View"
            contentDescription = "ChatGPT\nРабота\nРазговоры с искусственным интеллектом\nСредняя оценка: 4,8\n36 МБ"
            setBoundsInScreen(Rect(300, 500, 900, 680))
            isVisibleToUser = true
        }
        val result = AccessibilityHarvester.harvest(listing)
        assertEquals(2, result.size)
        assertEquals("ChatGPT", result[0].text)
        assertEquals("Работа • Разговоры с искусственным интеллектом", result[1].text)
        listing.recycle()
    }

    @Test
    fun launcherIconUsesCaptionBounds() {
        val icon = AccessibilityNodeInfo.obtain().apply {
            packageName = "com.google.android.apps.nexuslauncher"
            viewIdResourceName = "com.google.android.apps.nexuslauncher:id/icon"
            className = "android.widget.TextView"
            text = "Настройки"
            setBoundsInScreen(Rect(57, 733, 298, 1048))
            isVisibleToUser = true
        }
        val result = AccessibilityHarvester.harvest(icon)
        assertEquals(1, result.size)
        assertEquals(Rect(57, 953, 298, 1025), result[0].bounds)
        assertTrue(result[0].isLauncherLabel)
        icon.recycle()
    }
}
