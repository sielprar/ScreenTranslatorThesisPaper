package com.screentranslator.android.pipeline

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object TranslationRuntime {
    val engine = TranslationEngine()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun prewarm(sourceLanguage: String) {
        scope.launch {
            try {
                engine.prepareModel(sourceLanguage)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("ScreenTranslatorPipe", "Model prewarm failed [$sourceLanguage]", e)
            }
        }
    }
}
