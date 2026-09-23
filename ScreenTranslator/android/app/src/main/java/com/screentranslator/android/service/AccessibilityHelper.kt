package com.screentranslator.android.service

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.text.TextUtils

object AccessibilityHelper {
    fun isServiceEnabled(context: Context): Boolean {
        val expected = ComponentName(
            context,
            ScreenTranslatorAccessibilityService::class.java,
        ).flattenToString()

        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false

        val splitter = TextUtils.SimpleStringSplitter(SEPARATOR).apply {
            setString(enabled)
        }
        while (splitter.hasNext()) {
            if (splitter.next().equals(expected, ignoreCase = true)) {
                return true
            }
        }
        return false
    }

    private const val SEPARATOR = ':'
}
