package com.screentranslator.android.pipeline

import com.screentranslator.android.pipeline.TextRouting.SuspectReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextRoutingTest {
    @Test
    fun nonTranslatable_rejectsUrlsTimesEmailsAndAmounts() {
        listOf(
            "https://example.com/page",
            "www.example.com",
            "es.wikipedia.org/wiki",
            "user@mail.ru",
            "5:22",
            "12:30:05",
            "8:34 AM",
            "390",
            "12,50 €",
            "-15%",
        ).forEach { assertTrue(it, TextRouting.isNonTranslatable(it)) }
    }

    @Test
    fun nonTranslatable_keepsTextMixedWithNumbers() {
        listOf("168 гр 390", "Капучино", "Settings", "Menú del día", "5 minutes")
            .forEach { assertFalse(it, TextRouting.isNonTranslatable(it)) }
    }

    @Test
    fun garbled_flagsCyrillicReadAsLatin() {
        listOf("TpOMKOcTb", "BM6pooTKIMK", "AHTHpacT", "PaIOHoe")
            .forEach { assertTrue(it, TextRouting.looksLikeGarbledOcr(it)) }
    }

    @Test
    fun garbled_passesOrdinaryText() {
        listOf("Settings", "Display and brightness", "COFFEE", "Wi-Fi", "Café au lait", "3D", "A1")
            .forEach { assertFalse(it, TextRouting.looksLikeGarbledOcr(it)) }
    }

    @Test
    fun garbled_knownFalsePositiveOnCamelCaseBrands() {
        assertTrue(TextRouting.looksLikeGarbledOcr("PayPal"))
        assertTrue(TextRouting.looksLikeGarbledOcr("YouTube"))
    }

    @Test
    fun readableCyrillic_requiresMostlyCyrillicLetters() {
        assertTrue(TextRouting.isReadableCyrillic("Батарея"))
        assertTrue(TextRouting.isReadableCyrillic("Батарея 100%"))
    }

    @Test
    fun readableCyrillic_rejectsMixedOrLatinText() {
        assertFalse(TextRouting.isReadableCyrillic("Wi-Fi и сеть"))
        assertFalse(TextRouting.isReadableCyrillic("Б"))
        assertFalse(TextRouting.isReadableCyrillic("Settings"))
        assertFalse(TextRouting.isReadableCyrillic("Hello Бa"))
    }

    @Test
    fun suspectReason_appliesFourTriggersInOrder() {
        assertEquals(SuspectReason.UNDETERMINED, TextRouting.suspectReason("Hola", "und", 0f))
        assertEquals(SuspectReason.UNEXPECTED_LANGUAGE, TextRouting.suspectReason("Tee", "et", 0.99f))
        assertEquals(SuspectReason.LOW_CONFIDENCE, TextRouting.suspectReason("Batapeя", "en", 0.88f))
        assertEquals(SuspectReason.GARBLED, TextRouting.suspectReason("TpOMKOcTb", "en", 0.99f))
        assertNull(TextRouting.suspectReason("Settings", "en", 0.99f))
    }

    @Test
    fun suspectReason_confidenceFloorIsInclusive() {
        assertNull(TextRouting.suspectReason("Settings", "en", TextRouting.LOW_CONFIDENCE_FLOOR))
        assertEquals(
            SuspectReason.LOW_CONFIDENCE,
            TextRouting.suspectReason("Settings", "en", 0.919f),
        )
    }

    @Test
    fun acceptTesseractReading_prefersCyrillicEvidence() {
        assertEquals("ru", TextRouting.acceptTesseractReading("Батарея", "en", 0.3f))
        assertEquals("es", TextRouting.acceptTesseractReading("Hola amigos", "es", 0.95f))
        assertNull(TextRouting.acceptTesseractReading("xqzt", "et", 0.99f))
        assertNull(TextRouting.acceptTesseractReading("Hola", "es", 0.5f))
    }

    @Test
    fun acceptRussianReading_needsConfidenceAndFourCyrillicLetters() {
        assertTrue(TextRouting.acceptRussianReading("Привет", 20))
        assertFalse(TextRouting.acceptRussianReading("Привет", 19))
        assertFalse(TextRouting.acceptRussianReading("При", 90))
    }

    @Test
    fun videoCaption_excludesBottomControlsAndSmallLabels() {
        val frameHeight = 2400
        assertTrue(TextRouting.isLikelyVideoCaption(top = 1000, width = 400, height = 60, frameHeight = frameHeight))
        assertFalse(TextRouting.isLikelyVideoCaption(top = 1900, width = 400, height = 60, frameHeight = frameHeight))
        assertFalse(TextRouting.isLikelyVideoCaption(top = 1000, width = 100, height = 60, frameHeight = frameHeight))
        assertFalse(TextRouting.isLikelyVideoCaption(top = 1000, width = 400, height = 20, frameHeight = frameHeight))
    }

    @Test
    fun russianReadingScore_penalisesDigitsInsideCyrillicWords() {
        assertTrue(TextRouting.hasDigitNextToCyrillic("Д0БР0"))
        assertFalse(TextRouting.hasDigitNextToCyrillic("Цена 390"))
        assertEquals(80, TextRouting.russianReadingScore("ДОБРО", 80))
        assertEquals(20, TextRouting.russianReadingScore("Д0БРО", 80))
    }

    @Test
    fun accessibilityLanguage_fixesLidScriptBlindSpot() {
        assertEquals("ru", TextRouting.accessibilityLanguage("Настройки", "und", null))
        assertEquals("uk", TextRouting.accessibilityLanguage("Налаштування", "en", "uk"))
        assertEquals("ru", TextRouting.accessibilityLanguage("Настройки", "en", "es"))
        assertEquals("bg", TextRouting.accessibilityLanguage("Настройки", "bg", null))
        assertEquals("es", TextRouting.accessibilityLanguage("Hola", "es", null))
    }

    @Test
    fun suggestLanguage_needsThreeCardsAndTwiceTheRunnerUp() {
        fun suggest(vararg langs: String) =
            TextRouting.suggestLanguage(TextRouting.languageCounts(langs.toList()))
        assertEquals("ru", suggest("ru", "ru", "ru", "en", "en", "es"))
        assertNull(suggest("ru", "ru", "es"))
        assertNull(suggest("ru", "ru", "ru", "es", "es"))
        assertEquals("ru", suggest("ru", "ru", "ru", "ru", "es", "es"))
        assertNull(suggest("en", "en", "en", "und", "mixed"))
    }

    @Test
    fun noOpTranslation_ignoresCaseAndPunctuation() {
        assertTrue(TextRouting.isNoOpTranslation("Subscribe ,", "Subscribe,"))
        assertTrue(TextRouting.isNoOpTranslation("@aghamarshan", "@aghamarshan"))
        assertTrue(TextRouting.isNoOpTranslation("простознакомая:", "простознакомая:"))
        assertFalse(TextRouting.isNoOpTranslation("Auto-dubbed", "Self-dubbed"))
        assertFalse(TextRouting.isNoOpTranslation("сёстры дома", "sisters at home"))
    }

    @Test
    fun focusDecision_skipsConfidentEnglish() {
        assertEquals(
            TextRouting.FocusDecision.SKIP_ENGLISH,
            TextRouting.focusDecision("es", "Subscribe", "en", 0.99f),
        )
        assertEquals(
            TextRouting.FocusDecision.APPLY,
            TextRouting.focusDecision("ru", "HAnNTKN", "en", 0.95f),
        )
        assertEquals(
            TextRouting.FocusDecision.APPLY,
            TextRouting.focusDecision("ru", "Settings", "en", 0.80f),
        )
    }

    @Test
    fun focusDecision_routesCyrillicUnderLatinFocusAutomatically() {
        assertEquals(
            TextRouting.FocusDecision.AUTO,
            TextRouting.focusDecision("es", "лучшая подруга", null, 0f),
        )
        assertEquals(
            TextRouting.FocusDecision.APPLY,
            TextRouting.focusDecision("ru", "лучшая подруга", null, 0f),
        )
        assertEquals(
            TextRouting.FocusDecision.APPLY,
            TextRouting.focusDecision("es", "que come como", "es", 0.9f),
        )
    }

    @Test
    fun russianReader_needsCyrillicEvidence() {
        assertTrue(TextRouting.russianReaderFirst("пучшая подруга", "und", 0f))
        assertTrue(TextRouting.russianReaderFirst("Pепа", "und", 0f))
        assertFalse(TextRouting.russianReaderFirst("que come como", "es", 0.9f))
        assertTrue(TextRouting.russianReaderAfter("НАПИТКИ"))
        assertTrue(TextRouting.russianReaderAfter("жЖизнЫбезюлуб"))
        assertFalse(TextRouting.russianReaderAfter("que come сomo"))
        assertFalse(TextRouting.russianReaderAfter("que come como"))
        assertFalse(TextRouting.russianReaderAfter("((earhiners arietaistuta 5 Г ГИ Та"))
    }

    @Test
    fun russianReaderFirst_treatsLookalikeLatinAsEvidence() {
        assertTrue(TextRouting.russianReaderFirst("KusHb 6es toryő", "hu", 0.7f))
        assertTrue(TextRouting.russianReaderFirst("A OH Tak M.He y3HaeT Kak", "und", 0f))
        assertTrue(TextRouting.russianReaderFirst("npaBunbHO noAKaTbIBatb", "eu", 0.6f))
        assertFalse(TextRouting.russianReaderFirst("The delicate dita que se", "und", 0f))
        assertFalse(TextRouting.russianReaderFirst("que come com0", "es", 0.8f))
        assertFalse(TextRouting.russianReaderFirst("The delicatecOme Como", "en", 0.61f))
    }

    @Test
    fun rereads_rejectGarbledCyrillic() {
        assertNull(TextRouting.acceptTesseractReading("ТПеаеПсате )", "mk", 0.64f))
        assertFalse(TextRouting.acceptRussianReading("ЮоуТиБе канал", 60))
        assertEquals("ru", TextRouting.acceptTesseractReading("установите и настройте", "ru", 0.95f))
        assertTrue(TextRouting.acceptRussianReading("А он так и не узнает как", 70))
    }

    @Test
    fun focusReader_keepsMlKitTextForLatinFocus() {
        assertTrue(TextRouting.focusUsesMlKitText("es"))
        assertTrue(TextRouting.focusUsesMlKitText("de"))
        assertFalse(TextRouting.focusUsesMlKitText("ru"))
        assertFalse(TextRouting.focusUsesMlKitText("uk"))
    }

    @Test
    fun credibleLatinReading_blocksCyrillicOverride() {
        assertTrue(TextRouting.latinReadingIsCredible("La que Come como", "es", 0.78f))
        assertNull(TextRouting.acceptTesseractReading("ajque соте сото", "mk", 0.93f, mlKitLatinCredible = true))
        assertEquals("ru", TextRouting.acceptTesseractReading("ajque соте сото", "mk", 0.93f))
        assertFalse(TextRouting.latinReadingIsCredible("KusHb 6es toryő", "hu", 0.7f))
        assertFalse(TextRouting.latinReadingIsCredible("пучшая подруга", "und", 0f))
        assertFalse(TextRouting.latinReadingIsCredible("La que Come como", "es", 0.4f))
    }
}
