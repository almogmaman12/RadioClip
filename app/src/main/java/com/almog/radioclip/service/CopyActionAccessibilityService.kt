package com.almog.radioclip.service

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.TextView
import java.util.Locale

/**
 * Optional, visible capture of a newly copied clip after a recognizable user Copy/Cut click.
 * Does not inspect window contents, capture keystrokes, or poll in the background.
 */
class CopyActionAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var window: TextView? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_VIEW_CLICKED ||
            event.packageName?.toString() == packageName || event.isPassword) return
        val sync = RadioSyncService.activeInstance ?: return
        if (!sync.canInspectBackgroundClipboard() || !Settings.canDrawOverlays(this)) return
        // Only the action label is checked, never selected text or other UI contents.
        val labels = event.text.map { it.toString() } + listOfNotNull(event.contentDescription?.toString())
        if (labels.none { it.trim().lowercase(Locale.ROOT) in setOf("copy", "cut", "copy text", "העתק", "העתקה", "גזור", "גזירה") }) return
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        val keyguard = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (!power.isInteractive || keyguard.isKeyguardLocked) return
        if (window != null) return
        val eventTime = System.currentTimeMillis() - (SystemClock.uptimeMillis() - event.eventTime).coerceAtLeast(0L)
        // Let the original Copy/Cut operation finish before requesting focus.
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ showCaptureWindow(eventTime - 250L) }, 180L)
    }

    private fun showCaptureWindow(minimumTimestamp: Long) {
        val sync = RadioSyncService.activeInstance ?: return
        if (!sync.canInspectBackgroundClipboard() || window != null) return
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        val keyguard = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (!power.isInteractive || keyguard.isKeyguardLocked) return
        val manager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val view = TextView(this).apply {
            text = "RadioClip · sharing copied text"
            setTextColor(Color.WHITE)
            setBackgroundColor(0xFF244866.toInt())
            setPadding(24, 16, 24, 16)
            isFocusable = true
            isFocusableInTouchMode = true
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = 64 }
        try {
            manager.addView(view, params)
            window = view
            view.requestFocus()
        } catch (_: Exception) { return }
        val deadline = SystemClock.uptimeMillis() + 900L
        fun attempt() {
            if (window !== view || !sync.canInspectBackgroundClipboard() ||
                keyguard.isKeyguardLocked || !power.isInteractive) {
                closeWindow()
                return
            }
            val complete = view.hasWindowFocus() && sync.inspectBackgroundClipboard(minimumTimestamp)
            if (complete || SystemClock.uptimeMillis() >= deadline) {
                handler.postDelayed({ closeWindow() }, 160L)
            } else {
                handler.postDelayed({ attempt() }, 80L)
            }
        }
        handler.postDelayed({ attempt() }, 100L)
    }

    private fun closeWindow() {
        handler.removeCallbacksAndMessages(null)
        val view = window ?: return
        window = null
        try { (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeViewImmediate(view) }
        catch (_: Exception) { }
    }

    override fun onInterrupt() = closeWindow()
    override fun onDestroy() {
        closeWindow()
        super.onDestroy()
    }
}
