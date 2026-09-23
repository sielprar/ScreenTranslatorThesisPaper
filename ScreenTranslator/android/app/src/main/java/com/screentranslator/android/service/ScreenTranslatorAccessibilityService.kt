package com.screentranslator.android.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import com.screentranslator.android.overlay.LanguageFocusOverlay
import com.screentranslator.android.overlay.OverlayWindow
import com.screentranslator.android.pipeline.A11yTextNode
import com.screentranslator.android.pipeline.AccessibilityHarvester
import com.screentranslator.android.pipeline.BenchmarkRecorder
import com.screentranslator.android.pipeline.CardStyle
import com.screentranslator.android.pipeline.EvalFlags
import com.screentranslator.android.pipeline.FrameTrace
import com.screentranslator.android.pipeline.LanguageDetector
import com.screentranslator.android.pipeline.OcrEngine
import com.screentranslator.android.pipeline.PerceptualHash
import com.screentranslator.android.pipeline.TesseractRecognizer
import com.screentranslator.android.pipeline.TextRouting
import com.screentranslator.android.pipeline.TranslationRuntime
import com.screentranslator.android.pipeline.VideoTiming
import com.screentranslator.android.pipeline.downscaleForOcr
import com.screentranslator.android.pipeline.safeCrop
import com.screentranslator.android.pipeline.thresholdForOcr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import java.util.concurrent.Executors

private fun formatConf(value: Float): String = "%.2f".format(value)

class ScreenTranslatorAccessibilityService : AccessibilityService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val screenshotExecutor = Executors.newSingleThreadExecutor()
    private val ocr = OcrEngine()
    private val lid = LanguageDetector()
    private val translator = TranslationRuntime.engine
    private val tesseract by lazy { TesseractRecognizer(this) }
    private val russianTesseracts by lazy {
        List(RUSSIAN_OCR_WORKERS) { TesseractRecognizer(this, "rus") }
    }

    private var overlay: OverlayWindow? = null
    private var languageChip: LanguageFocusOverlay? = null

    @Volatile
    private var focusedLanguage: String? = null

    @Volatile
    private var lastSuggestion: String? = null

    @Volatile
    private var lastEventPackage: String? = null

    private val dismissedSuggestions: MutableSet<String> = mutableSetOf()

    @Volatile
    private var inflight: Job? = null

    @Volatile
    private var pending = false

    @Volatile
    private var windowEpoch = 0L
    @Volatile
    private var scrollRevision = 0L
    private var scrollSettleJob: Job? = null
    private var videoRefreshJob: Job? = null

    @Volatile
    private var lastFrameHash: LongArray? = null

    @Volatile
    private var lastScreenshotRequestMs = 0L

    @Volatile
    private var lastCards: List<OverlayWindow.TranslationCard> = emptyList()

    private val resolvedCache = object : LinkedHashMap<String, ResolvedRegion>(
        256, 0.75f, true,
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, ResolvedRegion>,
        ): Boolean = size > RESOLVED_CACHE_MAX
    }

    private data class ResolvedRegion(val lang: String, val translated: String?)

    private data class StyleKey(val pkg: String, val text: String, val xBand: Int)
    private data class TextStyle(val background: Int, val foreground: Int)
    private val styleCache = object : LinkedHashMap<StyleKey, TextStyle>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<StyleKey, TextStyle>) =
            size > RESOLVED_CACHE_MAX
    }

    private fun styleKey(pkg: String, card: OverlayWindow.TranslationCard) =
        StyleKey(pkg, card.sourceText, card.bounds.left / 64)

    private data class PassContext(
        val startMs: Long,
        val harvestMs: Long,
        val pkg: String,
        val rootMs: Long = 0,
        val walkMs: Long = 0,
        val windowEpoch: Long = 0,
        val scrollRevision: Long = 0,
    )

    private fun isCurrent(ctx: PassContext) =
        ctx.windowEpoch == windowEpoch && ctx.scrollRevision == scrollRevision

    @Volatile
    private var lastA11yFingerprint: Int = 0

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "Accessibility service connected")
        instance = this
        overlay = OverlayWindow(this).also { it.show() }
        languageChip = LanguageFocusOverlay(this).apply {
            callbacks = object : LanguageFocusOverlay.Callbacks {
                override fun onConfirm(languageCode: String) {
                    focusedLanguage = languageCode
                    dismissedSuggestions.clear()
                    showLocked(languageCode)
                    Log.i(TAG, "language focus confirmed: $languageCode")
                    rememberModelSource(languageCode)
                    invalidateResolutionCaches()
                }
                override fun onPick(languageCode: String) {
                    focusedLanguage = languageCode
                    dismissedSuggestions.clear()
                    showLocked(languageCode)
                    Log.i(TAG, "language focus picked: $languageCode")
                    rememberModelSource(languageCode)
                    invalidateResolutionCaches()
                }
                override fun onClear() {
                    focusedLanguage = null
                    Log.i(TAG, "language focus cleared")
                    hide()
                    invalidateResolutionCaches()
                }
            }
        }
        BenchmarkRecorder.init(filesDir)
        val recent = getSharedPreferences("translation_model", MODE_PRIVATE)
            .getString("recent_source", "ru") ?: "ru"
        TranslationRuntime.prewarm(recent)
        captureAndRecognize()
    }

    fun applyLanguageFocus(languageCode: String?) {
        if (lastEventPackage == null) {
            lastEventPackage = rootInActiveWindow?.packageName?.toString()
                ?.takeIf { it != packageName }
        }
        focusedLanguage = languageCode
        if (languageCode == null) {
            languageChip?.hide()
        } else {
            dismissedSuggestions.clear()
            languageChip?.showLocked(languageCode)
            rememberModelSource(languageCode)
        }
        Log.i(TAG, "language focus set externally: ${languageCode ?: "none"}")
        invalidateResolutionCaches()
    }

    fun rerunForEval(clearCaches: Boolean) {
        if (clearCaches) {
            synchronized(resolvedCache) { resolvedCache.clear() }
            synchronized(styleCache) { styleCache.clear() }
            translator.clearTextCache()
            lastFrameHash = null
            lastA11yFingerprint = 0
        }
        Log.i(TAG, "eval rerun (clearCaches=$clearCaches)")
        if (inflight?.isActive == true) pending = true else captureAndRecognize()
    }

    private fun rememberModelSource(languageCode: String) {
        if (languageCode == "en") return
        getSharedPreferences("translation_model", MODE_PRIVATE).edit()
            .putString("recent_source", languageCode).apply()
        TranslationRuntime.prewarm(languageCode)
    }

    private fun invalidateResolutionCaches() {
        synchronized(resolvedCache) { resolvedCache.clear() }
        lastFrameHash = null
        lastA11yFingerprint = 0
        windowEpoch++
        if (inflight?.isActive == true) {
            pending = true
            inflight?.cancel()
        } else {
            captureAndRecognize()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val relevant = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED
        if (!relevant) return
        if (event.packageName?.toString() == packageName) return
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            scrollRevision++
            lastCards = emptyList()
            lastA11yFingerprint = 0
            lastFrameHash = null
            overlay?.update(emptyList())
            pending = false
            inflight?.cancel()
            scrollSettleJob?.cancel()
            scrollSettleJob = scope.launch {
                delay(SCROLL_SETTLE_MS)
                withContext(Dispatchers.Main) {
                    if (inflight?.isActive == true) pending = true
                    else captureAndRecognize()
                }
            }
            return
        }
        if (scrollSettleJob?.isActive == true &&
            event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return

        val isAppSwitch = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED

        if (isAppSwitch) {
            scrollSettleJob?.cancel()
            windowEpoch++
            val newPackage = event.packageName?.toString()
            val actualAppSwitch = newPackage != null && newPackage != lastEventPackage
            if (actualAppSwitch) {
                lastFrameHash = null
                lastA11yFingerprint = 0
                lastCards = emptyList()
                synchronized(resolvedCache) { resolvedCache.clear() }
                focusedLanguage = null
                dismissedSuggestions.clear()
                lastSuggestion = null
                scope.launch(Dispatchers.Main) {
                    overlay?.update(emptyList())
                    languageChip?.hide()
                }
                updateVideoRefresh(newPackage)
            }
            lastEventPackage = newPackage
        }

        if (inflight?.isActive == true) {
            pending = true
            if (isAppSwitch) inflight?.cancel()
            return
        }

        captureAndRecognize()
    }

    private fun maybeRefire() {
        if (!pending) return
        pending = false
        captureAndRecognize()
    }

    private fun captureAndRecognize() {
        pending = false
        inflight = scope.launch {
            try {
                runOnePass()
            } finally {
                if (inflight === coroutineContext[Job]) maybeRefire()
            }
        }
    }

    private fun updateVideoRefresh(packageName: String?) {
        videoRefreshJob?.cancel()
        videoRefreshJob = null
        if (packageName !in VIDEO_PACKAGES) return
        videoRefreshJob = scope.launch {
            while (isActive && lastEventPackage in VIDEO_PACKAGES) {
                val tickStart = SystemClock.uptimeMillis()
                if (scrollSettleJob?.isActive != true) {
                    val pass = inflight?.takeIf { it.isActive }
                        ?: withContext(Dispatchers.Main) { captureAndRecognize(); inflight }
                    pass?.join()
                }
                delay(VideoTiming.delayAfterPassMs(SystemClock.uptimeMillis() - tickStart))
            }
        }
    }

    private suspend fun runOnePass() {
        val passStart = System.currentTimeMillis()
        val passEpoch = windowEpoch
        val passScrollRevision = scrollRevision
        val rootStart = passStart
        val root = try {
            rootInActiveWindow
        } catch (t: Throwable) {
            Log.w(TAG, "rootInActiveWindow failed", t)
            null
        }
        val rootMs = System.currentTimeMillis() - rootStart
        val walkStart = System.currentTimeMillis()
        val nodes = try {
            AccessibilityHarvester.harvest(root)
        } catch (t: Throwable) {
            Log.w(TAG, "a11y harvest failed", t)
            emptyList()
        }
        val walkMs = System.currentTimeMillis() - walkStart
        val harvestMs = rootMs + walkMs
        val pkg = root?.packageName?.toString() ?: "unknown"
        if (pkg in VIDEO_PACKAGES && videoRefreshJob?.isActive != true) {
            lastEventPackage = pkg
            updateVideoRefresh(pkg)
        }
        val ctx = PassContext(
            startMs = passStart,
            harvestMs = harvestMs,
            pkg = pkg,
            rootMs = rootMs,
            walkMs = walkMs,
            windowEpoch = passEpoch,
            scrollRevision = passScrollRevision,
        )
        if (!isCurrent(ctx)) return

        if (EvalFlags.a11yFastPath && nodes.size >= MIN_A11Y_NODES_FAST_PATH &&
            pkg !in IMAGE_VIEWER_PACKAGES && pkg !in VIDEO_PACKAGES
        ) {
            val fastCardCount = runFastPath(nodes, ctx)
            if (fastCardCount > 0) return
        }
        val bitmap = takeScreenshotSuspending() ?: return
        try {
            runPipeline(bitmap, ctx)
        } finally {
            bitmap.recycle()
        }
    }

    private suspend fun takeScreenshotSuspending(): Bitmap? {
        val waitMs = SCREENSHOT_MIN_INTERVAL_MS -
            (SystemClock.uptimeMillis() - lastScreenshotRequestMs)
        if (waitMs > 0) delay(waitMs)
        lastScreenshotRequestMs = SystemClock.uptimeMillis()
        return captureScreenshotOnce()
    }

    private suspend fun captureScreenshotOnce(): Bitmap? = suspendCancellableCoroutine { cont ->
        scope.launch(Dispatchers.Main) {
            val hadCards = lastCards.isNotEmpty()
            val hadChip = focusedLanguage != null || lastSuggestion != null
            if (hadCards) {
                overlay?.setCardsInvisible(true)
            }
            languageChip?.setTemporarilyInvisible(true)
            if (hadCards || hadChip) delay(SCREENSHOT_HIDE_DELAY_MS)
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                screenshotExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        val hwBuffer = screenshot.hardwareBuffer
                        val swBitmap: Bitmap? = try {
                            val hw = Bitmap.wrapHardwareBuffer(hwBuffer, screenshot.colorSpace)
                            hw?.copy(Bitmap.Config.ARGB_8888, false)
                        } finally {
                            hwBuffer.close()
                        }
                        scope.launch(Dispatchers.Main) {
                            if (hadCards) overlay?.setCardsInvisible(false)
                            languageChip?.setTemporarilyInvisible(false)
                        }
                        if (swBitmap == null) {
                            Log.w(TAG, "Failed to convert hardware buffer to bitmap")
                        }
                        if (cont.isActive) cont.resume(swBitmap)
                    }

                    override fun onFailure(errorCode: Int) {
                        scope.launch(Dispatchers.Main) {
                            if (hadCards) overlay?.setCardsInvisible(false)
                            languageChip?.setTemporarilyInvisible(false)
                        }
                        Log.w(TAG, "takeScreenshot failed: errorCode=$errorCode")
                        if (cont.isActive) cont.resume(null)
                    }
                },
            )
        }
    }

    private suspend fun runPipeline(bitmap: Bitmap, ctx: PassContext) {
        if (!isCurrent(ctx)) return
        val hashStart = System.currentTimeMillis()
        val hash = PerceptualHash.dHash(bitmap)
        val hashMs = System.currentTimeMillis() - hashStart
        val previous = lastFrameHash
        if (previous != null) {
            val distance = PerceptualHash.hamming(hash, previous)
            if (distance <= PerceptualHash.SIMILAR_THRESHOLD) {
                if (!isCurrent(ctx)) return
                Log.i(
                    PIPELINE_TAG,
                    "screen unchanged (hamming=$distance/256 in ${hashMs}ms); reusing ${lastCards.size} card(s)",
                )
                val overlayStart = System.currentTimeMillis()
                withContext(Dispatchers.Main) {
                    overlay?.update(lastCards)
                    refreshLanguageChip()
                }
                val overlayMs = System.currentTimeMillis() - overlayStart
                BenchmarkRecorder.record(
                    FrameTrace(
                        tsMs = ctx.startMs,
                        pkg = ctx.pkg,
                        path = FrameTrace.Path.HASH_HIT,
                        totalMs = System.currentTimeMillis() - ctx.startMs,
                        harvestMs = ctx.harvestMs,
                        rootMs = ctx.rootMs,
                        walkMs = ctx.walkMs,
                        hashMs = hashMs,
                        overlayMs = overlayMs,
                        nCards = lastCards.size,
                    ),
                )
                return
            }
            Log.i(
                PIPELINE_TAG,
                "screen changed (hamming=$distance/256 in ${hashMs}ms); rerunning pipeline",
            )
            if (ctx.pkg !in VIDEO_PACKAGES && lastCards.isNotEmpty()) {
                withContext(Dispatchers.Main) { overlay?.update(emptyList()) }
            }
        }
        lastFrameHash = hash

        val videoMode = ctx.pkg in VIDEO_PACKAGES
        val (ocrBitmap, ocrScale) = bitmap.downscaleForOcr(
            maxLongSide = if (videoMode) VIDEO_OCR_MAX_LONG_SIDE else 2400,
        )
        val ocrWidth = ocrBitmap.width
        val ocrHeight = ocrBitmap.height
        val ocrStart = System.currentTimeMillis()
        val result = try {
            ocr.recognize(ocrBitmap, multiScale = !videoMode && EvalFlags.multiScaleOcr)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            Log.w(PIPELINE_TAG, "OCR failed", t)
            return
        } finally {
            if (ocrBitmap !== bitmap) ocrBitmap.recycle()
        }
        val ocrMs = System.currentTimeMillis() - ocrStart

        val nmtStart = System.currentTimeMillis()
        Log.i(
            PIPELINE_TAG,
            "OCR ${ocrWidth}x${ocrHeight} (scale=${formatConf(ocrScale)}) in ${ocrMs}ms → ${result.regions.size} region(s)",
        )
        val scaledRegions = if (ocrScale < 0.999f) {
            val inv = 1f / ocrScale
            result.regions.map { r ->
                val box = r.boundingBox ?: return@map r
                r.copy(
                    boundingBox = android.graphics.Rect(
                        (box.left * inv).toInt(),
                        (box.top * inv).toInt(),
                        (box.right * inv).toInt(),
                        (box.bottom * inv).toInt(),
                    ),
                )
            }
        } else result.regions
        val regions = scaledRegions.take(MAX_LOG_LINES)
        val cards = deoverlap(coroutineScope {
            regions.map { region ->
                async { processRegion(bitmap, region, videoMode) }
            }.awaitAll()
        }.filterNotNull())
        if (!isCurrent(ctx)) return
        val nmtMs = System.currentTimeMillis() - nmtStart

        lastCards = cards
        val overlayStart = System.currentTimeMillis()
        val suggestion = suggestLanguage(cards)
        if (suggestion != null) {
            if (suggestion != lastSuggestion) rememberModelSource(suggestion)
            lastSuggestion = suggestion
        }
        withContext(Dispatchers.Main) {
            overlay?.update(cards)
            refreshLanguageChip()
        }
        val overlayMs = System.currentTimeMillis() - overlayStart
        Log.i(PIPELINE_TAG, "LID+NMT for ${regions.size} region(s) in ${nmtMs}ms → ${cards.size} card(s)")

        if (scaledRegions.size > MAX_LOG_LINES) {
            Log.i(PIPELINE_TAG, "  … ${scaledRegions.size - MAX_LOG_LINES} more region(s) skipped")
        }

        BenchmarkRecorder.record(
            FrameTrace(
                tsMs = ctx.startMs,
                pkg = ctx.pkg,
                path = FrameTrace.Path.OCR_FULL,
                totalMs = System.currentTimeMillis() - ctx.startMs,
                harvestMs = ctx.harvestMs,
                rootMs = ctx.rootMs,
                walkMs = ctx.walkMs,
                hashMs = hashMs,
                ocrMs = ocrMs,
                nmtMs = nmtMs,
                overlayMs = overlayMs,
                nRegions = scaledRegions.size,
                nCards = cards.size,
                ocrRaw = result.regions.size,
                ocrKept = regions.size,
                ocrW = ocrBitmap.width,
                ocrH = ocrBitmap.height,
                ocrScale = ocrScale,
            ),
        )
    }

    private suspend fun processRegion(
        bitmap: Bitmap,
        region: OcrEngine.Region,
        videoMode: Boolean = false,
    ): OverlayWindow.TranslationCard? {
        val originalText = region.text.trim()
        if (originalText.isEmpty()) return null
        if (originalText.length < 2) return null
        if (TextRouting.isNonTranslatable(originalText)) return null
        val box = region.boundingBox
        if (box != null && box.top < resources.displayMetrics.density * 30f) return null
        if (box != null && (box.width() < MIN_REGION_WIDTH_PX ||
                box.height() < MIN_REGION_HEIGHT_PX)) {
            return null
        }

        val cached = synchronized(resolvedCache) { resolvedCache[originalText] }
        if (cached != null) {
            return buildCardFrom(bitmap, box, cached, videoMode)
        }

        val resolved = resolveRegion(bitmap, region, originalText, videoMode)
        synchronized(resolvedCache) { resolvedCache[originalText] = resolved }
        return buildCardFrom(bitmap, box, resolved, videoMode)
    }

    private suspend fun resolveRegion(
        bitmap: Bitmap,
        region: OcrEngine.Region,
        originalText: String,
        videoMode: Boolean = false,
    ): ResolvedRegion {
        var text = originalText

        val focus = focusedLanguage.takeIf { EvalFlags.languageFocus }
        if (focus != null && region.boundingBox != null) {
            val focusLid = if (TextRouting.isReadableCyrillic(text)) null else lid.detect(text)
            when (TextRouting.focusDecision(
                focus, text, focusLid?.language, focusLid?.confidence ?: 0f,
            )) {
                TextRouting.FocusDecision.SKIP_ENGLISH -> {
                    Log.i(PIPELINE_TAG, "  [en/focus-skip] \"$text\" @ ${region.boundingBox}")
                    return ResolvedRegion("en", null)
                }
                TextRouting.FocusDecision.AUTO -> Unit
                TextRouting.FocusDecision.APPLY ->
                    return resolveFocused(bitmap, region.boundingBox, text, focus, videoMode)
            }
        }

        val readableCyrillic = TextRouting.isReadableCyrillic(text)
        val lidResult = if (readableCyrillic) null else lid.detect(text)
        var lang = if (readableCyrillic) "ru" else lidResult!!.language
        val conf = if (readableCyrillic) 1f else lidResult!!.confidence
        var trustedLanguage = readableCyrillic || TextRouting.isTrustedLanguage(lang, conf)
        val suspectReason = if (readableCyrillic) null else TextRouting.suspectReason(text, lang, conf)

        val allowTesseract = !videoMode || region.boundingBox?.let { box ->
            TextRouting.isLikelyVideoCaption(box.top, box.width(), box.height(), bitmap.height)
        } == true
        if (EvalFlags.tesseractFallback && suspectReason != null && allowTesseract &&
            region.boundingBox != null
        ) {
            val box = region.boundingBox
            suspend fun readRussian(): Pair<String, Int>? = try {
                recognizeRussianComicText(bitmap, box, fastMode = videoMode)
                    ?.takeIf { TextRouting.acceptRussianReading(it.first, it.second) }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                Log.w(PIPELINE_TAG, "Russian OCR failed", t)
                null
            }
            val latinCredible = TextRouting.latinReadingIsCredible(originalText, lang, conf)
            var triedRussian = TextRouting.russianReaderFirst(originalText, lang, conf)
            var russian = if (triedRussian) readRussian() else null
            if (russian == null) {
                val tessText = bitmap.safeCrop(box)?.let { crop ->
                    try {
                        tesseract.recognize(crop).regions.joinToString(" ") { it.text }.trim()
                    } catch (t: Throwable) {
                        if (t is CancellationException) throw t
                        Log.w(PIPELINE_TAG, "Tesseract failed", t)
                        ""
                    } finally {
                        crop.recycle()
                    }
                }.orEmpty()
                if (tessText.isNotEmpty()) {
                    val tessLid = lid.detect(tessText)
                    val reason = if (suspectReason == TextRouting.SuspectReason.LOW_CONFIDENCE) {
                        "${suspectReason.label}(${formatConf(conf)})"
                    } else suspectReason.label
                    Log.i(
                        PIPELINE_TAG,
                        "  tesseract tried on [$lang]/$reason \"$originalText\" → " +
                            "[${tessLid.language} conf=${formatConf(tessLid.confidence)} " +
                            "cyr=${TextRouting.cyrillicCount(tessText)}] \"$tessText\"",
                    )
                    if (!triedRussian && !latinCredible && TextRouting.russianReaderAfter(tessText)) {
                        triedRussian = true
                        russian = readRussian()
                    }
                    if (russian == null) {
                        val accepted = TextRouting.acceptTesseractReading(
                            tessText, tessLid.language, tessLid.confidence, latinCredible,
                        )
                        if (accepted != null) {
                            text = tessText
                            lang = accepted
                            trustedLanguage = true
                        }
                    }
                }
            }
            if (russian != null) {
                text = russian.first
                lang = "ru"
                trustedLanguage = true
                Log.i(PIPELINE_TAG, "  [ru/comic] read \"$text\" (ML Kit \"$originalText\") @ $box")
            }
        }

        return when {
            !trustedLanguage -> {
                Log.i(PIPELINE_TAG, "  [und] \"$text\" @ ${region.boundingBox}")
                ResolvedRegion(LanguageDetector.UNDETERMINED, null)
            }

            lang == "en" -> {
                Log.i(PIPELINE_TAG, "  [en] \"$text\" @ ${region.boundingBox}")
                ResolvedRegion(lang, null)
            }

            else -> {
                val translated = translateVisible(text, lang)
                Log.i(
                    PIPELINE_TAG,
                    "  [$lang→en] \"$text\" → \"${translated ?: "?"}\" @ ${region.boundingBox}",
                )
                ResolvedRegion(lang, translated)
            }
        }
    }

    private suspend fun resolveFocused(
        bitmap: Bitmap,
        box: android.graphics.Rect,
        mlKitText: String,
        focus: String,
        videoMode: Boolean,
    ): ResolvedRegion {
        var text = mlKitText
        if (!EvalFlags.tesseractFallback) {
        } else if (focus == "ru") {
            if (!TextRouting.isReadableCyrillic(text)) {
                text = try {
                    recognizeRussianComicText(bitmap, box, fastMode = videoMode)?.first.orEmpty()
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    Log.w(PIPELINE_TAG, "Russian OCR failed (focus=ru)", t)
                    ""
                }
            }
        } else if (!TextRouting.focusUsesMlKitText(focus)) {
            val crop = bitmap.safeCrop(box)
            text = if (crop != null) {
                try {
                    tesseract.recognize(crop).regions.joinToString(" ") { it.text }.trim()
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    Log.w(PIPELINE_TAG, "Tesseract failed (focus=$focus)", t)
                    ""
                } finally {
                    crop.recycle()
                }
            } else ""
        }
        if (text.isEmpty()) return ResolvedRegion(focus, null)
        val translated = translateVisible(text, focus)
        Log.i(PIPELINE_TAG, "  [$focus/focus→en] \"$text\" → \"${translated ?: "?"}\" @ $box")
        return ResolvedRegion(focus, translated)
    }

    private suspend fun recognizeRussianComicText(
        bitmap: Bitmap,
        box: android.graphics.Rect,
        fastMode: Boolean = false,
    ): Pair<String, Int>? {
        fun candidate(result: TesseractRecognizer.Result): Pair<String, Int>? {
            val value = result.regions.joinToString(" ") { it.text }.trim()
            if (!TextRouting.isReadableCyrillic(value)) return null
            return value to result.confidence
        }
        fun embeddedDigit(value: String): Boolean = TextRouting.hasDigitNextToCyrillic(value)
        fun score(reading: Pair<String, Int>): Int =
            TextRouting.russianReadingScore(reading.first, reading.second)
        suspend fun read(padding: Int, threshold: Int?): Pair<String, Int>? {
            val crop = bitmap.safeCrop(box, paddingPx = padding) ?: return null
            try {
                val input = if (threshold == null) crop else crop.thresholdForOcr(threshold)
                try {
                    val worker = russianTesseracts[Math.floorMod(
                        box.left * 31 + box.top,
                        russianTesseracts.size,
                    )]
                    return candidate(worker.recognize(input))
                } finally {
                    if (input !== crop) input.recycle()
                }
            } finally {
                crop.recycle()
            }
        }

        val readings = mutableListOf<Pair<String, Int>>()
        read(12, null)?.let(readings::add)
        if ((readings.maxOfOrNull(::score) ?: 0) >= 85) return readings.first()
        read(12, 220)?.let(readings::add)
        if (fastMode) {
            return readings.maxByOrNull(::score)?.takeIf {
                score(it) >= 20 && !embeddedDigit(it.first)
            }
        }
        val best = readings.maxByOrNull(::score)
        if (best == null || embeddedDigit(best.first) || score(best) < 50) {
            read(8, 160)?.let(readings::add)
            val afterEight = readings.maxByOrNull(::score)
            if (afterEight == null || embeddedDigit(afterEight.first) || score(afterEight) < 50) {
                read(6, 160)?.let(readings::add)
            }
        }
        return readings.maxByOrNull(::score)?.takeIf {
            score(it) >= 20 && !embeddedDigit(it.first)
        }
    }

    private fun buildCardFrom(
        bitmap: Bitmap,
        box: android.graphics.Rect?,
        resolved: ResolvedRegion,
        videoMode: Boolean = false,
    ): OverlayWindow.TranslationCard? {
        val translated = resolved.translated ?: return null
        if (box == null) return null
        val bg = if (videoMode) CardStyle.VIDEO_BACKGROUND else CardStyle.sampleBackground(bitmap, box)
        val fg = if (videoMode) CardStyle.VIDEO_FOREGROUND else CardStyle.sampleForeground(bitmap, box, bg)
        val fontSp = CardStyle.fontSpFromRegionHeight(
            regionHeightPx = box.height(),
            density = resources.displayMetrics.density,
        )
        return OverlayWindow.TranslationCard(
            bounds = box,
            text = translated,
            bgColor = bg,
            textColor = fg,
            fontSp = fontSp,
            sourceLang = resolved.lang,
        )
    }

    private fun suggestLanguage(cards: List<OverlayWindow.TranslationCard>): String? {
        val counts = TextRouting.languageCounts(cards.map { it.sourceLang })
        val suggestion = TextRouting.suggestLanguage(counts)
        Log.i(
            PIPELINE_TAG,
            "language signal: counts=$counts → ${suggestion ?: "no suggestion"}",
        )
        return suggestion
    }

    private fun refreshLanguageChip() {
        val chip = languageChip ?: return
        val focus = focusedLanguage
        val suggestion = lastSuggestion
        when {
            focus != null -> chip.showLocked(focus)
            suggestion != null && suggestion !in dismissedSuggestions ->
                chip.showSuggestion(suggestion)
            else -> chip.hide()
        }
    }

    private fun deoverlap(
        cards: List<OverlayWindow.TranslationCard>,
    ): List<OverlayWindow.TranslationCard> {
        if (cards.size < 2) return cards
        val byArea = cards.sortedByDescending {
            it.bounds.width().toLong() * it.bounds.height().toLong()
        }
        val kept = mutableListOf<OverlayWindow.TranslationCard>()
        for (card in byArea) {
            val engulfed = kept.any { it.bounds.contains(card.bounds) }
            if (!engulfed) kept.add(card)
        }
        return kept
    }

    private suspend fun runFastPath(nodes: List<A11yTextNode>, ctx: PassContext): Int {
        if (!isCurrent(ctx)) return 1
        val trimmed = nodes.take(MAX_FAST_PATH_NODES)

        val fingerprint = trimmed.hashCode()
        if (fingerprint == lastA11yFingerprint && lastCards.isNotEmpty()) {
            Log.i(
                PIPELINE_TAG,
                "a11y unchanged (fp=$fingerprint, ${trimmed.size} node(s)); reusing ${lastCards.size} card(s)",
            )
            val overlayStart = System.currentTimeMillis()
            withContext(Dispatchers.Main) { overlay?.update(lastCards) }
            val overlayMs = System.currentTimeMillis() - overlayStart
            BenchmarkRecorder.record(
                FrameTrace(
                    tsMs = ctx.startMs,
                    pkg = ctx.pkg,
                    path = FrameTrace.Path.A11Y_HIT,
                    totalMs = System.currentTimeMillis() - ctx.startMs,
                    harvestMs = ctx.harvestMs,
                    rootMs = ctx.rootMs,
                    walkMs = ctx.walkMs,
                    overlayMs = overlayMs,
                    nRegions = trimmed.size,
                    nCards = lastCards.size,
                ),
            )
            return lastCards.size
        }
        lastA11yFingerprint = fingerprint

        val nmtStart = System.currentTimeMillis()
        val translatedCards = deoverlap(coroutineScope {
            trimmed.map { node ->
                async { resolveA11yNode(node) }
            }.awaitAll()
        }.filterNotNull())
        if (!isCurrent(ctx)) return 1
        val nmtMs = System.currentTimeMillis() - nmtStart

        val cachedCards = translatedCards.mapNotNull { card ->
            val style = synchronized(styleCache) { styleCache[styleKey(ctx.pkg, card)] }
            if (style == null) null else card.copy(
                bgColor = style.background,
                textColor = style.foreground,
            )
        }
        var cards = cachedCards
        var overlayMs = 0L
        if (translatedCards.isNotEmpty()) {
            val start = System.currentTimeMillis()
            val painted = withContext(Dispatchers.Main) {
                if (!isCurrent(ctx)) false else {
                    lastCards = cards
                    overlay?.update(cards)
                    true
                }
            }
            if (!painted) return 1
            overlayMs += System.currentTimeMillis() - start

            val hasStyleMiss = cachedCards.size < translatedCards.size
            if (hasStyleMiss) {
                if (!isCurrent(ctx)) return 1
                val bitmap = takeScreenshotSuspending()
                if (bitmap != null) {
                    try {
                        if (!isCurrent(ctx)) return cards.size
                        cards = translatedCards.map { card ->
                            val key = styleKey(ctx.pkg, card)
                            val style = synchronized(styleCache) { styleCache[key] }
                                ?: run {
                                    val bg = CardStyle.sampleBackground(bitmap, card.bounds)
                                    val fg = CardStyle.sampleForeground(bitmap, card.bounds, bg)
                                    TextStyle(bg, fg).also { sampled ->
                                        synchronized(styleCache) { styleCache[key] = sampled }
                                    }
                                }
                            card.copy(
                                bgColor = style.background,
                                textColor = style.foreground,
                            )
                        }
                    } finally {
                        bitmap.recycle()
                    }
                    val startRefine = System.currentTimeMillis()
                    withContext(Dispatchers.Main) {
                        if (isCurrent(ctx)) {
                            lastCards = cards
                            overlay?.update(cards)
                        }
                    }
                    overlayMs += System.currentTimeMillis() - startRefine
                }
            }
        }
        val ms = System.currentTimeMillis() - ctx.startMs
        Log.i(
            PIPELINE_TAG,
            "a11y fast-path ${trimmed.size} node(s) → ${cards.size} card(s) in ${ms}ms",
        )
        BenchmarkRecorder.record(
            FrameTrace(
                tsMs = ctx.startMs,
                pkg = ctx.pkg,
                path = FrameTrace.Path.A11Y_FULL,
                totalMs = ms,
                harvestMs = ctx.harvestMs,
                rootMs = ctx.rootMs,
                walkMs = ctx.walkMs,
                nmtMs = nmtMs,
                overlayMs = overlayMs,
                nRegions = trimmed.size,
                nCards = cards.size,
            ),
        )
        return cards.size
    }

    private suspend fun resolveA11yNode(node: A11yTextNode): OverlayWindow.TranslationCard? {
        val text = node.text
        val box = node.bounds
        if (box.width() < MIN_REGION_WIDTH_PX || box.height() < MIN_REGION_HEIGHT_PX) return null
        if (TextRouting.isNonTranslatable(text)) return null

        val cached = synchronized(resolvedCache) { resolvedCache[text] }
        val resolved = cached ?: run {
            val r = if (node.isVirtualLine && text.contains(" • ")) {
                val parts = text.split(" • ")
                val translatedParts = coroutineScope {
                    parts.map { part -> async { detectAndTranslate(part) } }.awaitAll()
                }
                if (translatedParts.any { it.translated != null }) {
                    ResolvedRegion("mixed", parts.indices.joinToString(" • ") { index ->
                        translatedParts[index].translated ?: parts[index]
                    })
                } else ResolvedRegion("en", null)
            } else detectAndTranslate(text)
            synchronized(resolvedCache) { resolvedCache[text] = r }
            r
        }
        val translated = resolved.translated ?: return null

        val pxPerSp = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, 1f, resources.displayMetrics,
        )
        val fontSp = if (node.isLauncherLabel) {
            (box.height() * 0.5f / pxPerSp).coerceIn(8f, MAX_A11Y_FONT_SP)
        } else if (node.isVirtualLine) {
            CardStyle.fontSpFromRegionHeight(
                regionHeightPx = box.height(),
                density = pxPerSp,
            )
        } else {
            (box.height() * 0.9f / pxPerSp)
                .coerceIn(8f, MAX_A11Y_FONT_SP)
        }.coerceAtMost(MAX_A11Y_FONT_SP)
        return OverlayWindow.TranslationCard(
            bounds = box,
            text = translated,
            sourceText = text,
            isAccessibilityText = true,
            isLauncherLabel = node.isLauncherLabel,
            isVirtualLine = node.isVirtualLine,
            fullWidth = node.fullWidth,
            bold = node.bold,
            bgColor = FAST_PATH_BG,
            textColor = FAST_PATH_FG,
            fontSp = fontSp,
        )
    }

    private suspend fun translateVisible(text: String, lang: String): String? {
        val translated = translator.translate(text, lang) ?: return null
        if (TextRouting.isNoOpTranslation(text, translated)) {
            Log.i(PIPELINE_TAG, "  [$lang→en] no-op \"$text\"; no card")
            return null
        }
        return translated
    }

    private suspend fun detectAndTranslate(text: String): ResolvedRegion {
        val lang = TextRouting.accessibilityLanguage(
            text,
            lid.detect(text).language,
            focusedLanguage.takeIf { EvalFlags.languageFocus },
        )
        return when {
            lang == LanguageDetector.UNDETERMINED -> ResolvedRegion(lang, null)
            lang == "en" -> ResolvedRegion(lang, null)
            else -> ResolvedRegion(lang, translateVisible(text, lang))
        }
    }

    override fun onInterrupt() {
        Log.i(TAG, "Accessibility service interrupted")
    }

    override fun onDestroy() {
        Log.i(TAG, "Accessibility service destroyed")
        overlay?.hide()
        overlay = null
        languageChip?.destroy()
        languageChip = null
        scope.cancel()
        ocr.close()
        lid.close()
        tesseract.close()
        russianTesseracts.forEach(TesseractRecognizer::close)
        screenshotExecutor.shutdown()
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ScreenTranslatorA11y"
        private const val PIPELINE_TAG = "ScreenTranslatorPipe"
        private const val MAX_LOG_LINES = 120
        private const val SCREENSHOT_HIDE_DELAY_MS = 96L
        private const val SCROLL_SETTLE_MS = 120L
        private const val VIDEO_OCR_MAX_LONG_SIDE = 1600
        private const val RUSSIAN_OCR_WORKERS = 2

        private const val MIN_REGION_WIDTH_PX = 24
        private const val MIN_REGION_HEIGHT_PX = 12

        private const val RESOLVED_CACHE_MAX = 512

        private const val MIN_A11Y_NODES_FAST_PATH = 15
        private val IMAGE_VIEWER_PACKAGES = setOf(
            "com.google.android.apps.photos",
            "com.android.gallery3d",
        )
        private val VIDEO_PACKAGES = setOf(
            "com.google.android.youtube",
            "com.instagram.android",
            "com.zhiliaoapp.musically",
        )

        private const val MAX_FAST_PATH_NODES = 80
        private const val SCREENSHOT_MIN_INTERVAL_MS = 500L

        private const val FAST_PATH_BG = 0xDD000000.toInt()
        private const val FAST_PATH_FG = 0xFFFFFFFF.toInt()
        private const val MAX_A11Y_FONT_SP = 22f

        @Volatile
        var instance: ScreenTranslatorAccessibilityService? = null
            private set
    }
}
