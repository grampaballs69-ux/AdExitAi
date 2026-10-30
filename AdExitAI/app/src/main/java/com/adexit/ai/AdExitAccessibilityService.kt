

package com.adexit.ai

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale

class AdExitAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var scanUntil = 0L
    private var lastActionAt = 0L
    private var storeRecoveryInProgress = false
    private var storeBackAttempts = 0

    private val scanRunnable = object : Runnable {
        override fun run() {
            if (!automationEnabled()) return
            if (System.currentTimeMillis() > scanUntil) return

            val root = rootInActiveWindow
            if (root != null) {
                try {
                    if (root.packageName?.toString() == PLAY_STORE_PACKAGE) {
                        recoverFromPlayStore()
                        return
                    }

                    scanTree(root, root, 0)
                } finally {
                    root.recycle()
                }
            }

            if (automationEnabled() && System.currentTimeMillis() <= scanUntil) {
                handler.postDelayed(this, SCAN_INTERVAL_MS)
            }
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

        val eventPackage = event.packageName?.toString() ?: return

        if (eventPackage == PLAY_STORE_PACKAGE) {
            recoverFromPlayStore()
            return
        }

        if (eventPackage == packageName ||
            eventPackage == "com.android.systemui"
        ) return

        scanUntil = System.currentTimeMillis() + MAX_SCAN_WINDOW_MS

        handler.removeCallbacks(scanRunnable)
        handler.post(scanRunnable)
    }

    private fun automationEnabled(): Boolean {
        return getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTOMATION_ENABLED, true)
    }

    private fun scanTree(
        node: AccessibilityNodeInfo,
        root: AccessibilityNodeInfo,
        depth: Int
    ): Boolean {
        if (depth > MAX_TREE_DEPTH) return false

        if (System.currentTimeMillis() - lastActionAt < ACTION_COOLDOWN_MS) {
            return false
        }

        if (node.isVisibleToUser &&
            node.isEnabled &&
            isCloseOrSkipControl(node, root)
        ) {
            if (clickNodeOrClickableParent(node)) {
                lastActionAt = System.currentTimeMillis()
                return true
            }
        }

        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue

            val clicked = try {
                scanTree(child, root, depth + 1)
            } finally {
                child.recycle()
            }

            if (clicked) return true
        }

        return false
    }

    private fun isCloseOrSkipControl(
        node: AccessibilityNodeInfo,
        root: AccessibilityNodeInfo
    ): Boolean {
        val values = listOf(
            normalize(node.text?.toString()),
            normalize(node.contentDescription?.toString()),
            normalize(node.viewIdResourceName)
        ).filter { it.isNotEmpty() }

        if (values.isEmpty()) return false

        if (values.any { isGooglePlayLabel(it) }) return false

        val closeOrSkipLabel = values.any { value ->
            value in CLOSE_LABELS ||
                value.startsWith("skip") ||
                value.startsWith("close") ||
                value.startsWith("dismiss") ||
                value.contains("skipbutton") ||
                value.contains("closebutton") ||
                value.contains("dismissbutton")
        }

        val skipIcon = values.any { it in SKIP_ICONS }

        val xIcon =
            values.any { it in X_SYMBOLS } &&
                isLikelyClosePosition(node, root)

        return closeOrSkipLabel || skipIcon || xIcon
    }

    private fun isGooglePlayLabel(value: String): Boolean {
        return value.contains("googleplay") ||
            value.contains("getitongoogleplay") ||
            value.contains("openwithgoogleplay") ||
            value.contains("playstore") ||
            value.contains("installnow") ||
            value.contains("downloadnow")
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
        val nodeBounds = Rect()
        val rootBounds = Rect()

        node.getBoundsInScreen(nodeBounds)
        root.getBoundsInScreen(rootBounds)

        if (nodeBounds.isEmpty || rootBounds.isEmpty) return false

        val topLimit =
            rootBounds.top + rootBounds.height() * 0.45f

        val rightLimit =
            rootBounds.left + rootBounds.width() * 0.45f

        return nodeBounds.centerY() <= topLimit &&
            nodeBounds.centerX() >= rightLimit
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
        var depth = 0

        while (parent != null && depth < MAX_PARENT_DEPTH) {
            val isClickable = parent.isClickable

            val didClick = isClickable &&
                parent.isEnabled &&
                parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)

            val next = if (didClick) null else parent.parent

            parent.recycle()

            if (didClick) {
                next?.recycle()
                return true
            }

            parent = next
            depth++
        }

        parent?.recycle()
        return false
    }

    private fun recoverFromPlayStore() {
        if (storeRecoveryInProgress || !automationEnabled()) return

        storeRecoveryInProgress = true
        storeBackAttempts = 0

        handler.removeCallbacks(scanRunnable)

        handler.postDelayed({
            performStoreBack()
        }, STORE_INITIAL_BACK_DELAY_MS)
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

        val root = rootInActiveWindow

        val stillInStore = try {
            root?.packageName?.toString() == PLAY_STORE_PACKAGE
        } finally {
            root?.recycle()
        }

        if (!stillInStore) {
            storeRecoveryInProgress = false
            return
        }

        storeBackAttempts++

        performGlobalAction(GLOBAL_ACTION_BACK)

        handler.postDelayed({

            val checkRoot = rootInActiveWindow

            val remainsInStore = try {
                checkRoot?.packageName?.toString() == PLAY_STORE_PACKAGE
            } finally {
                checkRoot?.recycle()
            }

            if (remainsInStore &&
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

        private const val STORE_INITIAL_BACK_DELAY_MS = 350L
        private const val STORE_BACK_INTERVAL_MS = 700L
        private const val MAX_STORE_BACK_ATTEMPTS = 3

        private const val MAX_TREE_DEPTH = 20
        private const val MAX_PARENT_DEPTH = 5

        private val CLOSE_LABELS = setOf(
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
            "|►",
            "►|",
            "|▷",
            "▷|",
            "|⏵",
            "⏵|",
            "|⏭",
            "⏭|",
            "ı▶",
            "▶ı"
        )

        private val CHEVRON_SYMBOLS = setOf(
            "»",
            "›",
            "≫",
            "❯",
            "➜",
            "→",
            "⟶",
            "⮞",
            "⏩"
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
            return context.getSharedPreferences(
                PREFS_NAME,
                Context.MODE_PRIVATE
            ).getBoolean(KEY_AUTOMATION_ENABLED, true)
        }

        fun setAutomationEnabled(
            context: Context,
            enabled: Boolean
        ) {
            context.getSharedPreferences(
                PREFS_NAME,
                Context.MODE_PRIVATE
            )
                .edit()
                .putBoolean(KEY_AUTOMATION_ENABLED, enabled)
                .apply()
        }
    }
}
    }
}
