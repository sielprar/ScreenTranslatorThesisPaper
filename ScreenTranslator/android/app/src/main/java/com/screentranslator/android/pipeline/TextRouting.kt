package com.screentranslator.android.pipeline

object TextRouting {
    const val LOW_CONFIDENCE_FLOOR = 0.92f

    val EXPECTED_LANGUAGES: Set<String> = setOf(
        "en", "ru", "uk", "bg", "sr", "es", "fr", "pt", "it", "de", "pl",
    )

    private val CYRILLIC_LANGUAGES = setOf("ru", "uk", "bg", "sr")

    private const val VIDEO_CONTENT_BOTTOM_RATIO = 0.76f
    private const val VIDEO_TESSERACT_MIN_WIDTH_PX = 140
    private const val VIDEO_TESSERACT_MIN_HEIGHT_PX = 24

    private val NON_TRANSLATABLE_PATTERNS: List<Regex> = listOf(
        Regex("^\\S*(?:https?://|www\\.)\\S+$", RegexOption.IGNORE_CASE),
        Regex(
            "^[A-Za-z0-9.-]+\\.(?:com|org|net|io|edu|gov|ru|de|fr|es|jp|cn|uk|co)(?:/\\S*)?$",
            RegexOption.IGNORE_CASE,
        ),
        Regex("^\\S+@\\S+\\.\\S+$"),
        Regex("^\\d{1,2}:\\d{2}(?::\\d{2})?(?:\\s?[AaPp][Mm])?$"),
        Regex("^[\\-+]?[\\d.,\\s%\\$€₽¥£]+$"),
    )

    private val TOKEN_SEPARATORS = Regex("[\\s,.!?;:\"'()\\[\\]{}«»…\\-]+")

    fun isCyrillic(c: Char): Boolean = c in 'Ѐ'..'ӿ'

    fun cyrillicCount(text: String): Int = text.count(::isCyrillic)

    fun isReadableCyrillic(text: String): Boolean {
        val letters = text.count(Char::isLetter)
        val cyrillic = cyrillicCount(text)
        return cyrillic >= 2 && cyrillic * 4 >= letters * 3
    }

    fun isNonTranslatable(text: String): Boolean =
        NON_TRANSLATABLE_PATTERNS.any { it.matches(text) }

    fun looksLikeGarbledOcr(text: String): Boolean {
        for (token in text.split(TOKEN_SEPARATORS)) {
            if (token.length < 3) continue
            val letters = token.count { it.isLetter() }
            val digits = token.count { it.isDigit() }
            if (letters >= 2 && digits in 1..(letters - 1)) return true

            val letterOnly = token.filter { it.isLetter() }
            if (letterOnly.length >= 4) {
                var flips = 0
                for (i in 1 until letterOnly.length) {
                    if (letterOnly[i].isUpperCase() != letterOnly[i - 1].isUpperCase()) flips++
                }
                if (flips >= 2) return true
            }
        }
        return false
    }

    enum class SuspectReason(val label: String) {
        UNDETERMINED("und"),
        UNEXPECTED_LANGUAGE("unexpected"),
        LOW_CONFIDENCE("low-conf"),
        GARBLED("garbled"),
    }

    fun suspectReason(text: String, lang: String, confidence: Float): SuspectReason? = when {
        lang == LanguageDetector.UNDETERMINED -> SuspectReason.UNDETERMINED
        lang !in EXPECTED_LANGUAGES -> SuspectReason.UNEXPECTED_LANGUAGE
        confidence < LOW_CONFIDENCE_FLOOR -> SuspectReason.LOW_CONFIDENCE
        looksLikeGarbledOcr(text) -> SuspectReason.GARBLED
        else -> null
    }

    fun isTrustedLanguage(lang: String, confidence: Float): Boolean =
        lang in EXPECTED_LANGUAGES && confidence >= LOW_CONFIDENCE_FLOOR

    fun acceptTesseractReading(
        text: String,
        lang: String,
        confidence: Float,
        mlKitLatinCredible: Boolean = false,
    ): String? = when {
        looksLikeGarbledOcr(text) -> null
        cyrillicCount(text) >= 3 -> if (mlKitLatinCredible) null else "ru"
        isTrustedLanguage(lang, confidence) -> lang
        else -> null
    }

    fun acceptRussianReading(text: String, confidence: Int): Boolean =
        confidence >= 20 && cyrillicCount(text) >= 4 && !looksLikeGarbledOcr(text)

    fun isLikelyVideoCaption(top: Int, width: Int, height: Int, frameHeight: Int): Boolean =
        top < frameHeight * VIDEO_CONTENT_BOTTOM_RATIO &&
            width >= VIDEO_TESSERACT_MIN_WIDTH_PX &&
            height >= VIDEO_TESSERACT_MIN_HEIGHT_PX

    fun hasDigitNextToCyrillic(text: String): Boolean = text.indices.any { index ->
        text[index].isDigit() && (
            (index > 0 && isCyrillic(text[index - 1])) ||
                (index + 1 < text.length && isCyrillic(text[index + 1]))
            )
    }

    fun russianReadingScore(text: String, confidence: Int): Int =
        confidence - if (hasDigitNextToCyrillic(text)) 60 else 0

    fun accessibilityLanguage(text: String, lidLanguage: String, focus: String?): String =
        if (cyrillicCount(text) >= 2 &&
            (lidLanguage == LanguageDetector.UNDETERMINED || lidLanguage == "en")
        ) {
            focus?.takeIf { it in CYRILLIC_LANGUAGES } ?: "ru"
        } else lidLanguage

    fun languageCounts(sourceLanguages: List<String>): Map<String, Int> =
        sourceLanguages
            .filter { it in EXPECTED_LANGUAGES && it != "en" }
            .groupingBy { it }
            .eachCount()

    fun suggestLanguage(counts: Map<String, Int>): String? {
        val (top, topCount) = counts.maxByOrNull { it.value } ?: return null
        val runnerUp = counts.filterKeys { it != top }.values.maxOrNull() ?: 0
        return if (topCount >= 3 && topCount >= runnerUp * 2) top else null
    }

    fun normalizeForComparison(text: String): String =
        text.lowercase().filter { it.isLetterOrDigit() }

    fun isNoOpTranslation(source: String, translated: String): Boolean =
        normalizeForComparison(source) == normalizeForComparison(translated)

    fun isConfidentEnglish(text: String, lang: String, confidence: Float): Boolean =
        lang == "en" && confidence >= LOW_CONFIDENCE_FLOOR && !looksLikeGarbledOcr(text)

    enum class FocusDecision { APPLY, SKIP_ENGLISH, AUTO }

    fun focusDecision(
        focus: String,
        text: String,
        lidLanguage: String?,
        lidConfidence: Float,
    ): FocusDecision = when {
        isReadableCyrillic(text) ->
            if (focus in CYRILLIC_LANGUAGES) FocusDecision.APPLY else FocusDecision.AUTO
        lidLanguage != null && isConfidentEnglish(text, lidLanguage, lidConfidence) ->
            FocusDecision.SKIP_ENGLISH
        else -> FocusDecision.APPLY
    }

    fun russianReaderFirst(mlKitText: String, lidLanguage: String, lidConfidence: Float): Boolean =
        cyrillicCount(mlKitText) >= 1 || (
            looksLikeGarbledOcr(mlKitText) &&
                !latinReadingIsCredible(mlKitText, lidLanguage, lidConfidence)
            )

    fun latinReadingIsCredible(mlKitText: String, lidLanguage: String, lidConfidence: Float): Boolean =
        cyrillicCount(mlKitText) == 0 &&
            lidLanguage in LATIN_LANGUAGES &&
            lidConfidence >= LATIN_EVIDENCE_FLOOR

    private val LATIN_LANGUAGES = EXPECTED_LANGUAGES - CYRILLIC_LANGUAGES

    private const val LATIN_EVIDENCE_FLOOR = 0.5f

    fun focusUsesMlKitText(focus: String): Boolean = focus !in CYRILLIC_LANGUAGES

    fun russianReaderAfter(engRusReading: String): Boolean = isReadableCyrillic(engRusReading)
}
