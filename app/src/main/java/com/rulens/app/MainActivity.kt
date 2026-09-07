package com.rulens.app

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        RULensAccessibilityService.requestShow()

        val pad = (20 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(pad, pad * 2, pad, pad)
        }

        root.addView(TextView(this).apply {
            text = "RU Lens"
            textSize = 28f
            gravity = Gravity.CENTER
        })

        root.addView(TextView(this).apply {
            text = "Локальный переводчик турецкого интерфейса на русский.\n\nВ приложении отсутствует разрешение INTERNET. Текст не отправляется на сервер и не сохраняется в историю."
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, pad, 0, pad)
        })

        root.addView(Button(this).apply {
            text = "Включить RU Lens"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        })

        root.addView(Button(this).apply {
            text = "Закрыть RU Lens"
            setOnClickListener {
                RULensAccessibilityService.requestClose()
                finishAndRemoveTask()
            }
        })

        root.addView(TextView(this).apply {
            text = "После включения службы появится плавающая кнопка RU. Нажатие переводит доступные турецкие надписи на текущем экране. Кнопка «Закрыть RU Lens» убирает плавающую кнопку и переводы; при следующем запуске приложения RU Lens снова появится без повторной выдачи разрешения."
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, pad, 0, 0)
        })

        setContentView(root)
    }
}
