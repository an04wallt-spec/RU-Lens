package com.rulens.app

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

class RULensAccessibilityService : AccessibilityService() {
    private lateinit var wm: WindowManager
    private var bubble: TextView? = null
    private val translationViews = mutableListOf<View>()
    private var statusView: TextView? = null
    private var translated = false
    private var ocrBusy = false
    private val drawnKeys = mutableSetOf<String>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val textRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        showBubble()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // No continuous capture or storage. Translation happens only after RU tap.
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        clearTranslations()
        clearStatus()
        bubble?.let { runCatching { wm.removeView(it) } }
        bubble = null
        runCatching { textRecognizer.close() }
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
        if (ocrBusy) return

        if (translated) {
            clearTranslations()
            clearStatus()
            translated = false
            bubble?.text = "RU"
            return
        }

        clearTranslations()
        clearStatus()
        drawnKeys.clear()

        val stats = ScanStats()
        var scannedRoots = 0

        for (window in windows) {
            val root = window.root ?: continue
            val pkg = root.packageName?.toString().orEmpty()
            if (pkg == packageName) continue
            scannedRoots++
            collect(root, stats)
        }

        if (scannedRoots == 0) {
            val root = rootInActiveWindow
            if (root != null && root.packageName?.toString() != packageName) {
                scannedRoots++
                collect(root, stats)
            }
        }

        if (translationViews.isNotEmpty()) {
            translated = true
            bubble?.text = "×"
            showStatus("RU Lens: Accessibility — переведено ${stats.translated} фрагм.")
            return
        }

        // Accessibility gave no usable translation. Fall back to fully local OCR.
        startOcrFallback(stats.textNodes, scannedRoots)
    }

    private fun startOcrFallback(accessibilityTextNodes: Int, scannedRoots: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            val reason = when {
                accessibilityTextNodes > 0 -> "текст найден, но совпадений словаря нет"
                scannedRoots > 0 -> "приложение не отдаёт текст Android"
                else -> "нет доступного окна"
            }
            showStatus("RU Lens: $reason; OCR требует Android 11+")
            return
        }

        ocrBusy = true
        bubble?.text = "…"
        clearStatus()
        showStatus("RU Lens: Accessibility не помог — запускаю локальный OCR…")

        // Hide our own overlay before the screenshot so OCR does not recognize RU Lens itself.
        bubble?.visibility = View.INVISIBLE
        clearStatus()

        mainHandler.postDelayed({
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        val buffer = screenshot.hardwareBuffer
                        val hardwareBitmap = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                        val bitmap = hardwareBitmap?.copy(Bitmap.Config.ARGB_8888, false)
                        buffer.close()

                        if (bitmap == null) {
                            finishOcrFailure("не удалось получить изображение экрана")
                            return
                        }

                        val image = InputImage.fromBitmap(bitmap, 0)
                        textRecognizer.process(image)
                            .addOnSuccessListener { result ->
                                var linesFound = 0
                                var translatedLines = 0

                                for (block in result.textBlocks) {
                                    for (line in block.lines) {
                                        val source = line.text.trim()
                                        val rect = line.boundingBox ?: continue
                                        if (source.isBlank() || rect.width() <= 8 || rect.height() <= 8) continue
                                        linesFound++

                                        val ru = BankDictionary.translate(source) ?: continue
                                        val key = "ocr:${rect.left}:${rect.top}:${rect.right}:${rect.bottom}:${ru.lowercase()}"
                                        if (drawnKeys.add(key)) {
                                            showTranslation(rect, ru)
                                            translatedLines++
                                        }
                                    }
                                }

                                translated = translationViews.isNotEmpty()
                                bubble?.text = if (translated) "×" else "RU"
                                bubble?.visibility = View.VISIBLE
                                ocrBusy = false

                                when {
                                    translated -> showStatus("RU Lens: OCR — переведено $translatedLines из $linesFound строк")
                                    linesFound > 0 -> showStatus("RU Lens: OCR видит текст ($linesFound строк), но словарь не нашёл совпадений")
                                    else -> showStatus("RU Lens: OCR не нашёл текста на экране")
                                }
                                bitmap.recycle()
                            }
                            .addOnFailureListener { error ->
                                bitmap.recycle()
                                finishOcrFailure("ошибка распознавания: ${error.javaClass.simpleName}")
                            }
                    }

                    override fun onFailure(errorCode: Int) {
                        val message = if (
                            Build.VERSION.SDK_INT >= 34 &&
                            errorCode == ERROR_TAKE_SCREENSHOT_SECURE_WINDOW
                        ) {
                            "приложение защищает экран от снимков (secure window)"
                        } else {
                            "снимок экрана недоступен, код $errorCode"
                        }
                        finishOcrFailure(message)
                    }
                }
            )
        }, 120)
    }

    private fun finishOcrFailure(message: String) {
        ocrBusy = false
        translated = false
        bubble?.text = "RU"
        bubble?.visibility = View.VISIBLE
        showStatus("RU Lens: OCR — $message")
    }

    private fun collect(node: AccessibilityNodeInfo, stats: ScanStats) {
        if (!node.isVisibleToUser) return

        val rect = Rect()
        node.getBoundsInScreen(rect)
        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels

        val validRect = rect.width() > 8 &&
            rect.height() > 8 &&
            rect.right > 0 && rect.bottom > 0 &&
            rect.left < screenW && rect.top < screenH

        if (validRect) {
            val visibleText = node.text?.toString()?.trim()?.takeIf { it.isNotBlank() }
            val fallbackDescription = if (visibleText == null && node.childCount == 0) {
                node.contentDescription?.toString()?.trim()?.takeIf {
                    it.isNotBlank() && it.length <= 80 && !it.contains("Bu sayfa", ignoreCase = true)
                }
            } else null

            val source = visibleText ?: fallbackDescription
            if (source != null) {
                stats.textNodes++
                val ru = BankDictionary.translate(source)
                if (ru != null) {
                    val key = "${rect.left}:${rect.top}:${rect.right}:${rect.bottom}:${ru.lowercase()}"
                    val tooLarge = rect.width() > screenW * 0.92 || rect.height() > screenH * 0.22
                    if (!tooLarge && drawnKeys.add(key)) {
                        showTranslation(rect, ru)
                        stats.translated++
                    }
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
        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels

        if (bounds.right <= 0 || bounds.bottom <= 0 || bounds.left >= screenW || bounds.top >= screenH) return

        val tv = TextView(this).apply {
            text = translatedText
            textSize = 13f
            setTextColor(Color.WHITE)
            setPadding(pad, pad / 2, pad, pad / 2)
            gravity = Gravity.CENTER
            background = rounded(Color.argb(235, 25, 25, 25), 8f * density)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

        val minW = (56 * density).toInt()
        val minH = (28 * density).toInt()
        val maxW = (screenW * 0.72f).toInt()
        val width = bounds.width().coerceAtLeast(minW).coerceAtMost(maxW)
        val height = bounds.height().coerceAtLeast(minH).coerceAtMost((64 * density).toInt())

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
            x = bounds.left.coerceAtLeast(0).coerceAtMost((screenW - width).coerceAtLeast(0))
            y = bounds.top.coerceAtLeast(0).coerceAtMost((screenH - height).coerceAtLeast(0))
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
            tv.postDelayed({ clearStatus() }, 4200)
        }
    }

    private fun clearTranslations() {
        translationViews.forEach { runCatching { wm.removeView(it) } }
        translationViews.clear()
        drawnKeys.clear()
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
