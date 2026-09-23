package com.screentranslator.android.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.util.Log
import com.googlecode.tesseract.android.TessBaseAPI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

class TesseractRecognizer(
    private val context: Context,
    private val languages: String = "eng+rus",
) {
    private val tess = TessBaseAPI()
    private val mutex = Mutex()
    private var initialized = false

    private suspend fun ensureInitialized() = withContext(Dispatchers.IO) {
        if (initialized) return@withContext
        initializationMutex.withLock {
            if (initialized) return@withLock
            copyAssetsIfMissing(languages.split("+"))
            val ok = tess.init(context.filesDir.absolutePath, languages)
            if (!ok) {
                Log.w(TAG, "TessBaseAPI.init failed for languages=$languages")
                return@withLock
            }
            tess.setVariable("tessedit_ocr_engine_mode", "1")
            tess.setVariable("tessedit_char_whitelist", CHAR_WHITELIST)
            tess.pageSegMode = TessBaseAPI.PageSegMode.PSM_SINGLE_LINE
            initialized = true
        }
    }

    private fun copyAssetsIfMissing(langs: List<String>) {
        val tessdataDir = File(context.filesDir, "tessdata").also { it.mkdirs() }
        for (lang in langs) {
            val dst = File(tessdataDir, "$lang.traineddata")
            if (dst.exists()) continue
            try {
                context.assets.open("tessdata/$lang.traineddata").use { input ->
                    dst.outputStream().use { output -> input.copyTo(output) }
                }
            } catch (t: Throwable) {
                Log.w(
                    TAG,
                    "Missing $lang.traineddata in assets — run scripts/download_tessdata.sh",
                    t,
                )
            }
        }
    }

    suspend fun recognize(bitmap: Bitmap): Result = mutex.withLock {
        ensureInitialized()
        if (!initialized) return@withLock Result(emptyList())
        withContext(Dispatchers.Default) {
            tess.setImage(bitmap)
            tess.utF8Text
            val lines = collectLines()
            Result(lines, tess.meanConfidence())
        }
    }

    private fun collectLines(): List<OcrEngine.Region> {
        val iterator = tess.resultIterator ?: return emptyList()
        val regions = mutableListOf<OcrEngine.Region>()
        try {
            iterator.begin()
            do {
                val text = iterator.getUTF8Text(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE)?.trim()
                if (!text.isNullOrEmpty()) {
                    val box = iterator.getBoundingRect(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE)
                    regions.add(OcrEngine.Region(text = text, boundingBox = box))
                }
            } while (iterator.next(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE))
        } finally {
            iterator.delete()
        }
        return regions
    }

    fun close() {
        tess.recycle()
    }

    data class Result(val regions: List<OcrEngine.Region>, val confidence: Int = 0)

    companion object {
        private const val TAG = "ScreenTranslatorPipe"
        private val initializationMutex = Mutex()

        private const val CYRILLIC_UPPER = "АБВГДЕЁЖЗИЙКЛМНОПРСТУФХЦЧШЩЪЫЬЭЮЯ"
        private const val CYRILLIC_LOWER = "абвгдеёжзийклмнопрстуфхцчшщъыьэюя"
        private const val LATIN_UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        private const val LATIN_LOWER = "abcdefghijklmnopqrstuvwxyz"
        private const val DIGITS = "0123456789"
        private const val PUNCT = ".,!?:;-\"'()«»/@#%&+= "
        private const val CHAR_WHITELIST =
            CYRILLIC_UPPER + CYRILLIC_LOWER +
                LATIN_UPPER + LATIN_LOWER +
                DIGITS + PUNCT
    }
}

fun Bitmap.thresholdForOcr(threshold: Int = 200): Bitmap {
    val pixels = IntArray(width * height)
    getPixels(pixels, 0, width, 0, 0, width, height)
    for (index in pixels.indices) {
        val color = pixels[index]
        val gray = (Color.red(color) * 299 + Color.green(color) * 587 +
            Color.blue(color) * 114) / 1000
        pixels[index] = if (gray >= threshold) Color.WHITE else Color.BLACK
    }
    return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
}

fun Bitmap.safeCrop(rect: Rect, paddingPx: Int = 20, minHeightPx: Int = 60): Bitmap? {
    val left = (rect.left - paddingPx).coerceIn(0, width - 1)
    val top = (rect.top - paddingPx).coerceIn(0, height - 1)
    val right = (rect.right + paddingPx).coerceIn(left + 1, width)
    val bottom = (rect.bottom + paddingPx).coerceIn(top + 1, height)
    val w = right - left
    val h = bottom - top
    if (w <= 0 || h <= 0) return null
    val cropped = Bitmap.createBitmap(this, left, top, w, h)
    if (h >= minHeightPx) return cropped
    val scale = minHeightPx.toFloat() / h
    val scaledW = (w * scale).toInt().coerceAtLeast(1)
    val scaledH = minHeightPx
    val scaled = Bitmap.createScaledBitmap(cropped, scaledW, scaledH, true)
    if (scaled !== cropped) cropped.recycle()
    return scaled
}
