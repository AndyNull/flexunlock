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

/**
 * v0.45.0: 最小化 AccessibilityService
 *
 * 闪退根因：TYPES_ALL_MASK + canRetrieveWindowContent=true 会注入每个 App 进程
 *
 * 修复：
 *  1. eventTypes = 0（不监听任何事件）
 *  2. canRetrieveWindowContent = false（不读取窗口内容）
 *  3. 只保留 FLAG_REQUEST_FILTER_KEY_EVENTS（监听音量键）
 *  4. notificationTimeout = 0
 *
 * 这样 AccessibilityService 不会注入 App 进程，只拦截按键事件
 */
class SideKeyAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "FlexUnlockA11y"
        private const val DOUBLE_CLICK_THRESHOLD_MS = 400L
    }

    private var lastVolUpTime = 0L
    private var lastVolDownTime = 0L
    private val serviceScope = CoroutineScope(Dispatchers.IO)

    override fun onServiceConnected() {
        super.onServiceConnected()
        // v0.45.0: 最小化配置——只拦截按键，不监听事件，不读取窗口
        val info = AccessibilityServiceInfo().apply {
            eventTypes = 0  // 不监听任何 accessibility 事件（避免注入 App）
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
            notificationTimeout = 0L
            // 不设置 canRetrieveWindowContent（默认 false）
        }
        serviceInfo = info
        Timber.i("$TAG: Service connected (minimal mode, no injection)")
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val now = SystemClock.uptimeMillis()
        when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    val elapsed = now - lastVolUpTime
                    if (elapsed < DOUBLE_CLICK_THRESHOLD_MS) {
                        Timber.i("$TAG: VOLUME_UP double-click")
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
                        Timber.i("$TAG: VOLUME_DOWN double-click")
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
