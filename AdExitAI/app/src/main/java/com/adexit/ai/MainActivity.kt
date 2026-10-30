
package com.adexit.ai

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var automationButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 64, 48, 48)
        }

        val title = TextView(this).apply {
            text = "AdExit AI"
            textSize = 28f
        }

        statusText = TextView(this).apply {
            textSize = 16f
            setPadding(0, 32, 0, 32)
        }

        val accessibilityButton = Button(this).apply {
            text = "Open Accessibility Settings"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        automationButton = Button(this).apply {
            setOnClickListener {
                val enabled =
                    !AdExitAccessibilityService.isAutomationEnabled(this@MainActivity)

                AdExitAccessibilityService.setAutomationEnabled(
                    this@MainActivity,
                    enabled
                )

                updateStatus()
            }
        }

        val information = TextView(this).apply {
            text = "\nAdExit AI monitors supported ad screens for close and skip controls. " +
                "When Google Play opens from an ad, it attempts to return using Android's Back action.\n\n" +
                "Some ads cannot be detected if their controls are not exposed to Android accessibility."
            textSize = 14f
        }

        layout.addView(title)
        layout.addView(statusText)
        layout.addView(accessibilityButton)
        layout.addView(automationButton)
        layout.addView(information)

        setContentView(layout)
        updateStatus()
    }

    override fun onResume() {
        super.onResume()
        if (::statusText.isInitialized) updateStatus()
    }

    private fun updateStatus() {
        val accessibilityEnabled =
            AdExitAccessibilityService.isEnabled(this)

        val automationEnabled =
            AdExitAccessibilityService.isAutomationEnabled(this)

        statusText.text = buildString {
            append("Accessibility service: ")
            append(if (accessibilityEnabled) "Enabled" else "Not enabled")
            append("\nAutomation: ")
            append(if (automationEnabled) "Running" else "Paused")
        }

        automationButton.text =
            if (automationEnabled) "Pause Automation" else "Resume Automation"
    }
}
