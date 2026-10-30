
package com.adexit.ai

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.graphics.Rect
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.os.Handler
import android.os.Looper
import java.util.Locale

class AdExitAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())

    private var lastActionAt = 0L
    private var scanUntil = 0L
    private var storeRecoveryInProgress = false
    private var storeBackAttempts = 0

    private val scanRunnable = object : Runnable {
        override fun run() {
            if (!automationEnabled()) return
            if (System.currentTimeMillis() > scanUntil) return

            val root = rootInActiveWindow

            if (root != null) {
                try {
                    if (isPlayStore(root)) {
                        recoverFromPlayStore()
                        return
                    }

                    scanForCloseButtons(root)
                } finally {
                    root.recycle()
                }
            }

            handler.postDelayed(this, SCAN_INTERVAL_MS)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()

        serviceInfo = serviceInfo.apply {
            eventTypes =
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_WINDOWS_CHANGED

            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC

            flags = flags or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS

            notificationTimeout = 100
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !automationEnabled()) return

        val pkg = event.packageName?.toString() ?: return

        // If Google Play opens, return using Android Back.
        if (pkg == PLAY_STORE_PACKAGE) {
            recoverFromPlayStore()
            return
        }

        if (pkg == packageName || pkg == "com.android.systemui") return

        scanUntil = System.currentTimeMillis() + MAX_SCAN_WINDOW_MS

        handler.removeCallbacks(scanRunnable)
        handler.post(scanRunnable)
    }

    private fun automationEnabled(): Boolean {
        return getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTOMATION_ENABLED, true)
    }

    private fun isPlayStore(root: AccessibilityNodeInfo): Boolean {
        return root.packageName?.toString() == PLAY_STORE_PACKAGE
    }

    private fun scanForCloseButtons(root: AccessibilityNodeInfo) {
        if (System.currentTimeMillis() - lastActionAt < ACTION_COOLDOWN_MS) {
            return
        }

        val candidates = ArrayList<AccessibilityNodeInfo>()

        collectCloseCandidates(root, root, candidates, 0)

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

    private fun collectCloseCandidates(
        node: AccessibilityNodeInfo,
        root: AccessibilityNodeInfo,
        output: MutableList<AccessibilityNodeInfo>,
        depth: Int
    ) {
        if (depth > 20 || output.size >= 50) return

        val text = normalize(node.text?.toString())
        val description = normalize(node.contentDescription?.toString())
        val viewId = normalize(node.viewIdResourceName)

        val combined = "$text $description $viewId"

        val closeMatch =
            text in CLOSE_LABELS ||
            description in CLOSE_LABELS

        val skipIconMatch =
            text in SKIP_ICONS ||
            description in SKIP_ICONS

        val semanticMatch = CLOSE_TERMS.any {
            combined.contains(it)
        }

        val xMatch =
            text in X_SYMBOLS ||
            description in X_SYMBOLS

        val isClickable =
            node.isClickable || hasClickableAncestor(node)

        if (isClickable && node.isVisibleToUser) {
            val shouldClick =
                closeMatch ||
                skipIconMatch ||
                semanticMatch ||
                (xMatch && isLikelyClosePosition(node, root))

            if (shouldClick) {
                output.add(AccessibilityNodeInfo.obtain(node))
            }
        }

        // Google Play and chevron recognition.
        // Do not click standalone chevrons or store redirect labels.
        val googlePlayRedirect = GOOGLE_PLAY_TERMS.any {
            combined.contains(it)
        }

        if (googlePlayRedirect) {
            // Recognized. Back recovery happens when Google Play opens.
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue

            try {
                collectCloseCandidates(child, root, output, depth + 1)
            } finally {
                child.recycle()
            }

            if (output.size >= 50) return
        }
    }

    private fun normalize(value: String?): String {
        return value.orEmpty()
            .trim()
            .lowercase(Locale.ROOT)
            .replace("\\s+".toRegex(), "")
    }

    private fun isLikelyClosePosition(
        node: AccessibilityNodeInfo,
        root: AccessibilityNodeInfo
    ): Boolean {
        val nodeRect = Rect()
        val rootRect = Rect()

        node.getBoundsInScreen(nodeRect)
        root.getBoundsInScreen(rootRect)

        if (nodeRect.isEmpty || rootRect.isEmpty) return false

        val topLimit = rootRect.top + (rootRect.height() * 0.45f)
        val rightLimit = rootRect.left + (rootRect.width() * 0.45f)

        return nodeRect.centerY() <= topLimit &&
            nodeRect.centerX() >= rightLimit
    }

    private fun hasClickableAncestor(
        node: AccessibilityNodeInfo
    ): Boolean {
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
        ) {
            return true
        }

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
        performStoreBack()
    }

    private fun performStoreBack() {
        if (!automationEnabled()) {
            storeRecoveryInProgress = false
            return
        }

        if (storeBackAttempts >= MAX_STORE_BACK_ATTEMPTS) {
            storeRecoveryInProgress = false
            return
        }

        storeBackAttempts++

        performGlobalAction(GLOBAL_ACTION_BACK)

        handler.postDelayed({
            val root = rootInActiveWindow

            val stillInStore = try {
                root?.packageName?.toString() == PLAY_STORE_PACKAGE
            } finally {
                root?.recycle()
            }

            if (stillInStore &&
                storeBackAttempts < MAX_STORE_BACK_ATTEMPTS
            ) {
                performStoreBack()
            } else {
                storeRecoveryInProgress = false
            }

        }, STORE_BACK_INTERVAL_MS)
    }

    override fun onInterrupt() {
        handler.removeCallbacksAndMessages(null)
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

        private const val SCAN_INTERVAL_MS = 500L
        private const val MAX_SCAN_WINDOW_MS = 60_000L
        private const val ACTION_COOLDOWN_MS = 1_000L

        private const val STORE_BACK_INTERVAL_MS = 700L
        private const val MAX_STORE_BACK_ATTEMPTS = 3

        private val CLOSE_LABELS = setOf(
            "x",
            "×",
            "✕",
            "✖",
            "✗",
            "close",
            "closead",
            "skip",
            "skipad",
            "dismiss",
            "dismissad",
            "nothanks",
            "notnow",
            "continuetoapp",
            "returntoapp"
        )

        private val X_SYMBOLS = setOf(
            "x",
            "×",
            "✕",
            "✖",
            "✗"
        )

        private val SKIP_ICONS = setOf(
            "|▶",
            "▶|",
            "|▸",
            "▸|",
            "|⏵",
            "⏵|",
            "|⏭",
            "⏭|"
        )

        private val CLOSE_TERMS = listOf(
            "close_ad",
            "closead",
            "skip_ad",
            "skipad",
            "dismiss_ad",
            "dismissad",
            "closebutton",
            "skipbutton",
            "dismissbutton"
        )

        private val GOOGLE_PLAY_TERMS = listOf(
            "googleplay»",
            "googleplay›",
            "googleplay≫",
            "googleplay❯",
            "googleplay",
            "getitonGooglePlay".lowercase(Locale.ROOT),
            "installnow",
            "downloadnow",
            "openinGooglePlay".lowercase(Locale.ROOT)
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

        fun isAutomationEnabled(context: Context): Boolean {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_AUTOMATION_ENABLED, true)
        }

        fun setAutomationEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_AUTOMATION_ENABLED, enabled)
                .apply()
        }
    }
}

                .apply()
        }
    }
}
