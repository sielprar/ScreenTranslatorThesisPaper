package com.screentranslator.android.pipeline

import android.util.Log
import java.io.File
import java.io.FileWriter
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

data class FrameTrace(
    val tsMs: Long,
    val pkg: String,
    val path: Path,
    val totalMs: Long,
    val harvestMs: Long = 0,
    val rootMs: Long = 0,
    val walkMs: Long = 0,
    val hashMs: Long = 0,
    val ocrMs: Long = 0,
    val nmtMs: Long = 0,
    val overlayMs: Long = 0,
    val nRegions: Int = 0,
    val nCards: Int = 0,
    val ocrRaw: Int = 0,
    val ocrKept: Int = 0,
    val ocrW: Int = 0,
    val ocrH: Int = 0,
    val ocrScale: Float = 1f,
) {
    enum class Path { A11Y_HIT, A11Y_FULL, HASH_HIT, OCR_FULL }
}

object BenchmarkRecorder {
    private const val TAG = "ScreenTranslatorBench"
    private const val SUMMARY_EVERY = 30
    private const val MEM_WINDOW = 120
    private const val ROTATE_AT_BYTES = 5_000_000L
    private const val CSV_HEADER =
        "frame_id,ts_ms,pkg,path,total_ms,harvest_ms,root_ms,walk_ms,hash_ms," +
            "ocr_ms,nmt_ms,overlay_ms,n_regions,n_cards,ocr_raw,ocr_kept," +
            "ocr_w,ocr_h,ocr_scale"

    @Volatile private var csvFile: File? = null
    private val recent = ConcurrentLinkedQueue<FrameTrace>()
    private val frameCounter = AtomicLong(0)

    fun init(filesDir: File) {
        if (csvFile != null) return
        val dir = File(filesDir, "benchmarks").also { it.mkdirs() }
        val file = File(dir, "frames.csv")
        val hasData = file.exists() && file.length() > 0L
        val hasHeader = hasData && file.bufferedReader().use { it.readLine() == CSV_HEADER }
        if (hasData && (!hasHeader || file.length() > ROTATE_AT_BYTES)) {
            val rotated = File(dir, "frames-${System.currentTimeMillis()}.csv")
            if (!file.renameTo(rotated)) {
                Log.w(TAG, "Could not rotate invalid/oversized benchmark CSV")
            }
        }
        if (!file.exists() || file.length() == 0L) {
            file.writeText(CSV_HEADER + "\n")
        }
        csvFile = file
        Log.i(TAG, "recording to ${file.absolutePath}")
    }

    fun record(trace: FrameTrace) {
        val id = frameCounter.incrementAndGet()
        val row = trace.toCsvRow(id)
        val file = csvFile
        if (file != null) {
            runCatching {
                FileWriter(file, true).use { it.write(row); it.write("\n") }
            }.onFailure { Log.w(TAG, "CSV write failed", it) }
        }
        recent.add(trace)
        while (recent.size > MEM_WINDOW) recent.poll()
        if (id % SUMMARY_EVERY == 0L) emitSummary()
    }

    private fun emitSummary() {
        val snapshot = recent.toList()
        if (snapshot.isEmpty()) return
        val parts = FrameTrace.Path.values().mapNotNull { path ->
            val subset = snapshot.filter { it.path == path }
            if (subset.isEmpty()) return@mapNotNull null
            val sorted = subset.map { it.totalMs }.sorted()
            val p50 = sorted[sorted.size / 2]
            val p95 = sorted[minOf(sorted.size - 1, (sorted.size * 95) / 100)]
            "${path.name.lowercase()} n=${subset.size} p50=${p50}ms p95=${p95}ms"
        }
        Log.i(TAG, "last ${snapshot.size} frames: ${parts.joinToString("; ")}")
    }

    private fun FrameTrace.toCsvRow(id: Long): String =
        "$id,$tsMs,${csvEscape(pkg)},${path.name.lowercase()},$totalMs,$harvestMs,$rootMs,$walkMs," +
            "$hashMs,$ocrMs,$nmtMs,$overlayMs,$nRegions,$nCards,$ocrRaw,$ocrKept," +
            "$ocrW,$ocrH,$ocrScale"

    private fun csvEscape(s: String): String =
        if (s.contains(',') || s.contains('"'))
            "\"${s.replace("\"", "\"\"")}\""
        else s
}
