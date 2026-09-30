package com.flexunlock.dexlsp.system.runtime

import android.os.Handler
import android.os.Looper
import android.util.Log
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

internal enum class CoverLockState {
    UNLOCKED,
    LOCK_PENDING,
    LOCKED
}

internal fun coverLockStateAfterSleep(current: CoverLockState): CoverLockState =
    if (current == CoverLockState.LOCKED) CoverLockState.LOCKED else CoverLockState.LOCK_PENDING

internal fun coverLockStateAfterKeyguard(
    current: CoverLockState,
    showing: Boolean
): CoverLockState = when {
    showing -> CoverLockState.LOCKED
    current == CoverLockState.LOCK_PENDING -> CoverLockState.LOCK_PENDING
    else -> CoverLockState.UNLOCKED
}

/** Tracks the security boundary between a committed sleep and visible Keyguard. */
internal object CoverLockTransitionPolicy {
    private const val TAG = "FlexUnlock-SystemBridge"
    private const val PHONE_WINDOW_MANAGER = "com.android.server.policy.PhoneWindowManager"
    private const val LOCK_PENDING_TIMEOUT_MS = 2_500L

    private val stateLock = Any()
    private val handler = Handler(Looper.getMainLooper())
    private var pendingGeneration = 0L

    @Volatile
    private var state = CoverLockState.UNLOCKED

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        val policyClass = XposedHelpers.findClassIfExists(
            PHONE_WINDOW_MANAGER,
            lpparam.classLoader
        ) ?: return log("cover lock transition policy unavailable: PhoneWindowManager missing")

        runCatching {
            val sleepMethods = policyClass.declaredMethods.filter { method ->
                method.name == "startedGoingToSleep" &&
                    method.parameterTypes.firstOrNull() == Int::class.javaPrimitiveType
            }
            sleepMethods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (
                            !RuntimeFacts.isClosed() ||
                            !com.flexunlock.dexlsp.CoverDisplayResolver.isBuiltInTarget()
                        ) return
                        val reason = (param.args.firstOrNull() as? Number)?.toInt()
                        XposedHelpers.callMethod(
                            param.thisObject,
                            "sendCloseSystemWindows",
                            "globalactions"
                        )
                        markLockPending("started-going-to-sleep reason=$reason")
                        CoverKeyguardSurfacePolicy.prepareHiddenForSleep()
                    }
                })
            }

            val wakeMethods = policyClass.declaredMethods.filter { method ->
                method.name == "startedWakingUp" &&
                    method.parameterTypes.firstOrNull() == Int::class.javaPrimitiveType
            }
            wakeMethods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (
                            !RuntimeFacts.isClosed() ||
                            !com.flexunlock.dexlsp.CoverDisplayResolver.isBuiltInTarget() ||
                            !isAccessRestricted()
                        ) return
                        if (NativeSecondaryHomeRouter.isCoverKeyguardShowing()) return
                        val reason = (param.args.firstOrNull() as? Number)?.toInt()
                        schedulePendingRelease()
                        CoverDisplayPolicy.requestKeyguardForCoverWake(
                            "started-waking-up reason=$reason"
                        )
                        log("display-1 wake protected for restricted Keyguard reason=$reason")
                    }
                })
            }
            log(
                "cover lock transition policy installed " +
                    "sleepMethods=${sleepMethods.size} wakeMethods=${wakeMethods.size}"
            )
        }.onFailure { error ->
            log("cover lock transition policy unavailable: ${error.message}")
        }
    }

    fun onKeyguardStateChanged(showing: Boolean) {
        synchronized(stateLock) {
            val previous = state
            if (showing) pendingGeneration++
            state = coverLockStateAfterKeyguard(previous, showing)
            if (state != previous || (!showing && state == CoverLockState.LOCK_PENDING)) {
                log(
                    "display-1 lock transition keyguardShowing=$showing " +
                        "state=$previous->$state"
                )
            }
        }
    }

    fun isLockPending(): Boolean = state == CoverLockState.LOCK_PENDING

    fun isAccessRestricted(): Boolean = state != CoverLockState.UNLOCKED

    private fun markLockPending(reason: String) {
        val generation = synchronized(stateLock) {
            val previous = state
            state = coverLockStateAfterSleep(previous)
            pendingGeneration++
            log("cover lock committed reason=$reason state=$previous->$state")
            pendingGeneration
        }
    }

    private fun schedulePendingRelease() {
        val generation = synchronized(stateLock) {
            if (state != CoverLockState.LOCK_PENDING) return
            pendingGeneration
        }
        handler.postDelayed(
            {
                val released = synchronized(stateLock) {
                    if (
                        generation != pendingGeneration ||
                        state != CoverLockState.LOCK_PENDING ||
                        NativeSecondaryHomeRouter.isCoverKeyguardShowing()
                    ) {
                        false
                    } else {
                        state = CoverLockState.UNLOCKED
                        pendingGeneration++
                        true
                    }
                }
                if (released) {
                    CoverWakeBrightnessGatePolicy.forceCancel(
                        reason = "lock-pending-timeout",
                        restoreBrightness = true
                    )
                    CoverKeyguardSurfacePolicy.forceRelease("lock-pending-timeout")
                    log("cover lock pending timed out; native unlocked rendering restored")
                }
            },
            LOCK_PENDING_TIMEOUT_MS
        )
    }

    private fun log(message: String) {
        Log.i(TAG, message)
        XposedBridge.log("$TAG: $message")
    }
}
