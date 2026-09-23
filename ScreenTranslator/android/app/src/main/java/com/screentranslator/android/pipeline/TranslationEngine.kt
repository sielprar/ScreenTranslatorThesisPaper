package com.screentranslator.android.pipeline

import android.util.Log
import android.os.SystemClock
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await

class TranslationEngine(
    targetLanguage: String = TranslateLanguage.ENGLISH,
) {
    private val target: String = targetLanguage
    private val cache = mutableMapOf<Pair<String, String>, Translator>()
    private val mutex = Mutex()

    private val textCache = object : LinkedHashMap<Pair<String, String>, String>(
        256, 0.75f, true,
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<Pair<String, String>, String>,
        ): Boolean = size > TEXT_CACHE_MAX
    }

    suspend fun prepareModel(sourceLanguage: String): Boolean {
        val src = TranslateLanguage.fromLanguageTag(sourceLanguage) ?: return false
        if (src == target) return true
        val start = SystemClock.elapsedRealtime()
        val ready = getOrCreate(src, target) != null
        Log.i(TAG, "model prewarm [$src→$target]: ready=$ready in ${SystemClock.elapsedRealtime() - start}ms")
        return ready
    }

    suspend fun translate(source: String, sourceLanguage: String): String? {
        val text = source.trim()
        if (text.isEmpty()) return null

        val key = sourceLanguage to text
        synchronized(textCache) { textCache[key] }?.let { return it }

        val src = TranslateLanguage.fromLanguageTag(sourceLanguage)
            ?: return null
        if (src == target) {
            synchronized(textCache) { textCache[key] = text }
            return text
        }

        val start = SystemClock.elapsedRealtime()
        val translator = getOrCreate(src, target) ?: return null
        val readyMs = SystemClock.elapsedRealtime() - start
        return try {
            val translated = translator.translate(text).await()
            val translateMs = SystemClock.elapsedRealtime() - start - readyMs
            if (readyMs > 1_000 || translateMs > 1_000) {
                Log.i(TAG, "translation timing [$src→$target]: model=${readyMs}ms inference=${translateMs}ms")
            }
            synchronized(textCache) { textCache[key] = translated }
            translated
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            Log.w(TAG, "Translation failed [$src→$target]: ${t.message}")
            null
        }
    }

    private suspend fun getOrCreate(src: String, dst: String): Translator? = mutex.withLock {
        val key = src to dst
        cache[key]?.let { return@withLock it }

        val translator = Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(src)
                .setTargetLanguage(dst)
                .build(),
        )
        val conditions = DownloadConditions.Builder().build()
        val started = SystemClock.elapsedRealtime()
        try {
            translator.downloadModelIfNeeded(conditions).await()
        } catch (t: Throwable) {
            if (t is CancellationException) {
                translator.close()
                throw t
            }
            Log.w(TAG, "Model download failed [$src→$dst]: ${t.message}")
            translator.close()
            return@withLock null
        }
        cache[key] = translator
        Log.i(TAG, "model ready [$src→$dst] in ${SystemClock.elapsedRealtime() - started}ms")
        translator
    }

    fun clearTextCache() {
        synchronized(textCache) { textCache.clear() }
    }

    fun close() {
        cache.values.forEach { it.close() }
        cache.clear()
        synchronized(textCache) { textCache.clear() }
    }

    companion object {
        private const val TAG = "ScreenTranslatorPipe"
        private const val TEXT_CACHE_MAX = 512
    }
}
