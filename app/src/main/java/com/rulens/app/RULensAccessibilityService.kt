package com.rulens.app

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView

class RULensAccessibilityService : AccessibilityService() {
    private lateinit var wm: WindowManager
    private var bubble: TextView? = null
    private val translationViews = mutableListOf<View>()
    private var translated = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        showBubble()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Deliberately no continuous capture/storage.
        // Translation happens only when the user taps RU.
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        clearTranslations()
        bubble?.let { runCatching { wm.removeView(it) } }
        bubble = null
        super.onDestroy()
    }

    private fun showBubble() {
        if (bubble != null) return

        val density = resources.displayMetrics.density
        val size = (56 * density).toInt()
        val view = TextView(this).apply {
            text = "RU"
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = rounded(Color.rgb(35, 35, 35), 28f * density)
            elevation = 12f * density
        }

        val lp = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = (12 * density).toInt()
            y = (160 * density).toInt()
        }

        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false

        view.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startX = lp.x
                    startY = lp.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (kotlin.math.abs(dx) > 8 || kotlin.math.abs(dy) > 8) moved = true
                    lp.x = startX - dx.toInt() // END gravity: positive x moves left
                    lp.y = startY + dy.toInt()
                    wm.updateViewLayout(view, lp)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) toggleTranslation()
                    true
                }
                else -> false
            }
        }

        bubble = view
        wm.addView(view, lp)
    }

    private fun toggleTranslation() {
        if (translated) {
            clearTranslations()
            translated = false
            bubble?.text = "RU"
            return
        }

        clearTranslations()
        val root = rootInActiveWindow ?: return
        collect(root)
        translated = translationViews.isNotEmpty()
        bubble?.text = if (translated) "×" else "RU"
    }

    private fun collect(node: AccessibilityNodeInfo) {
        val text = listOf(node.text, node.contentDescription)
            .firstOrNull { !it.isNullOrBlank() }
            ?.toString()
            ?.trim()

        if (!text.isNullOrBlank()) {
            val ru = BankDictionary.translate(text)
            if (!ru.isNullOrBlank() && ru != text) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                if (rect.width() > 8 && rect.height() > 8 && rect.top >= 0) {
                    showTranslation(rect, ru)
                }
            }
        }

        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                collect(child)
                child.recycle()
            }
        }
    }

    private fun showTranslation(bounds: Rect, translatedText: String) {
        val density = resources.displayMetrics.density
        val pad = (5 * density).toInt()
        val tv = TextView(this).apply {
            text = translatedText
            textSize = 13f
            setTextColor(Color.WHITE)
            setPadding(pad, pad / 2, pad, pad / 2)
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(Color.argb(235, 25, 25, 25), 8f * density)
            maxLines = 3
        }

        val minH = (28 * density).toInt()
        val width = bounds.width().coerceAtLeast((70 * density).toInt())
        val height = bounds.height().coerceAtLeast(minH)
        val lp = WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = bounds.left
            y = bounds.top
        }

        runCatching {
            wm.addView(tv, lp)
            translationViews += tv
        }
    }

    private fun clearTranslations() {
        translationViews.forEach { runCatching { wm.removeView(it) } }
        translationViews.clear()
    }

    private fun rounded(color: Int, radius: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
    }
}
