package com.screentranslator.android.pipeline

import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import com.google.mlkit.nl.languageid.LanguageIdentifier
import kotlinx.coroutines.tasks.await

class LanguageDetector(confidenceThreshold: Float = 0.5f) {
    private val identifier: LanguageIdentifier = LanguageIdentification.getClient(
        LanguageIdentificationOptions.Builder()
            .setConfidenceThreshold(confidenceThreshold)
            .build(),
    )

    suspend fun detect(text: String): LidResult {
        if (text.isBlank()) return UNDETERMINED_RESULT
        val candidates = identifier.identifyPossibleLanguages(text).await()
        val top = candidates.firstOrNull() ?: return UNDETERMINED_RESULT
        return if (top.languageTag == UNDETERMINED) {
            UNDETERMINED_RESULT
        } else {
            LidResult(top.languageTag, top.confidence)
        }
    }

    fun close() {
        identifier.close()
    }

    companion object {
        const val UNDETERMINED: String = "und"
        private val UNDETERMINED_RESULT = LidResult(UNDETERMINED, 0f)
    }
}

data class LidResult(val language: String, val confidence: Float)
