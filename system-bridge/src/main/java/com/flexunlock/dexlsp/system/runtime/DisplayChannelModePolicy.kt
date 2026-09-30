package com.flexunlock.dexlsp.system.runtime

import android.app.ActivityManager
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import com.flexunlock.dexlsp.CoverQsMode
import com.flexunlock.dexlsp.CoverQsModeConfig
import com.flexunlock.dexlsp.resolveFullQsWallpaperWhich
import com.flexunlock.dexlsp.CoverQsTransaction
import com.flexunlock.dexlsp.CoverQsTransition
import com.flexunlock.dexlsp.DisplayMetricsSnapshot
import com.flexunlock.dexlsp.config.CoverDisplayConfig
import com.flexunlock.dexlsp.config.CoverDisplayOverride
import com.flexunlock.dexlsp.system.session.FoldState
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicLong

internal enum class DisplayRecoveryTrigger {
    ORIGINAL_REQUEST,
    TOPOLOGY_CHANGED,
    FOLD_CHANGED
}

internal fun shouldRetryDisplayRecovery(
    transaction: CoverQsTransaction,
    trigger: DisplayRecoveryTrigger,
    foldState: FoldState = FoldState.UNKNOWN,
    nowUptimeMillis: Long = Long.MAX_VALUE,
    topologyRetryAfterUptimeMillis: Long = Long.MIN_VALUE
): Boolean = transaction.state == CoverQsTransition.RECOVERING &&
    (trigger != DisplayRecoveryTrigger.FOLD_CHANGED || foldState != FoldState.UNKNOWN) &&
    (trigger != DisplayRecoveryTrigger.TOPOLOGY_CHANGED ||
        nowUptimeMillis >= topologyRetryAfterUptimeMillis)

internal fun hasRequiredBuiltInDisplays(displays: List<Pair<Int, Int>>): Boolean =
    DISPLAY_IDS.all { requiredId ->
        displays.any { (displayId, type) ->
            displayId == requiredId && type == DISPLAY_TYPE_BUILT_IN
        }
    }

internal fun shouldSwapDisplayLayout(deviceState: Int): Boolean =
    deviceState in CLOSED_STATES

internal fun hasUiProcessRestarted(previousPid: Int?, currentPid: Int?): Boolean =
    currentPid != null && currentPid != previousPid

internal fun fullCanvasOverride(
    snapshot: DisplayMetricsSnapshot,
    configured: CoverDisplayOverride?
): CoverDisplayOverride {
    val edge = minOf(snapshot.initialWidth, snapshot.initialHeight)
    return configured ?: CoverDisplayOverride(edge - 1, edge, snapshot.initialDensity)
}

internal object DisplayChannelModePolicy {
    @Volatile private var fullMode = readBootMode()
    @Volatile private var mapper: Any? = null
    @Volatile private var windowManagerService: Any? = null
    @Volatile private var context: Context? = null
    @Volatile private var transaction = CoverQsTransaction()
    private val nativeIds = IdentityHashMap<Any, IntArray>()
    private val handler = Handler(Looper.getMainLooper())
    private val generation = AtomicLong(0L)
    @Volatile private var recoveryRoundActive = false
    @Volatile private var stableReconcileActive = false
    @Volatile private var stableReconcilePending = false
    @Volatile private var topologyRecoveryRetryAfterUptimeMillis = Long.MIN_VALUE
    @Volatile private var lastFullFoldState = FoldState.UNKNOWN
    private var displayListenerRegistered = false
    private var topologyValidationGeneration = 0L

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        installWallpaperModePolicy(lpparam.classLoader)
        val windowManagerClass = XposedHelpers.findClass(
            "com.android.server.wm.WindowManagerService",
            lpparam.classLoader
        )
        windowManagerClass.declaredMethods
            .filter { method ->
                method.name == "main" &&
                    java.lang.reflect.Modifier.isStatic(method.modifiers) &&
                    method.returnType == windowManagerClass
            }
            .forEach { method ->
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        windowManagerService = param.result
                    }
                })
            }
        val flipPolicyClass = XposedHelpers.findClass(
            "com.android.server.wm.FlipDisplayController\$1",
            lpparam.classLoader
        )
        XposedBridge.hookAllMethods(
            flipPolicyClass,
            "shouldNotTopDisplay",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!fullMode) return
                    val displayId = (param.args.firstOrNull() as? Number)?.toInt() ?: return
                    if (displayId in DISPLAY_IDS) param.result = displayId == 1
                }
            }
        )
        val mapperClass = XposedHelpers.findClass(
            "com.android.server.display.LogicalDisplayMapper",
            lpparam.classLoader
        )
        XposedBridge.hookAllConstructors(mapperClass, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                mapper = param.thisObject
            }
        })
        XposedBridge.hookAllMethods(mapperClass, "applyLayoutLocked", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                mapper = param.thisObject
            }
        })

        val layoutMapClass = XposedHelpers.findClass(
            "com.android.server.display.DeviceStateToLayoutMap",
            lpparam.classLoader
        )
        XposedHelpers.findAndHookMethod(
            layoutMapClass,
            "get",
            Int::class.javaPrimitiveType,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val state = (param.args[0] as Number).toInt()
                    runCatching {
                        applyLayout(param.result ?: return, fullMode && shouldSwapDisplayLayout(state))
                    }.onFailure { error ->
                        log("display layout hook failed: ${error.message}")
                    }
                }
            }
        )
        log("display channel hook installed bootFull=$fullMode")
    }

    private fun installWallpaperModePolicy(classLoader: ClassLoader) {
        val wallpaperServiceClass = XposedHelpers.findClassIfExists(
            "com.samsung.android.server.wallpaper.SemWallpaperManagerService",
            classLoader
        ) ?: return log("FULL wallpaper mode unavailable: service class missing")
        val subDisplayModeClass = XposedHelpers.findClassIfExists(
            "com.samsung.android.server.wallpaper.SubDisplayMode",
            classLoader
        ) ?: return log("FULL wallpaper mode unavailable: sub-display class missing")

        XposedBridge.hookAllMethods(
            wallpaperServiceClass,
            "getCurrentImplicitMode",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (wallpaperFullModeActive()) param.result = 4
                }
            }
        )
        XposedBridge.hookAllMethods(
            wallpaperServiceClass,
            "getModeEnsuredWhich",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val which = (param.args.firstOrNull() as? Number)?.toInt() ?: return
                    val resolved = resolveFullQsWallpaperWhich(wallpaperFullModeActive(), which)
                    if (resolved != which) param.result = resolved
                }
            }
        )
        XposedBridge.hookAllMethods(
            subDisplayModeClass,
            "getFolderStateBasedWhich",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val which = (param.args.firstOrNull() as? Number)?.toInt() ?: return
                    val resolved = resolveFullQsWallpaperWhich(wallpaperFullModeActive(), which)
                    if (resolved != which) param.result = resolved
                }
            }
        )
        log("FULL wallpaper mode policy installed")
    }

    private fun wallpaperFullModeActive(): Boolean =
        fullMode && (context == null || transaction.isStableFull)

    fun bind(systemContext: Context) {
        context = systemContext
        registerTopologyListener(systemContext.getSystemService(DisplayManager::class.java))
        transaction = CoverQsModeConfig.readTransaction(systemContext)
        when {
            transaction.isStableFull && transaction.snapshots.size == 2 -> {
                fullMode = true
                lastFullFoldState = if (RuntimeFacts.isClosed()) FoldState.CLOSED else FoldState.OPENED
                writeBootMode(true)
                reconcileStableMode(restartUi = false)
            }
            transaction.isStableOriginal -> {
                fullMode = false
                lastFullFoldState = FoldState.UNKNOWN
                writeBootMode(false)
                reapplyLayout()
                CoverDisplayConfigurationController.restoreNativeMetrics(0)
            }
            else -> recoverInterruptedTransaction()
        }
        log("display channel bound state=${transaction.state} applied=${transaction.applied}")
    }

    fun update(mode: CoverQsMode, callback: (Boolean) -> Unit) {
        if (!transaction.state.isStable()) {
            if (mode == CoverQsMode.ORIGINAL && shouldRetryDisplayRecovery(
                    transaction,
                    DisplayRecoveryTrigger.ORIGINAL_REQUEST
                )
            ) {
                startRecoveryRound("original-request", callback)
            } else {
                callback(false)
            }
            return
        }
        if (mode == transaction.applied) return callback(true)
        if (mode == CoverQsMode.FULL) enable(callback) else disable(callback)
    }

    fun onFoldState(state: FoldState) {
        if (shouldRetryDisplayRecovery(
                transaction,
                DisplayRecoveryTrigger.FOLD_CHANGED,
                state
            )
        ) {
            startRecoveryRound("fold:$state") { success ->
                log("display recovery retried by fold state success=$success")
            }
            return
        }
        if (!transaction.isStableFull || state == FoldState.UNKNOWN) return
        if (state == lastFullFoldState) return
        lastFullFoldState = state
        reconcileStableMode(restartUi = true)
    }

    fun isAppliedFull(): Boolean = transaction.isStableFull

    fun onCanvasOverrideChanged() {
        if (transaction.isStableFull) reconcileStableMode(restartUi = false)
    }

    private fun enable(callback: (Boolean) -> Unit) {
        val systemContext = context ?: return callback(false)
        if (!com.flexunlock.dexlsp.CoverDisplayResolver.matches(1)) {
            log("FULL channel rejected: selected display is not the built-in cover")
            return callback(false)
        }
        if (!reapplyLayout() || !CoverDisplayConfigurationController.restoreNativeMetrics(0)) {
            return callback(false)
        }
        val snapshots = captureTopology() ?: return callback(false)
        val pending = CoverQsTransaction(
            requested = CoverQsMode.FULL,
            applied = CoverQsMode.ORIGINAL,
            state = CoverQsTransition.ENABLING,
            snapshots = snapshots
        )
        if (!CoverQsModeConfig.write(systemContext, pending)) return callback(false)
        transaction = pending
        CoverSessionCoordinator.current()?.onQsModeChanged(CoverQsMode.FULL)
        fullMode = true
        writeBootMode(false)
        if (!reapplyLayout()) return beginRecovery { callback(false) }
        applyFullAttempt(generation.incrementAndGet(), 0, callback)
    }

    private fun disable(callback: (Boolean) -> Unit) {
        val systemContext = context ?: return callback(false)
        val pending = transaction.copy(
            requested = CoverQsMode.ORIGINAL,
            state = CoverQsTransition.DISABLING
        )
        if (!CoverQsModeConfig.write(systemContext, pending)) return callback(false)
        transaction = pending
        fullMode = false
        writeBootMode(false)
        if (!reapplyLayout()) return beginRecovery(callback)
        restoreAttempt(generation.incrementAndGet(), 0) { success ->
            if (success) callback(true) else beginRecovery(callback)
        }
    }

    private fun applyFullAttempt(token: Long, attempt: Int, callback: (Boolean) -> Unit) {
        handler.postDelayed({
            if (token != generation.get()) return@postDelayed
            val applied = RuntimeFacts.isClosed() && isExpectedTopology(swapped = true) && applyCanvas()
            if (!applied && attempt < MAX_ATTEMPTS) {
                applyFullAttempt(token, attempt + 1, callback)
            } else if (applied) {
                commitFull(callback)
            } else {
                beginRecovery { callback(false) }
            }
        }, retryDelay(attempt))
    }

    private fun commitFull(callback: (Boolean) -> Unit) {
        val systemContext = context ?: return beginRecovery { callback(false) }
        val committed = transaction.copy(
            applied = CoverQsMode.FULL,
            state = CoverQsTransition.FULL
        )
        if (!writeBootMode(true)) return beginRecovery { callback(false) }
        if (!CoverQsModeConfig.write(systemContext, committed)) {
            return beginRecovery { callback(false) }
        }
        transaction = committed
        lastFullFoldState = FoldState.CLOSED
        runCatching {
            CoverDisplayResolverBridge.refresh("full-qs-committed")
        }.onFailure { error ->
            log("FULL display resolver refresh failed: ${error.message}")
        }
        promoteFullDisplay()
        restartUiProcesses { ready ->
            if (!ready) {
                beginRecovery { callback(false) }
                return@restartUiProcesses
            }
            callback(true)
            log("display channel committed FULL after UI restart")
        }
    }

    private fun beginRecovery(callback: (Boolean) -> Unit) {
        context?.let { systemContext ->
            val recovering = transaction.copy(
                requested = CoverQsMode.ORIGINAL,
                state = CoverQsTransition.RECOVERING
            )
            CoverQsModeConfig.write(systemContext, recovering)
            transaction = recovering
        }
        fullMode = false
        lastFullFoldState = FoldState.UNKNOWN
        writeBootMode(false)
        startRecoveryRound("transaction-recovery", callback)
    }

    private fun recoverInterruptedTransaction() {
        fullMode = false
        writeBootMode(false)
        val recovering = transaction.copy(
            requested = CoverQsMode.ORIGINAL,
            state = CoverQsTransition.RECOVERING
        )
        context?.let { CoverQsModeConfig.write(it, recovering) }
        transaction = recovering
        startRecoveryRound("interrupted-transaction") { success ->
            log("interrupted display transaction recovery success=$success")
        }
    }

    private fun startRecoveryRound(reason: String, callback: (Boolean) -> Unit = {}) {
        val token = synchronized(this) {
            if (!shouldRetryDisplayRecovery(transaction, DisplayRecoveryTrigger.ORIGINAL_REQUEST) ||
                recoveryRoundActive
            ) {
                null
            } else {
                recoveryRoundActive = true
                generation.incrementAndGet()
            }
        }
        if (token == null) {
            callback(false)
            return
        }
        if (!reapplyLayout()) {
            recoveryRoundActive = false
            deferTopologyRecovery()
            callback(false)
            log("display recovery layout unavailable reason=$reason")
            return
        }
        log("display recovery round started reason=$reason")
        restoreAttempt(token, 0) { success ->
            recoveryRoundActive = false
            if (!success) deferTopologyRecovery()
            callback(success)
        }
    }

    private fun deferTopologyRecovery() {
        topologyRecoveryRetryAfterUptimeMillis =
            SystemClock.uptimeMillis() + TOPOLOGY_RECOVERY_COOLDOWN_MS
    }

    private fun restoreAttempt(token: Long, attempt: Int, callback: (Boolean) -> Unit) {
        handler.postDelayed({
            if (token != generation.get()) return@postDelayed
            val restored = runCatching {
                isExpectedTopology(swapped = false) &&
                    restoreSnapshots() &&
                    isExpectedTopology(swapped = false) &&
                    restoredMetricsVisible()
            }.onFailure { error ->
                log("display recovery verification failed: ${error.message}")
            }.getOrDefault(false)
            if (!restored && attempt < MAX_ATTEMPTS) {
                restoreAttempt(token, attempt + 1, callback)
            } else if (restored) {
                commitOriginal(callback)
            } else {
                callback(false)
                log("display channel recovery pending after $MAX_ATTEMPTS attempts")
            }
        }, retryDelay(attempt))
    }

    private fun commitOriginal(callback: (Boolean) -> Unit) {
        val systemContext = context ?: return callback(false)
        CoverDisplayConfigurationController.invalidateStoredMetrics()
        runCatching {
            CoverDisplayResolverBridge.refresh("full-qs-restored")
        }.onFailure { error ->
            log("ORIGINAL display resolver refresh failed: ${error.message}")
        }
        runCatching {
            CoverSessionCoordinator.current()?.onQsModeChanged(CoverQsMode.ORIGINAL)
        }.onFailure { error ->
            log("ORIGINAL session restore failed: ${error.message}")
        }
        restartUiProcesses { ready ->
            if (!ready) {
                val recovering = transaction.copy(
                    requested = CoverQsMode.ORIGINAL,
                    state = CoverQsTransition.RECOVERING
                )
                CoverQsModeConfig.write(systemContext, recovering)
                transaction = recovering
                callback(false)
                log("ORIGINAL UI restart incomplete; recovery remains pending")
                return@restartUiProcesses
            }
            val original = CoverQsTransaction()
            if (!CoverQsModeConfig.write(systemContext, original)) return@restartUiProcesses callback(false)
            transaction = original
            lastFullFoldState = FoldState.UNKNOWN
            CoverDisplayResolverBridge.refresh("original-ui-ready")
            callback(true)
            log("display channel committed ORIGINAL after UI restart")
        }
    }

    private fun reconcileStableMode(restartUi: Boolean) {
        val token = synchronized(this) {
            if (stableReconcileActive) {
                stableReconcilePending = true
                return
            }
            stableReconcileActive = true
            generation.incrementAndGet()
        }
        reconcileStableModeAttempt(token, 0, restartUi)
    }

    private fun reconcileStableModeAttempt(token: Long, attempt: Int, restartUi: Boolean) {
        handler.postDelayed({
            if (token != generation.get() || !transaction.isStableFull) {
                stableReconcileActive = false
                stableReconcilePending = false
                return@postDelayed
            }
            val closed = RuntimeFacts.isClosed()
            val applied = runCatching {
                reapplyLayout() && if (closed) {
                    isExpectedTopology(swapped = true) && applyCanvas()
                } else {
                    isExpectedTopology(swapped = false) &&
                        restoreSnapshots() &&
                        restoredMetricsVisible()
                }
            }.onFailure { error ->
                log("stable FULL reconcile failed: ${error.message}")
            }.getOrDefault(false)
            if (!applied && attempt < MAX_ATTEMPTS) {
                reconcileStableModeAttempt(token, attempt + 1, restartUi)
                return@postDelayed
            }
            val reconcileAgain = synchronized(this) {
                stableReconcileActive = false
                stableReconcilePending.also { stableReconcilePending = false }
            }
            if (applied && closed) promoteFullDisplay()
            log("stable FULL reconcile folded=$closed success=$applied attempts=${attempt + 1}")
            if (!applied) {
                beginRecovery { success ->
                    log("stable FULL reconcile recovery success=$success")
                }
            } else if (restartUi) {
                restartLauncherProcess { ready ->
                    log("stable FULL Launcher restart ready=$ready")
                    if (reconcileAgain) reconcileStableMode(restartUi = false)
                }
            } else if (reconcileAgain) {
                reconcileStableMode(restartUi = false)
            }
        }, retryDelay(attempt))
    }

    private fun captureTopology(): List<DisplayMetricsSnapshot>? {
        if (!RuntimeFacts.isClosed()) return null
        val manager = context?.getSystemService(DisplayManager::class.java) ?: return null
        val displays = manager.displays
        if (!hasRequiredBuiltInDisplays(displays.map { it.displayId to displayType(it) })) return null
        val inner = manager.getDisplay(0) ?: return null
        val outer = manager.getDisplay(1) ?: return null
        if (displayType(inner) != DISPLAY_TYPE_BUILT_IN ||
            displayType(outer) != DISPLAY_TYPE_BUILT_IN
        ) return null
        if (inner.mode.physicalHeight <= outer.mode.physicalHeight) return null
        return DISPLAY_IDS.map { displayId ->
            val display = manager.getDisplay(displayId) ?: return null
            CoverDisplayConfigurationController.captureMetrics(
                displayId,
                displayIdentity(display)
            ) ?: return null
        }
    }

    private fun registerTopologyListener(manager: DisplayManager) {
        if (displayListenerRegistered) return
        manager.registerDisplayListener(
            object : DisplayManager.DisplayListener {
                override fun onDisplayAdded(displayId: Int) =
                    scheduleTopologyValidation("added:$displayId")

                override fun onDisplayRemoved(displayId: Int) =
                    scheduleTopologyValidation("removed:$displayId")

                override fun onDisplayChanged(displayId: Int) =
                    scheduleTopologyValidation("changed:$displayId")
            },
            handler
        )
        displayListenerRegistered = true
    }

    private fun scheduleTopologyValidation(reason: String) {
        val token = ++topologyValidationGeneration
        handler.postDelayed(
            {
                if (token == topologyValidationGeneration) validateStableTopology(reason)
            },
            TOPOLOGY_SETTLE_DELAY_MS
        )
    }

    private fun validateStableTopology(reason: String) {
        if (shouldRetryDisplayRecovery(
                transaction,
                DisplayRecoveryTrigger.TOPOLOGY_CHANGED,
                nowUptimeMillis = SystemClock.uptimeMillis(),
                topologyRetryAfterUptimeMillis = topologyRecoveryRetryAfterUptimeMillis
            )
        ) {
            startRecoveryRound("topology:$reason") { success ->
                log("display recovery retried by topology success=$success")
            }
            return
        }
        if (!transaction.isStableFull) return
        val manager = context?.getSystemService(DisplayManager::class.java) ?: return
        if (!hasRequiredBuiltInDisplays(manager.displays.map { it.displayId to displayType(it) })) {
            log(
                "FULL core topology unavailable reason=$reason " +
                    "displays=${manager.displays.joinToString { "${it.displayId}:${displayType(it)}" }}"
            )
            reconcileStableMode(restartUi = false)
            return
        }
        val closed = RuntimeFacts.isClosed()
        if (!isExpectedTopology(swapped = closed) || (closed && !canvasMatches())) {
            reconcileStableMode(restartUi = false)
        }
    }

    private fun isExpectedTopology(swapped: Boolean): Boolean {
        val manager = context?.getSystemService(DisplayManager::class.java) ?: return false
        val snapshots = transaction.snapshots.associateBy(DisplayMetricsSnapshot::displayId)
        if (snapshots.size != 2) return false
        return DISPLAY_IDS.all { displayId ->
            val expectedId = if (swapped) 1 - displayId else displayId
            manager.getDisplay(displayId)?.let(::displayIdentity) == snapshots[expectedId]?.uniqueId
        }
    }

    private fun displayIdentity(display: Any): String =
        display.javaClass.getMethod("getUniqueId").invoke(display) as String

    private fun displayType(display: Any): Int =
        (display.javaClass.getMethod("getType").invoke(display) as Number).toInt()

    private fun applyCanvas(): Boolean {
        val systemContext = context ?: return false
        if (canvasMatches()) return true
        val outerSnapshot = transaction.snapshots.firstOrNull { it.displayId == 1 } ?: return false
        val value = fullCanvasOverride(
            outerSnapshot,
            CoverDisplayConfig.readFullQsOverride(systemContext)
        )
        if (minOf(value.width, value.height) < MIN_CANVAS_EDGE - 1) return false
        if (!CoverDisplayConfigurationController.applyTemporaryMetrics(0, value)) return false
        val metrics = DisplayMetrics()
        systemContext.getSystemService(DisplayManager::class.java)
            .getDisplay(0)?.getRealMetrics(metrics) ?: return false
        val sizeMatches = (metrics.widthPixels == value.width && metrics.heightPixels == value.height) ||
            (metrics.widthPixels == value.height && metrics.heightPixels == value.width)
        return sizeMatches &&
            metrics.densityDpi == value.densityDpi
    }

    private fun canvasMatches(): Boolean {
        val systemContext = context ?: return false
        val manager = systemContext.getSystemService(DisplayManager::class.java)
        val outerSnapshot = transaction.snapshots.firstOrNull { it.displayId == 1 } ?: return false
        val value = fullCanvasOverride(
            outerSnapshot,
            CoverDisplayConfig.readFullQsOverride(systemContext)
        )
        val metrics = DisplayMetrics()
        manager.getDisplay(0)?.getRealMetrics(metrics) ?: return false
        return (
            (metrics.widthPixels == value.width && metrics.heightPixels == value.height) ||
                (metrics.widthPixels == value.height && metrics.heightPixels == value.width)
            ) && metrics.densityDpi == value.densityDpi
    }

    private fun restoreSnapshots(): Boolean {
        if (transaction.snapshots.isEmpty()) return true
        var restored = true
        transaction.snapshots.forEach { snapshot ->
            if (!CoverDisplayConfigurationController.restoreMetrics(
                    snapshot,
                    restoreInitial = snapshot.displayId == 0
                )
            ) restored = false
        }
        return restored
    }

    private fun restoredMetricsVisible(): Boolean {
        val manager = context?.getSystemService(DisplayManager::class.java) ?: return false
        return transaction.snapshots.all { snapshot ->
            val expected = restoredDisplayMetrics(snapshot)
            val display = manager.getDisplay(snapshot.displayId) ?: return@all false
            if (displayIdentity(display) != snapshot.uniqueId) return@all false
            val metrics = DisplayMetrics().also(display::getRealMetrics)
            val sizeMatches =
                (metrics.widthPixels == expected.width &&
                    metrics.heightPixels == expected.height) ||
                    (metrics.widthPixels == expected.height &&
                        metrics.heightPixels == expected.width)
            sizeMatches && metrics.densityDpi == expected.densityDpi
        }
    }

    private fun applyLayout(layout: Any, swap: Boolean) {
        val displays = XposedHelpers.getObjectField(layout, "mDisplays") as List<*>
        val original = synchronized(nativeIds) {
            nativeIds[layout]?.takeIf { it.size == displays.size } ?: displays.map {
                XposedHelpers.getIntField(it, "mLogicalDisplayId")
            }.toIntArray().also { nativeIds[layout] = it }
        }
        displays.forEachIndexed { index, display ->
            val nativeId = original[index]
            XposedHelpers.setIntField(
                display,
                "mLogicalDisplayId",
                if (swap && nativeId in DISPLAY_IDS) 1 - nativeId else nativeId
            )
        }
    }

    private fun reapplyLayout(): Boolean = runCatching {
        val target = mapper ?: error("LogicalDisplayMapper unavailable")
        val syncRoot = XposedHelpers.getObjectField(target, "mSyncRoot")
        synchronized(syncRoot) {
            XposedHelpers.callMethod(target, "applyLayoutLocked")
            XposedHelpers.callMethod(target, "updateLogicalDisplaysLocked\$1")
        }
        true
    }.onFailure { error ->
        log("display layout reapply failed: ${error.message}")
    }.getOrDefault(false)

    private fun promoteFullDisplay() {
        val service = windowManagerService ?: return log("FULL display focus unavailable")
        val moved = runCatching {
            XposedHelpers.callMethod(service, "moveDisplayToTopInternal", 0) as Boolean
        }.onFailure { error ->
            log("FULL display focus failed: ${error.message}")
        }.getOrDefault(false)
        log("FULL display focus moved=$moved")
    }

    private fun restartUiProcesses(callback: (Boolean) -> Unit) {
        val systemContext = context ?: return callback(false)
        val activityManager = systemContext.getSystemService(ActivityManager::class.java)
        restartUiProcess(activityManager, 0, callback)
    }

    internal fun restartLauncherProcess(callback: (Boolean) -> Unit) {
        val systemContext = context ?: return callback(false)
        val activityManager = systemContext.getSystemService(ActivityManager::class.java)
        val packageName = UI_PACKAGES.first()
        val previousPid = runningProcessPid(activityManager, packageName)
        if (!killUiProcess(packageName)) return callback(false)
        awaitUiProcessRestart(activityManager, packageName, previousPid, 0, callback)
    }

    private fun restartUiProcess(
        activityManager: ActivityManager,
        index: Int,
        callback: (Boolean) -> Unit
    ) {
        if (index == UI_PACKAGES.size) {
            handler.postDelayed({ callback(true) }, UI_SETTLE_DELAY_MS)
            return
        }
        val packageName = UI_PACKAGES[index]
        val previousPid = runningProcessPid(activityManager, packageName)
        if (!killUiProcess(packageName)) {
            if (packageName == OPTIONAL_UI_PACKAGE) restartUiProcess(activityManager, index + 1, callback)
            else callback(false)
            return
        }
        awaitUiProcessRestart(activityManager, packageName, previousPid, 0) { ready ->
            if (ready || packageName == OPTIONAL_UI_PACKAGE) {
                restartUiProcess(activityManager, index + 1, callback)
            } else {
                callback(false)
            }
        }
    }

    private fun awaitUiProcessRestart(
        activityManager: ActivityManager,
        packageName: String,
        previousPid: Int?,
        attempt: Int,
        callback: (Boolean) -> Unit
    ) {
        handler.postDelayed({
            val currentPid = runningProcessPid(activityManager, packageName)
            if (hasUiProcessRestarted(previousPid, currentPid)) {
                log("UI ready package=$packageName pid=$currentPid")
                callback(true)
            } else if (attempt < UI_RESTART_MAX_ATTEMPTS) {
                awaitUiProcessRestart(activityManager, packageName, previousPid, attempt + 1, callback)
            } else {
                log("UI restart timed out package=$packageName")
                callback(false)
            }
        }, UI_RESTART_POLL_MS)
    }

    private fun runningProcessPid(activityManager: ActivityManager, packageName: String): Int? =
        activityManager.runningAppProcesses?.firstOrNull { it.processName == packageName }?.pid

    private fun killUiProcess(packageName: String): Boolean {
        val systemContext = context ?: return false
        return runCatching {
            val activityManager = XposedHelpers.callStaticMethod(
                XposedHelpers.findClass("android.app.ActivityManager", null),
                "getService"
            )
            val uid = systemContext.packageManager.getApplicationInfo(packageName, 0).uid
            XposedHelpers.callMethod(activityManager, "killApplicationProcess", packageName, uid)
            true
        }.onFailure { error ->
            log("UI restart failed package=$packageName: ${error.message}")
        }.getOrDefault(false)
    }

    private fun readBootMode(): Boolean = runCatching {
        val properties = Class.forName("android.os.SystemProperties")
        properties.getMethod("getBoolean", String::class.java, Boolean::class.javaPrimitiveType)
            .invoke(null, BOOT_PROPERTY, false) as Boolean
    }.getOrDefault(false)

    private fun writeBootMode(enabled: Boolean): Boolean = runCatching {
        val properties = Class.forName("android.os.SystemProperties")
        properties.getMethod("set", String::class.java, String::class.java)
            .invoke(null, BOOT_PROPERTY, if (enabled) "1" else "0")
        true
    }.onFailure { error ->
        log("display boot mode write failed: ${error.message}")
    }.getOrDefault(false)

    private fun CoverQsTransition.isStable(): Boolean =
        this == CoverQsTransition.ORIGINAL || this == CoverQsTransition.FULL

    private fun retryDelay(attempt: Int): Long =
        if (attempt == 0) INITIAL_DELAY_MS else RETRY_DELAY_MS

    private fun log(message: String) {
        XposedBridge.log("FlexUnlock-SystemBridge: $message")
    }

    private object CoverDisplayResolverBridge {
        fun refresh(reason: String) {
            com.flexunlock.dexlsp.CoverDisplayResolver.refreshConfiguration(reason)
            CoverDisplayConfigurationController.onResolverChanged()
        }
    }

    private val UI_PACKAGES = listOf(
        "com.sec.android.app.launcher",
        "com.android.systemui",
        "com.samsung.android.sidegesturepad"
    )
    private const val OPTIONAL_UI_PACKAGE = "com.samsung.android.sidegesturepad"
    private const val BOOT_PROPERTY = "persist.sys.flexunlock.qs_tx_full"
    private const val INITIAL_DELAY_MS = 500L
    private const val RETRY_DELAY_MS = 200L
    private const val MAX_ATTEMPTS = 20
    private const val MIN_CANVAS_EDGE = 720
    private const val TOPOLOGY_SETTLE_DELAY_MS = 1_200L
    private const val TOPOLOGY_RECOVERY_COOLDOWN_MS = 5_000L
    private const val UI_RESTART_POLL_MS = 100L
    private const val UI_RESTART_MAX_ATTEMPTS = 40
    private const val UI_SETTLE_DELAY_MS = 600L
}

private val DISPLAY_IDS = 0..1
private val CLOSED_STATES = setOf(0, 1)
private const val DISPLAY_TYPE_BUILT_IN = 1
