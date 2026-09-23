package com.screentranslator.android.pipeline

object EvalFlags {
    @Volatile var a11yFastPath = true

    @Volatile var multiScaleOcr = true

    @Volatile var tesseractFallback = true

    @Volatile var languageFocus = true

    fun describe(): String =
        "a11y=${a11yFastPath.bit()} multiscale=${multiScaleOcr.bit()} " +
            "tesseract=${tesseractFallback.bit()} focus=${languageFocus.bit()}"

    private fun Boolean.bit() = if (this) 1 else 0
}
