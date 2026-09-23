package com.screentranslator.android.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.screentranslator.android.pipeline.EvalFlags
import com.screentranslator.android.service.ScreenTranslatorAccessibilityService

class EvalControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_SET_FLAGS) {
            EvalFlags.a11yFastPath = intent.getBooleanExtra("a11y", true)
            EvalFlags.multiScaleOcr = intent.getBooleanExtra("multiscale", true)
            EvalFlags.tesseractFallback = intent.getBooleanExtra("tesseract", true)
            EvalFlags.languageFocus = intent.getBooleanExtra("focus", true)
            Log.i(TAG, "flags ${EvalFlags.describe()}")
            resultData = EvalFlags.describe()
            resultCode = RESULT_OK
            return
        }
        val service = ScreenTranslatorAccessibilityService.instance
        if (service == null) {
            Log.w(TAG, "${intent.action} ignored: accessibility service not connected")
            resultCode = RESULT_NOT_CONNECTED
            return
        }
        when (intent.action) {
            ACTION_SET_FOCUS -> {
                val lang = intent.getStringExtra(EXTRA_LANG)?.trim()?.takeIf { it.isNotEmpty() }
                service.applyLanguageFocus(lang)
            }
            ACTION_RERUN -> service.rerunForEval(intent.getBooleanExtra(EXTRA_CLEAR, false))
            else -> return
        }
        resultCode = RESULT_OK
    }

    private companion object {
        const val TAG = "ScreenTranslatorEval"
        const val ACTION_SET_FOCUS = "com.screentranslator.android.debug.SET_FOCUS"
        const val ACTION_RERUN = "com.screentranslator.android.debug.RERUN"
        const val ACTION_SET_FLAGS = "com.screentranslator.android.debug.SET_FLAGS"
        const val EXTRA_LANG = "lang"
        const val EXTRA_CLEAR = "clear"
        const val RESULT_OK = 1
        const val RESULT_NOT_CONNECTED = 2
    }
}
