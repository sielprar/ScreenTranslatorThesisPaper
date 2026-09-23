package com.screentranslator.android.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.core.content.getSystemService

class LanguageFocusOverlay(private val context: Context) {
    interface Callbacks {
        fun onConfirm(languageCode: String)
        fun onPick(languageCode: String)
        fun onClear()
    }

    var callbacks: Callbacks? = null

    private val windowManager: WindowManager = context.getSystemService()!!
    private var container: FrameLayout? = null
    private var state: State = State.Hidden

    private sealed class State {
        object Hidden : State()
        data class Suggestion(val code: String, val name: String) : State()
        data class Locked(val code: String, val name: String) : State()
    }

    fun showSuggestion(languageCode: String) {
        val newState = State.Suggestion(languageCode, languageName(languageCode))
        if (newState == state) return
        state = newState
        ensureWindow()
        render()
    }

    fun showLocked(languageCode: String) {
        val newState = State.Locked(languageCode, languageName(languageCode))
        if (newState == state) return
        state = newState
        ensureWindow()
        render()
    }

    fun hide() {
        if (state is State.Hidden) return
        state = State.Hidden
        val root = container
        container = null
        if (root != null) runCatching { windowManager.removeView(root) }
    }

    fun destroy() = hide()

    fun setTemporarilyInvisible(invisible: Boolean) {
        container?.visibility = if (invisible) View.INVISIBLE else View.VISIBLE
    }

    private fun ensureWindow() {
        if (container != null) return
        val root = FrameLayout(context)
        val lp = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(12)
            y = dp(64)
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        windowManager.addView(root, lp)
        container = root
    }

    private fun render() {
        val root = container ?: return
        root.removeAllViews()
        when (val s = state) {
            State.Hidden -> hide()
            is State.Suggestion -> root.addView(renderSuggestion(s))
            is State.Locked -> root.addView(renderLocked(s))
        }
    }

    private fun renderSuggestion(s: State.Suggestion): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = pillBackground(fill = 0xEE202020.toInt())
            setPadding(dp(12), dp(6), dp(6), dp(6))
            elevation = dp(4).toFloat()
        }
        val label = TextView(context).apply {
            text = "${s.name}?"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        }
        row.addView(
            label,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = dp(8) },
        )
        row.addView(makeIconButton("✓", 0xFF4CAF50.toInt()) {
            callbacks?.onConfirm(s.code)
        })
        row.addView(makeIconButton("✗", 0xFFF44336.toInt()) {
            openDropdown()
        })
        return row
    }

    private fun renderLocked(s: State.Locked): View {
        val chip = TextView(context).apply {
            text = "${flag(s.code)} ${s.code.uppercase()}"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            background = pillBackground(fill = 0xDD1B5E20.toInt())
            setPadding(dp(10), dp(6), dp(10), dp(6))
            elevation = dp(2).toFloat()
            setOnClickListener { openDropdown() }
        }
        return chip
    }

    private fun openDropdown() {
        val root = container ?: return
        val anchor = if (root.childCount > 0) root.getChildAt(0) else root
        val menu = PopupMenu(context, anchor)
        for ((idx, code) in SUPPORTED_LANGUAGES.withIndex()) {
            menu.menu.add(0, idx, idx, languageName(code))
        }
        menu.menu.add(0, CLEAR_ID, SUPPORTED_LANGUAGES.size, "None (auto-detect)")
        menu.setOnMenuItemClickListener { item ->
            if (item.itemId == CLEAR_ID) {
                callbacks?.onClear()
            } else {
                callbacks?.onPick(SUPPORTED_LANGUAGES[item.itemId])
            }
            true
        }
        menu.show()
    }

    private fun makeIconButton(text: String, tint: Int, onClick: () -> Unit): View =
        TextView(context).apply {
            this.text = text
            setTextColor(tint)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(32))
        }

    private fun pillBackground(fill: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(18).toFloat()
        setColor(fill)
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        context.resources.displayMetrics,
    ).toInt()

    companion object {
        val SUPPORTED_LANGUAGES: List<String> = listOf(
            "ru", "en", "uk", "bg", "sr", "es", "fr", "pt", "it", "de", "pl",
        )

        private const val CLEAR_ID = -1

        private fun languageName(code: String): String = when (code) {
            "en" -> "English"
            "ru" -> "Russian"
            "uk" -> "Ukrainian"
            "bg" -> "Bulgarian"
            "sr" -> "Serbian"
            "es" -> "Spanish"
            "fr" -> "French"
            "pt" -> "Portuguese"
            "it" -> "Italian"
            "de" -> "German"
            "pl" -> "Polish"
            else -> code.uppercase()
        }

        private fun flag(code: String): String = when (code) {
            "en" -> "🇬🇧"
            "ru" -> "🇷🇺"
            "uk" -> "🇺🇦"
            "bg" -> "🇧🇬"
            "sr" -> "🇷🇸"
            "es" -> "🇪🇸"
            "fr" -> "🇫🇷"
            "pt" -> "🇵🇹"
            "it" -> "🇮🇹"
            "de" -> "🇩🇪"
            "pl" -> "🇵🇱"
            else -> "🏳"
        }
    }
}
