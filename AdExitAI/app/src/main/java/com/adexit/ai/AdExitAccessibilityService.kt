package com.adexit.ai

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.provider.Settings
import java.util.Locale

class AdExitAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var lastNonStorePackage: String? = null
    private var lastActionAt = 0L
    private var scanUntil = 0L
    private var storeRecoveryInProgress = false

    private val scanRunnable = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            if (now > scanUntil) return
            val current = rootInActiveWindow
            if (current != null) {
                try {
                    val pkg = current.packageName?.toString()
                    if (pkg == PLAY_STORE_PACKAGE) {
                        recoverFromPlayStore()
                        return
                    }
                    findAndClickCloseControl(current)
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
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return

        if (pkg == PLAY_STORE_PACKAGE) {
            recoverFromPlayStore()
            return
        }

        if (pkg != packageName && pkg != "com.android.systemui") {
            lastNonStorePackage = pkg
        }

        // Repeated polling handles countdowns and close buttons that appear later.
        scanUntil = System.currentTimeMillis() + MAX_SCAN_WINDOW_MS
        handler.removeCallbacks(scanRunnable)
        handler.post(scanRunnable)
    }

    private fun findAndClickCloseControl(root: AccessibilityNodeInfo) {
        val now = System.currentTimeMillis()
        if (now - lastActionAt < ACTION_COOLDOWN_MS) return

        val candidates = ArrayList<AccessibilityNodeInfo>()
        collectCandidates(root, candidates, 0)

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

    private fun collectCandidates(
        node: AccessibilityNodeInfo,
        output: MutableList<AccessibilityNodeInfo>,
        depth: Int
    ) {
        if (depth > 18 || output.size >= 40) return

        val text = node.text?.toString()?.trim()?.lowercase(Locale.ROOT).orEmpty()
        val desc = node.contentDescription?.toString()?.trim()?.lowercase(Locale.ROOT).orEmpty()
        val viewId = node.viewIdResourceName?.lowercase(Locale.ROOT).orEmpty()
        val combined = "$text $desc $viewId"

        // Avoid broad matches such as "install", "open", or a random letter in a sentence.
        val exactClose = text in EXACT_CLOSE_LABELS || desc in EXACT_CLOSE_LABELS
        val semanticClose = SEMANTIC_CLOSE_TERMS.any { term ->
            combined.contains(term) && (node.isClickable || hasClickableAncestor(node))
        }
        if ((exactClose || semanticClose) && (node.isClickable || hasClickableAncestor(node))) {
            val copy = AccessibilityNodeInfo.obtain(node)
            output.add(copy)
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try { collectCandidates(child, output, depth + 1) }
            finally { child.recycle() }
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

    private fun clickNodeOrClickableParent(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        var parent = node.parent
        var hops = 0
        while (parent != null && hops < 5) {
            if (parent.isClickable) {
                val clicked = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
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
        if (storeRecoveryInProgress) return
        storeRecoveryInProgress = true
        handler.removeCallbacks(scanRunnable)

        // Back normally dismisses the Play Store page and returns to the ad/app.
        val wentBack = performGlobalAction(GLOBAL_ACTION_BACK)
        handler.postDelayed({
            val activePkg = rootInActiveWindow?.let {
                val p = it.packageName?.toString()
                it.recycle()
                p
            }
            if (!wentBack || activePkg == PLAY_STORE_PACKAGE) {
                // Home is the fallback. Reopen the app that was foreground before Play Store.
                performGlobalAction(GLOBAL_ACTION_HOME)
                handler.postDelayed({
                    val previous = lastNonStorePackage
                    if (previous != null && previous != packageName) {
                        val launch = packageManager.getLaunchIntentForPackage(previous)
                        if (launch != null) {
                            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                            try { startActivity(launch) } catch (_: Exception) { }
                        }
                    }
                    storeRecoveryInProgress = false
                }, 700)
            } else {
                storeRecoveryInProgress = false
            }
        }, 500)
    }

    override fun onInterrupt() {
        handler.removeCallbacks(scanRunnable)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        private const val PLAY_STORE_PACKAGE = "com.android.vending"
        private const val SCAN_INTERVAL_MS = 650L
        private const val MAX_SCAN_WINDOW_MS = 60_000L
        private const val ACTION_COOLDOWN_MS = 1_200L

        private val EXACT_CLOSE_LABELS = setOf(
            "x", "×", "✕", "✖", "close", "close ad", "skip", "skip ad",
            "dismiss", "dismiss ad", "no thanks", "not now"
        )
        private val SEMANTIC_CLOSE_TERMS = listOf(
            "close_ad", "closead", "skip_ad", "skipad", "dismiss_ad", "dismissad"
        )

        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val component = "${context.packageName}/${AdExitAccessibilityService::class.java.name}"
            return enabled.split(':').any { it.equals(component, ignoreCase = true) }
        }
    }
}
