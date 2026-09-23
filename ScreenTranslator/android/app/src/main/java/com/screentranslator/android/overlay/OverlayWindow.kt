package com.screentranslator.android.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.getSystemService

class OverlayWindow(private val context: Context) {
    private val windowManager: WindowManager = context.getSystemService()!!
    private var container: FrameLayout? = null
    private var cardsRoot: FrameLayout? = null

    fun show() {
        if (container != null) return

        val root = FrameLayout(context).apply {
            fitsSystemWindows = false
            setOnApplyWindowInsetsListener { _, _ -> WindowInsets.CONSUMED }
        }
        val cards = FrameLayout(context).apply {
            clipChildren = false
            clipToPadding = false
        }
        root.addView(
            cards,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        cardsRoot = cards

        val dot = View(context).apply { setBackgroundColor(Color.RED) }
        val dotSize = dp(8)
        root.addView(
            dot,
            FrameLayout.LayoutParams(dotSize, dotSize).apply {
                gravity = Gravity.TOP or Gravity.END
                topMargin = dp(48)
                rightMargin = dp(12)
            },
        )

        val bounds = windowManager.currentWindowMetrics.bounds
        val params = WindowManager.LayoutParams(
            bounds.width(),
            bounds.height(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        windowManager.addView(root, params)
        container = root

        root.post {
            val loc = IntArray(2)
            root.getLocationOnScreen(loc)
            Log.i(
                TAG,
                "Overlay geometry: display=${bounds.width()}x${bounds.height()}, " +
                    "root=${root.width}x${root.height} @ (${loc[0]}, ${loc[1]})",
            )
        }
    }

    fun update(cards: List<TranslationCard>) {
        val root = cardsRoot ?: return
        val overlayW = root.width.takeIf { it > 0 }
            ?: context.resources.displayMetrics.widthPixels
        val overlayH = root.height.takeIf { it > 0 }
            ?: context.resources.displayMetrics.heightPixels

        val cornerRadiusPx = dp(CARD_CORNER_DP).toFloat()

        while (root.childCount < cards.size) {
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = cornerRadiusPx
            }
            val tv = TextView(context).apply {
                background = bg
                elevation = 0f
                includeFontPadding = false
                maxLines = Int.MAX_VALUE
                ellipsize = null
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
            }
            root.addView(
                tv,
                FrameLayout.LayoutParams(0, 0),
            )
        }

        for (i in cards.indices) {
            val card = cards[i]
            val tv = root.getChildAt(i) as TextView
            if (tv.tag == card && tv.visibility == View.VISIBLE) continue
            tv.tag = card
            val horizontalEdgePx = if (card.isLauncherLabel) 0 else
                dp(if (card.isVirtualLine) VIRTUAL_EDGE_COVER_DP else EDGE_COVER_DP)
            val verticalEdgePx = if (card.isAccessibilityText) 0 else horizontalEdgePx
            tv.visibility = View.VISIBLE
            tv.text = card.text
            tv.gravity = if (card.isLauncherLabel) Gravity.CENTER else Gravity.CENTER_VERTICAL or Gravity.START
            tv.setTypeface(Typeface.DEFAULT, if (card.bold) Typeface.BOLD else Typeface.NORMAL)
            tv.setTextColor(card.textColor)
            val bg = tv.background as? GradientDrawable
                ?: GradientDrawable().also { tv.background = it }
            bg.setColor(card.bgColor)
            bg.cornerRadius = cornerRadiusPx
            tv.setPadding(horizontalEdgePx, verticalEdgePx, horizontalEdgePx, verticalEdgePx)
            val lp = tv.layoutParams as FrameLayout.LayoutParams
            val rawW = (card.bounds.width() + 2 * horizontalEdgePx).coerceAtLeast(dp(24))
            val rawH = (card.bounds.height() + 2 * verticalEdgePx).coerceAtLeast(dp(20))
            val rawLeft = (card.bounds.left - horizontalEdgePx).coerceAtLeast(0)
            val rawTop = (card.bounds.top - verticalEdgePx).coerceAtLeast(0)
            lp.width = minOf(rawW, overlayW - rawLeft).coerceAtLeast(dp(24))
            lp.height = minOf(rawH, overlayH - rawTop).coerceAtLeast(dp(20))
            lp.leftMargin = rawLeft
            lp.topMargin = rawTop
            tv.layoutParams = lp
            val maxSp = card.fontSp.coerceAtLeast(MIN_RENDER_SP)
            if (card.isVirtualLine && !card.fullWidth && card.sourceText.isNotEmpty()) {
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    textSize = TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_SP,
                        maxSp,
                        context.resources.displayMetrics,
                    )
                    typeface = tv.typeface
                }
                val sourceWidth = card.sourceText.lineSequence()
                    .maxOf { paint.measureText(it).toInt() }
                val targetWidth = card.text.lineSequence()
                    .maxOf { paint.measureText(it).toInt() }
                lp.width = minOf(lp.width, maxOf((sourceWidth * 1.5f).toInt(), targetWidth) + 2 * horizontalEdgePx)
                    .coerceAtLeast(dp(24))
                tv.layoutParams = lp
            }
            tv.setAutoSizeTextTypeUniformWithConfiguration(
                MIN_RENDER_SP.toInt(),
                maxSp.toInt().coerceAtLeast(MIN_RENDER_SP.toInt()),
                1,
                TypedValue.COMPLEX_UNIT_SP,
            )
        }

        for (i in cards.size until root.childCount) {
            root.getChildAt(i).visibility = View.GONE
        }
    }

    fun hide() {
        val root = container
        container = null
        cardsRoot = null
        if (root != null) runCatching { windowManager.removeView(root) }
    }

    fun setCardsInvisible(invisible: Boolean) {
        cardsRoot?.apply {
            alpha = if (invisible) 0f else 1f
            visibility = if (invisible) View.INVISIBLE else View.VISIBLE
        }
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        context.resources.displayMetrics,
    ).toInt()

    data class TranslationCard(
        val bounds: Rect,
        val text: String,
        val bgColor: Int = Color.argb(220, 0, 0, 0),
        val textColor: Int = Color.WHITE,
        val fontSp: Float = 12f,
        val sourceText: String = "",
        val isAccessibilityText: Boolean = false,
        val isLauncherLabel: Boolean = false,
        val isVirtualLine: Boolean = false,
        val fullWidth: Boolean = false,
        val bold: Boolean = false,
        val sourceLang: String = "und",
    )

    companion object {
        private const val TAG = "ScreenTranslatorA11y"

        private const val EDGE_COVER_DP = 8
        private const val VIRTUAL_EDGE_COVER_DP = 2
        private const val MIN_RENDER_SP = 7f

        private const val CARD_CORNER_DP = 0
    }
}
