package com.adexit.ai

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (20 * resources.displayMetrics.density).toInt()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val title = TextView(this).apply {
            text = "AdExit AI"
            textSize = 30f
            setTextColor(0xFF172554.toInt())
        }
        val description = TextView(this).apply {
            text = "Automatic ad close and Google Play redirect recovery"
            textSize = 16f
            setPadding(0, pad, 0, pad)
        }
        status = TextView(this).apply {
            textSize = 16f
            setPadding(0, pad, 0, pad)
        }
        val enable = Button(this).apply {
            text = "Enable Accessibility Service"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
        val help = TextView(this).apply {
            text = "After opening Accessibility settings, select AdExit AI and turn it on. " +
                "Android will show a permission warning because accessibility can read screen content. " +
                "Only enable it if you trust this app. Return here afterward."
            textSize = 14f
            setPadding(0, pad, 0, 0)
        }
        layout.addView(title)
        layout.addView(description)
        layout.addView(status)
        layout.addView(enable)
        layout.addView(help)
        setContentView(layout)
    }

    override fun onResume() {
        super.onResume()
        if (::status.isInitialized) {
            status.text = if (AdExitAccessibilityService.isEnabled(this)) {
                "Status: Accessibility service enabled"
            } else {
                "Status: Not enabled yet"
            }
        }
    }
}
