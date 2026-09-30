package com.flexunlock.dexlsp

import android.app.AndroidAppHelper
import android.content.Intent
import android.os.SystemClock
import android.view.View
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.util.WeakHashMap

/** Delays the cover Keyguard's first visible frame until its default page is stable. */
internal object NativeCoverKeyguardTransitionHooks {
    private const val SCOPE = "KeyguardFirstFrame"
    private const val SUBLAUNCHER_DECORATION = "aod.Ep1"
    private const val WAKE_GATE_WINDOW_MS = 800L
    private const val READY_STABILITY_DELAY_MS = 40L
    private const val PREARMED_READY_FALLBACK_DELAY_MS = 120L
    private const val GATE_TIMEOUT_MS = 280L
    private const val INDEX_TOLERANCE = 0.01f

    private val gateLock = Any()
    private val hookedDecorationClasses = mutableSetOf<Class<*>>()
    private val trackedDecorations = WeakHashMap<Any, TrackedDecoration>()
    private val gates = WeakHashMap<Any, FirstFrameGate>()
    private val consumedWakeGenerations = WeakHashMap<Any, Long>()

    @Volatile
    private var wakeWindow: WakeWindow? = null

    private var nextWakeGeneration = 0L

    fun onStartedWakingUp() {
        if (!CoverDisplayResolver.isBuiltInTarget()) {
            closeWakeWindow("non-built-in target")
            return
        }
        val staleGates: List<FirstFrameGate>
        val prearmedGates: List<Pair<Any, FirstFrameGate>>
        val generation: Long
        synchronized(gateLock) {
            staleGates = gates.values.toList()
            gates.clear()
            generation = ++nextWakeGeneration
            wakeWindow = WakeWindow(
                generation = generation,
                deadlineMs = SystemClock.elapsedRealtime() + WAKE_GATE_WINDOW_MS
            )
            prearmedGates = trackedDecorations.mapNotNull { (owner, tracked) ->
                val root = tracked.root.takeIf { it.isAttachedToWindow } ?: return@mapNotNull null
                consumedWakeGenerations[owner] = generation
                val gate = FirstFrameGate(
                    root = root,
                    originalAlpha = tracked.restorableAlpha,
                    wakeGeneration = generation,
                    timeoutScheduled = true
                )
                gates[owner] = gate
                root.alpha = 0f
                owner to gate
            }
        }
        restoreStaleGates(staleGates)
        prearmedGates.forEach { (owner, gate) ->
            scheduleTimeout(owner, gate)
            schedulePrearmedReadyFallback(owner, gate)
            CoverRuntime.log(
                SCOPE,
                "first-frame gate prearmed generation=${gate.wakeGeneration}"
            )
        }
        if (prearmedGates.isNotEmpty()) {
            publishGateArmed(generation)
        } else {
            publishGatePending(generation)
        }
    }

    private fun publishGatePending(generation: Long) {
        publishGateState(generation, armed = false, pending = true)
    }

    private fun publishGateArmed(generation: Long) {
        publishGateState(generation, armed = true, pending = false)
    }

    private fun publishGateState(
        generation: Long,
        armed: Boolean,
        pending: Boolean
    ) {
        val context = AndroidAppHelper.currentApplication() ?: return
        runCatching {
            context.sendBroadcast(
                Intent(CoverRuntime.COVER_FIRST_FRAME_GATE_ACTION).apply {
                    setPackage("android")
                    addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                    putExtra(CoverRuntime.EXTRA_COVER_FIRST_FRAME_GATE_ARMED, armed)
                    putExtra(CoverRuntime.EXTRA_COVER_FIRST_FRAME_GATE_PENDING, pending)
                    putExtra(CoverRuntime.EXTRA_COVER_FIRST_FRAME_GATE_GENERATION, generation)
                }
            )
            val state = if (armed) "armed" else "pending"
            CoverRuntime.log(
                SCOPE,
                "first-frame gate $state published generation=$generation"
            )
        }.onFailure { error ->
            CoverRuntime.log(
                SCOPE,
                "first-frame gate state publish failed generation=$generation: ${error.message}"
            )
        }
    }

    fun onStartedGoingToSleep() {
        closeWakeWindow("started going to sleep")
    }

    fun onKeyguardHidden() {
        closeWakeWindow("Keyguard hidden")
    }

    fun installPlugin(classLoader: ClassLoader?) {
        if (classLoader == null) return unavailable("SubLauncher plugin ClassLoader missing")
        val decorationClass = findCompatClass(
            classLoader,
            SUBLAUNCHER_DECORATION,
            v8Name = "aod.YX0",
            v7Name = "aod.C3171nM0"
        ) ?: return unavailable("SubLauncher decoration missing")
        if (!markHooked(decorationClass)) return

        runCatching {
            XposedBridge.hookAllConstructors(decorationClass, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    // 新装饰实例创建时立即应用门控(若处于唤醒窗口),
                    // 避免等待 v(Float) 回调导致门控 armed 晚于 safety layer 释放。
                    val owner = param.thisObject ?: return
                    val root = runCatching {
                        XposedHelpers.getObjectField(owner, "z") as? View
                    }.getOrNull() ?: return
                    if (!CoverRuntime.isBuiltInCoverView(root)) return
                    trackDecoration(owner, root)
                    applyGateImmediately(owner, root)
                }
            })
            XposedBridge.hookAllMethods(
                decorationClass,
                "v",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val focus = (param.args.firstOrNull() as? Number)?.toFloat() ?: return
                        stabilizeFirstFrame(param.thisObject, focus)
                    }
                }
            )
            CoverRuntime.log(
                SCOPE,
                "SubLauncher default-page first-frame gate installed class=${decorationClass.name}"
            )
        }.onFailure {
            unmarkHooked(decorationClass)
            unavailable("SubLauncher first-frame gate failed: ${it.message}")
        }
    }

    /**
     * 新装饰实例创建时若处于唤醒窗口,立即隐藏 root(alpha=0),
     * 不等 v(Float) 回调,避免 safety layer 释放后滑动动画可见的窗口期。
     */
    private fun applyGateImmediately(owner: Any, root: View) {
        val window = wakeWindow ?: return
        if (SystemClock.elapsedRealtime() > window.deadlineMs) return
        if (!NativeCoverKeyguardHooks.isShowing()) return
        synchronized(gateLock) {
            if (wakeWindow?.generation != window.generation) return
            if (consumedWakeGenerations[owner] == window.generation) return
            consumedWakeGenerations[owner] = window.generation
            val existing = gates[owner]
            if (existing != null && existing.root === root) return
            val gate = FirstFrameGate(
                root = root,
                originalAlpha = trackedDecorations[owner]?.restorableAlpha ?: root.alpha.takeIf {
                    it > 0.01f
                } ?: 1f,
                wakeGeneration = window.generation
            )
            gates[owner] = gate
            root.alpha = 0f
            if (!gate.timeoutScheduled) {
                gate.timeoutScheduled = true
                scheduleTimeout(owner, gate)
                schedulePrearmedReadyFallback(owner, gate)
                CoverRuntime.log(
                    SCOPE,
                    "first-frame gate applied at decoration creation generation=${gate.wakeGeneration}"
                )
                publishGateArmed(gate.wakeGeneration)
            }
        }
    }

    private fun stabilizeFirstFrame(owner: Any, focus: Float) {
        val root = runCatching {
            XposedHelpers.getObjectField(owner, "z") as? View
        }.getOrNull() ?: return
        if (!CoverRuntime.isBuiltInCoverView(root)) return
        val tracked = trackDecoration(owner, root)

        if (!NativeCoverKeyguardHooks.isShowing()) {
            if (wakeWindow == null) {
                releaseGate(owner, null, "Keyguard hidden")
            }
            return
        }

        val window = wakeWindow ?: return
        if (SystemClock.elapsedRealtime() > window.deadlineMs) return

        val gate = synchronized(gateLock) {
            if (wakeWindow?.generation != window.generation) return@synchronized null

            val existing = gates[owner]
            val replacingActiveRoot = existing != null &&
                existing.wakeGeneration == window.generation &&
                existing.root !== root
            if (existing != null && existing.wakeGeneration == window.generation) {
                if (!replacingActiveRoot) return@synchronized existing
                existing.root.alpha = existing.originalAlpha
                gates.remove(owner)
            }
            if (
                !replacingActiveRoot &&
                consumedWakeGenerations[owner] == window.generation
            ) {
                return@synchronized null
            }

            consumedWakeGenerations[owner] = window.generation
            FirstFrameGate(
                root = root,
                originalAlpha = tracked.restorableAlpha,
                wakeGeneration = window.generation
            ).also { created ->
                gates[owner] = created
                root.alpha = 0f
            }
        } ?: return

        gate.root.alpha = 0f
        if (!gate.timeoutScheduled) {
            gate.timeoutScheduled = true
            scheduleTimeout(owner, gate)
            CoverRuntime.log(
                SCOPE,
                "first-frame gate armed generation=${gate.wakeGeneration}"
            )
            publishGateArmed(gate.wakeGeneration)
        }

        val stabilityGeneration = ++gate.stabilityGeneration
        if (!isDefaultPageReady(owner, focus)) return
        gate.root.postDelayed(
            {
                val stillStable = synchronized(gateLock) {
                    val current = gates[owner]
                    current === gate && current.stabilityGeneration == stabilityGeneration
                }
                if (stillStable && isDefaultPageReady(owner, null)) {
                    releaseGate(owner, gate, "default page stable")
                }
            },
            READY_STABILITY_DELAY_MS
        )
    }

    private fun trackDecoration(owner: Any, root: View): TrackedDecoration =
        synchronized(gateLock) {
            val activeGate = gates[owner]?.takeIf { it.root === root }
            val existing = trackedDecorations[owner]?.takeIf { it.root === root }
            TrackedDecoration(
                root = root,
                restorableAlpha = activeGate?.originalAlpha
                    ?: existing?.restorableAlpha
                    ?: root.alpha.takeIf { it > 0.01f }
                    ?: 1f
            ).also { trackedDecorations[owner] = it }
        }

    private fun schedulePrearmedReadyFallback(owner: Any, gate: FirstFrameGate) {
        gate.root.postDelayed(
            {
                val untouched = synchronized(gateLock) {
                    val current = gates[owner]
                    current === gate && current.stabilityGeneration == 0
                }
                if (
                    untouched &&
                    NativeCoverKeyguardHooks.isShowing() &&
                    isDefaultPageReady(owner, null)
                ) {
                    releaseGate(owner, gate, "prearmed default page unchanged")
                }
            },
            PREARMED_READY_FALLBACK_DELAY_MS
        )
    }

    private fun scheduleTimeout(owner: Any, gate: FirstFrameGate) {
        gate.root.postDelayed(
            { releaseGate(owner, gate, "initialization timeout") },
            GATE_TIMEOUT_MS
        )
    }

    private fun isDefaultPageReady(owner: Any, observedFocus: Float?): Boolean {
        val state = runCatching {
            XposedHelpers.getObjectField(owner, "b")
        }.getOrNull() ?: return false
        val focus = observedFocus ?: (stateValue(state, "A2") as? Number)?.toFloat() ?: return false
        val defaultIndex = (stateValue(state, "W1") as? Number)?.toFloat() ?: return false
        val mode = stateValue(state, "F2")?.toString() ?: return false
        return mode != "None" && kotlin.math.abs(focus - defaultIndex) <= INDEX_TOLERANCE
    }

    private fun stateValue(state: Any, accessor: String): Any? = runCatching {
        val flow = XposedHelpers.callMethod(state, accessor)
        XposedHelpers.callMethod(flow, "getValue")
    }.getOrNull()

    private fun releaseGate(owner: Any, expected: FirstFrameGate?, reason: String) {
        val gate = synchronized(gateLock) {
            val current = gates[owner] ?: return
            if (expected != null && current !== expected) return
            gates.remove(owner)
            current
        }
        gate.root.alpha = gate.originalAlpha
        CoverRuntime.log(
            SCOPE,
            "first-frame gate released generation=${gate.wakeGeneration} " +
                "reason=$reason alpha=${gate.originalAlpha}"
        )
    }

    private fun closeWakeWindow(reason: String) {
        val staleGates = synchronized(gateLock) {
            wakeWindow = null
            gates.values.toList().also { gates.clear() }
        }
        restoreStaleGates(staleGates)
        if (staleGates.isNotEmpty()) {
            CoverRuntime.log(
                SCOPE,
                "first-frame gates cleared count=${staleGates.size} reason=$reason"
            )
        }
    }

    private fun restoreStaleGates(staleGates: List<FirstFrameGate>) {
        staleGates.forEach { gate ->
            gate.root.post {
                val gatedAgain = synchronized(gateLock) {
                    gates.values.any { current -> current.root === gate.root }
                }
                if (!gatedAgain) gate.root.alpha = gate.originalAlpha
            }
        }
    }

    private fun markHooked(type: Class<*>): Boolean =
        synchronized(hookedDecorationClasses) { hookedDecorationClasses.add(type) }

    private fun unmarkHooked(type: Class<*>) {
        synchronized(hookedDecorationClasses) { hookedDecorationClasses.remove(type) }
    }

    private fun unavailable(reason: String) {
        CoverRuntime.log(SCOPE, "first-frame gate unavailable: $reason")
    }

    private data class WakeWindow(
        val generation: Long,
        val deadlineMs: Long
    )

    private data class TrackedDecoration(
        val root: View,
        val restorableAlpha: Float
    )

    private data class FirstFrameGate(
        val root: View,
        val originalAlpha: Float,
        val wakeGeneration: Long,
        var stabilityGeneration: Int = 0,
        var timeoutScheduled: Boolean = false
    )
}
