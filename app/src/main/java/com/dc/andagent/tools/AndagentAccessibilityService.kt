package com.dc.andagent.tools

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import java.lang.ref.WeakReference

/**
 * R71: control-level automation without external privilege backends.
 *
 * The service keeps a weak reference for tools and records only the latest
 * window identity. It never persists events or the node tree; a11y_screen
 * reads the active window on demand.
 */
class AndagentAccessibilityService : AccessibilityService() {

    @Volatile
    var lastPackage: String? = null
        private set

    @Volatile
    var lastEventClass: String? = null
        private set

    @Volatile
    var lastEventMs: Long = 0L
        private set

    @Volatile
    var eventCount: Int = 0
        private set

    override fun onServiceConnected() {
        super.onServiceConnected()
        instanceRef = WeakReference(this)
        connected = true
        lastEventMs = System.currentTimeMillis()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                lastPackage = event.packageName?.toString()
                lastEventClass = event.className?.toString()
                lastEventMs = if (event.eventTime > 0L) event.eventTime else System.currentTimeMillis()
                eventCount++
            }
        }
    }

    override fun onInterrupt() {
        // No long-running work to abort.
    }

    override fun onUnbind(intent: Intent?): Boolean {
        clearInstance()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        clearInstance()
        super.onDestroy()
    }

    companion object {
        @Volatile
        private var instanceRef: WeakReference<AndagentAccessibilityService>? = null

        @Volatile
        private var connected: Boolean = false

        fun instance(): AndagentAccessibilityService? = instanceRef?.get()

        fun isConnected(): Boolean = connected && instance() != null

        private fun clearInstance() {
            instanceRef = null
            connected = false
        }

        /**
         * Reads the secure setting directly: it works even if the service is
         * still starting, and lets a11y_status tell "off" apart from
         * "enabled but not connected".
         */
        fun isEnabled(context: Context): Boolean = runCatching {
            val resolver = context.contentResolver
            val enabled = Settings.Secure.getInt(
                resolver,
                Settings.Secure.ACCESSIBILITY_ENABLED,
                0,
            ) == 1
            if (!enabled) return@runCatching false
            val component = ComponentName(
                context,
                AndagentAccessibilityService::class.java,
            ).flattenToString()
            val listed = Settings.Secure.getString(
                resolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            )
            isServiceListed(listed, component)
        }.getOrDefault(false)
    }
}
