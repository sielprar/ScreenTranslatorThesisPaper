package com.screentranslator.android.pipeline

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.tasks.await

class OcrEngine {
    private val native: TextRecognizer =
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val upscaled: TextRecognizer =
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun recognize(bitmap: Bitmap, multiScale: Boolean = true): Result = coroutineScope {
        val nativeInput = InputImage.fromBitmap(bitmap, 0)
        if (!multiScale) {
            val regions = runOne(native, nativeInput, scale = 1f)
            return@coroutineScope Result(
                fullText = regions.joinToString("\n") { it.text },
                regions = regions,
            )
        }
        val big = Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * UPSCALE).toInt().coerceAtLeast(1),
            (bitmap.height * UPSCALE).toInt().coerceAtLeast(1),
            true,
        )
        val bigInput = InputImage.fromBitmap(big, 0)

        val results = try {
            val nativeAsync = async { runOne(native, nativeInput, scale = 1f) }
            val bigAsync = async { runOne(upscaled, bigInput, scale = 1f / UPSCALE) }
            listOf(nativeAsync, bigAsync).awaitAll()
        } finally {
            if (big !== bitmap) big.recycle()
        }

        val merged = dedup(results.flatten())
        Result(fullText = merged.joinToString("\n") { it.text }, regions = merged)
    }

    private suspend fun runOne(
        recognizer: TextRecognizer,
        input: InputImage,
        scale: Float,
    ): List<Region> = try {
        val visionText = recognizer.process(input).await()
        visionText.textBlocks.flatMap { block ->
            block.lines.map { line ->
                Region(
                    text = line.text,
                    boundingBox = line.boundingBox?.let { rescale(it, scale) },
                )
            }
        }
    } catch (t: Throwable) {
        if (t is CancellationException) throw t
        emptyList()
    }

    private fun rescale(rect: Rect, scale: Float): Rect =
        if (scale >= 0.999f && scale <= 1.001f) rect
        else Rect(
            (rect.left * scale).toInt(),
            (rect.top * scale).toInt(),
            (rect.right * scale).toInt(),
            (rect.bottom * scale).toInt(),
        )

    private fun dedup(regions: List<Region>): List<Region> {
        val sorted = regions.sortedByDescending { it.text.length }
        val kept = mutableListOf<Region>()
        for (r in sorted) {
            val box = r.boundingBox ?: continue
            val overlaps = kept.any { existing ->
                val eb = existing.boundingBox ?: return@any false
                iou(box, eb) > DEDUP_IOU
            }
            if (!overlaps) kept.add(r)
        }
        return kept
    }

    private fun iou(a: Rect, b: Rect): Float {
        val interLeft = maxOf(a.left, b.left)
        val interTop = maxOf(a.top, b.top)
        val interRight = minOf(a.right, b.right)
        val interBottom = minOf(a.bottom, b.bottom)
        val interW = (interRight - interLeft).coerceAtLeast(0)
        val interH = (interBottom - interTop).coerceAtLeast(0)
        val inter = interW.toLong() * interH.toLong()
        if (inter == 0L) return 0f
        val areaA = a.width().toLong() * a.height().toLong()
        val areaB = b.width().toLong() * b.height().toLong()
        val union = areaA + areaB - inter
        if (union == 0L) return 0f
        return inter.toFloat() / union.toFloat()
    }

    fun close() {
        native.close()
        upscaled.close()
    }

    data class Result(val fullText: String, val regions: List<Region>)

    data class Region(val text: String, val boundingBox: Rect?)

    companion object {
        private const val UPSCALE = 1.75f

        private const val DEDUP_IOU = 0.5f
    }
}
