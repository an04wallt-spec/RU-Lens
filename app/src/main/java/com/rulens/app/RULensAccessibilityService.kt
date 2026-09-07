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
    private var statusView: TextView? = null
    private var translated = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        showBubble()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // No continuous capture or storage. Translation only on RU tap.
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        clearTranslations()
        clearStatus()
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
                    lp.x = startX - dx.toInt()
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
            clearStatus()
            translated = false
            bubble?.text = "RU"
            return
        }

        clearTranslations()
        clearStatus()

        val stats = ScanStats()
        val seenPackages = linkedSetOf<String>()
        var scannedRoots = 0

        val availableWindows = windows
        for (window in availableWindows) {
            val root = window.root ?: continue
            val pkg = root.packageName?.toString().orEmpty()

            // Never scan RU Lens' own overlay/window.
            if (pkg == packageName) continue

            scannedRoots++
            if (pkg.isNotBlank()) seenPackages += pkg
            collect(root, stats)
        }

        // Fallback for devices that do not expose a windows list reliably.
        if (scannedRoots == 0) {
            val root = rootInActiveWindow
            if (root != null && root.packageName?.toString() != packageName) {
                scannedRoots++
                root.packageName?.toString()?.takeIf { it.isNotBlank() }?.let { seenPackages += it }
                collect(root, stats)
            }
        }

        translated = translationViews.isNotEmpty()
        bubble?.text = if (translated) "×" else "RU"

        val message = when {
            translated -> "RU Lens: переведено ${stats.translated} фрагм."
            stats.textNodes > 0 -> "RU Lens: текст найден (${stats.textNodes}), совпадений словаря нет"
            scannedRoots > 0 -> "RU Lens: окна доступны ($scannedRoots), но текста Android не отдал"
            else -> "RU Lens: нет доступного окна для чтения"
        }

        showStatus(message)
    }

    private fun collect(node: AccessibilityNodeInfo, stats: ScanStats) {
        val candidates = buildList {
            node.text?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let { add(it) }
            node.contentDescription?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let {
                if (!contains(it)) add(it)
            }
        }

        if (candidates.isNotEmpty()) {
            stats.textNodes++

            val translatedCandidate = candidates
                .asSequence()
                .mapNotNull { source -> BankDictionary.translate(source)?.let { source to it } }
                .firstOrNull()

            if (translatedCandidate != null) {
                val (_, ru) = translatedCandidate
                val rect = Rect()
                node.getBoundsInScreen(rect)
                if (rect.width() > 8 && rect.height() > 8 && rect.top >= 0) {
                    showTranslation(rect, ru)
                    stats.translated++
                }
            }
        }

        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child -> collect(child, stats) }
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

    private fun showStatus(message: String) {
        clearStatus()
        val density = resources.displayMetrics.density
        val tv = TextView(this).apply {
            text = message
            textSize = 13f
            setTextColor(Color.WHITE)
            setPadding((12 * density).toInt(), (8 * density).toInt(), (12 * density).toInt(), (8 * density).toInt())
            gravity = Gravity.CENTER
            background = rounded(Color.argb(240, 45, 45, 45), 10f * density)
        }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (70 * density).toInt()
        }

        runCatching {
            wm.addView(tv, lp)
            statusView = tv
            tv.postDelayed({ clearStatus() }, 3500)
        }
    }

    private fun clearTranslations() {
        translationViews.forEach { runCatching { wm.removeView(it) } }
        translationViews.clear()
    }

    private fun clearStatus() {
        statusView?.let { runCatching { wm.removeView(it) } }
        statusView = null
    }

    private fun rounded(color: Int, radius: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
    }

    private data class ScanStats(
        var textNodes: Int = 0,
        var translated: Int = 0
    )
}
