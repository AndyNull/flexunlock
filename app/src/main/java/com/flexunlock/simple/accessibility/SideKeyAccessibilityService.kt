package com.flexunlock.simple.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.flexunlock.simple.DeviceStateSwitcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import timber.log.Timber

class SideKeyAccessibilityService : AccessibilityService() {
    companion object {
        private const val TAG = "SideKeyA11y"
        private const val DOUBLE_CLICK_THRESHOLD_MS = 400L
    }

    private var lastVolUpTime = 0L
    private var lastVolDownTime = 0L
    private val serviceScope = CoroutineScope(Dispatchers.IO)

    override fun onServiceConnected() {
        super.onServiceConnected()
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
            notificationTimeout = 100L
        }
        serviceInfo = info
        Timber.i("$TAG: Service connected — listening for VOLUME key double-clicks")
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val now = SystemClock.uptimeMillis()
        when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    val elapsed = now - lastVolUpTime
                    if (elapsed < DOUBLE_CLICK_THRESHOLD_MS) {
                        Timber.i("$TAG: 🔥 VOLUME_UP double-click")
                        handleDoubleClick()
                        lastVolUpTime = 0
                        return true
                    }
                    lastVolUpTime = now
                }
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    val elapsed = now - lastVolDownTime
                    if (elapsed < DOUBLE_CLICK_THRESHOLD_MS) {
                        Timber.i("$TAG: 🔥 VOLUME_DOWN double-click")
                        handleDoubleClick()
                        lastVolDownTime = 0
                        return true
                    }
                    lastVolDownTime = now
                }
            }
        }
        return false
    }

    private fun handleDoubleClick() {
        serviceScope.launch { DeviceStateSwitcher.enableHomeMode() }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
}
