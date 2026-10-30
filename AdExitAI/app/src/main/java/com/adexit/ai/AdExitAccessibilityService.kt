
package com.adexit.ai

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale

class AdExitAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var lastActionAt = 0L
    private var scanUntil = 0L
    private var storeBackAttempts = 0
    private var storeRecoveryInProgress = false

    private val scanRunnable = object : Runnable {
        override fun run() {
            if (!automationEnabled()) return
            if (System.currentTimeMillis() > scanUntil) return
            val current = rootInActiveWindow
            if (current != null) {
                try {
                    if (current.packageName?.toString() == PLAY_STORE_PACKAGE) {
                        recoverFromPlayStore()
                        return
                    }
                    handleAdScreen(current)
                } finally {
                    current.recycle()
                }
            }
            handler.postDelayed(this, SCAN_INTERVAL_MS)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_WINDOWS_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 100
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !automationEnabled()) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == PLAY_STORE_PACKAGE) {
            recoverFromPlayStore()
            return
        }
        if (pkg == packageName || pkg == "com.android.systemui") return

        scanUntil = System.currentTimeMillis() + MAX_SCAN_WINDOW_MS
        handler.removeCallbacks(scanRunnable)
        handler.post(scanRunnable)
    }

    private fun automationEnabled(): Boolean =
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTOMATION_ENABLED, true)

    private fun handleAdScreen(root: AccessibilityNodeInfo) {
        val now = System.currentTimeMillis()
        if (now - lastActionAt < ACTION_COOLDOWN_MS) return

        val signals = collectScreenSignals(root)
        val hasGooglePlay = signals.any {
            it.contains("google play") || it.contains("play store")
        }
        val hasAdMarker = signals.any { marker ->
            AD_MARKERS.any { marker.contains(it) }
        }
        val hasChevron = signals.any { it.trim() in CHEVRON_MARKS }

        if (hasGooglePlay && (hasAdMarker || hasChevron)) {
            if (performGlobalAction(GLOBAL_ACTION_BACK)) {
                lastActionAt = System.currentTimeMillis()
                scanUntil = lastActionAt + MAX_SCAN_WINDOW_MS
                return
            }
        }

        if (!hasAdMarker && !hasGooglePlay) return
        val candidates = ArrayList<AccessibilityNodeInfo>()
        collectCloseCandidates(root, candidates, 0)
        for (node in candidates) {
            try {
                if (!node.isVisibleToUser || !node.isEnabled) continue
                if (clickNodeOrClickableParent(node)) {
                    lastActionAt = System.currentTimeMillis()
                    scanUntil = lastActionAt + MAX_SCAN_WINDOW_MS
                    return
                }
            } finally {
                node.recycle()
            }
        }
    }

    private fun collectScreenSignals(root: AccessibilityNodeInfo): List<String> {
        val values = ArrayList<String>()
        collectSignalsRecursive(root, values, 0)
        return values
    }

    private fun collectSignalsRecursive(
        node: AccessibilityNodeInfo,
        output: MutableList<String>,
        depth: Int
    ) {
        if (depth > 18 || output.size >= 150) return
        node.text?.toString()?.let {
            output.add(it.lowercase(Locale.ROOT))
        }
        node.contentDescription?.toString()?.let {
            output.add(it.lowercase(Locale.ROOT))
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                collectSignalsRecursive(child, output, depth + 1)
            } finally {
                child.recycle()
            }
            if (output.size >= 150) return
        }
    }

    private fun collectCloseCandidates(
        node: AccessibilityNodeInfo,
        output: MutableList<AccessibilityNodeInfo>,
        depth: Int
    ) {
        if (depth > 18 || output.size >= 40) return
        val text = node.text?.toString()?.trim()?.lowercase(Locale.ROOT).orEmpty()
        val desc = node.contentDescription?.toString()?.trim()
            ?.lowercase(Locale.ROOT).orEmpty()
        val viewId = node.viewIdResourceName?.lowercase(Locale.ROOT).orEmpty()
        val combined = "$text $desc $viewId"

        val exactClose = text in EXACT_CLOSE_LABELS ||
            desc in EXACT_CLOSE_LABELS
        val semanticClose = SEMANTIC_CLOSE_TERMS.any {
            combined.contains(it)
        }

        if ((exactClose || semanticClose) &&
            (node.isClickable || hasClickableAncestor(node))
        ) {
            output.add(AccessibilityNodeInfo.obtain(node))
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                collectCloseCandidates(child, output, depth + 1)
            } finally {
                child.recycle()
            }
            if (output.size >= 40) return
        }
    }

    private fun hasClickableAncestor(node: AccessibilityNodeInfo): Boolean {
        var parent = node.parent
        var hops = 0
        while (parent != null && hops < 5) {
            val clickable = parent.isClickable
            val next = parent.parent
            parent.recycle()
            if (clickable) {
                next?.recycle()
                return true
            }
            parent = next
            hops++
        }
        parent?.recycle()
        return false
    }

    private fun clickNodeOrClickableParent(
        node: AccessibilityNodeInfo
    ): Boolean {
        if (node.isClickable &&
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        ) return true

        var parent = node.parent
        var hops = 0
        while (parent != null && hops < 5) {
            if (parent.isClickable) {
                val clicked = parent.performAction(
                    AccessibilityNodeInfo.ACTION_CLICK
                )
                parent.recycle()
                return clicked
            }
            val next = parent.parent
            parent.recycle()
            parent = next
            hops++
        }
        parent?.recycle()
        return false
    }

    private fun recoverFromPlayStore() {
        if (storeRecoveryInProgress || !automationEnabled()) return
        storeRecoveryInProgress = true
        storeBackAttempts = 0
        handler.removeCallbacks(scanRunnable)
        pressBackWhileStoreRemains()
    }

    private fun pressBackWhileStoreRemains() {
        if (!automationEnabled()) {
            storeRecoveryInProgress = false
            return
        }

        val activeRoot = rootInActiveWindow
        val stillInStore = try {
            activeRoot?.packageName?.toString() == PLAY_STORE_PACKAGE
        } finally {
            activeRoot?.recycle()
        }

        if (!stillInStore) {
            storeRecoveryInProgress = false
            return
        }

        if (storeBackAttempts >= MAX_STORE_BACK_ATTEMPTS) {
            storeRecoveryInProgress = false
            return
        }

        storeBackAttempts++
        performGlobalAction(GLOBAL_ACTION_BACK)
        handler.postDelayed(
            { pressBackWhileStoreRemains() },
            STORE_BACK_INTERVAL_MS
        )
    }

    override fun onInterrupt() {
        handler.removeCallbacks(scanRunnable)
        storeRecoveryInProgress = false
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        private const val PLAY_STORE_PACKAGE = "com.android.vending"
        private const val PREFS_NAME = "adexit_settings"
        private const val KEY_AUTOMATION_ENABLED = "automation_enabled"
        private const val SCAN_INTERVAL_MS = 650L
        private const val MAX_SCAN_WINDOW_MS = 60_000L
        private const val ACTION_COOLDOWN_MS = 1_200L
        private const val STORE_BACK_INTERVAL_MS = 700L
        private const val MAX_STORE_BACK_ATTEMPTS = 3

        private val CHEVRON_MARKS = setOf(
            "»", "›", "➜", "→", "⟶", "❯", "≫"
        )

        private val AD_MARKERS = listOf(
            "sponsored",
            "advertisement",
            "ad choices",
            "adchoice",
            "advertiser",
            "promoted"
        )

        private val EXACT_CLOSE_LABELS = setOf(
            "x", "×", "✕", "✖", "close", "close ad",
            "skip", "skip ad", "dismiss", "dismiss ad",
            "no thanks", "not now"
        )

        private val SEMANTIC_CLOSE_TERMS = listOf(
            "close_ad", "closead", "skip_ad",
            "skipad", "dismiss_ad", "dismissad"
        )

        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false

            val component =
                "${context.packageName}/${AdExitAccessibilityService::class.java.name}"

            return enabled.split(':').any {
                it.equals(component, ignoreCase = true)
            }
        }

        fun isAutomationEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_AUTOMATION_ENABLED, true)

        fun setAutomationEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_AUTOMATION_ENABLED, enabled)
                .apply()
        }
    }
}
