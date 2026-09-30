package com.flexunlock.dexlsp.system.runtime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.UserManager
import android.provider.Settings
import android.util.Log
import android.view.Display
import com.flexunlock.dexlsp.CoverQsGridConfig
import com.flexunlock.dexlsp.CoverQsMode
import com.flexunlock.dexlsp.CoverQsModeConfig
import com.flexunlock.dexlsp.CoverRuntime
import com.flexunlock.dexlsp.system.session.CoverEffect
import com.flexunlock.dexlsp.system.session.CoverEvent
import com.flexunlock.dexlsp.system.session.CoverSession
import com.flexunlock.dexlsp.system.session.DexState
import com.flexunlock.dexlsp.system.session.DisplayState
import com.flexunlock.dexlsp.system.session.FoldState
import com.flexunlock.dexlsp.system.session.ShellState
import com.flexunlock.dexlsp.system.session.reduce
import de.robv.android.xposed.XposedBridge
import java.util.concurrent.atomic.AtomicLong

class CoverSessionCoordinator private constructor(
    private val context: Context
) {
    private val handler = Handler(Looper.getMainLooper())
    private val displayManager = context.getSystemService(DisplayManager::class.java)
    @Volatile
    private var session = CoverSession()

    @Volatile
    private var bootCompleted = false

    @Volatile
    private var bootPhaseObserved = false

    @Volatile
    private var keyguardReconcileToken = 0L

    @Volatile
    private var keyguardHomeRequestToken = Long.MIN_VALUE

    @Volatile
    private var homeStartIssuedGeneration = Long.MIN_VALUE

    private val fullDexModeGeneration = AtomicLong(0L)

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = observeDisplay(displayId)

        override fun onDisplayRemoved(displayId: Int) = observeDisplay(displayId)

        override fun onDisplayChanged(displayId: Int) = observeDisplay(displayId)
    }

    private val bootCompletedReceiver = object : BroadcastReceiver() {
        override fun onReceive(receiverContext: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
            completeBootGate("broadcast")
        }
    }

    private val userUnlockedReceiver = object : BroadcastReceiver() {
        override fun onReceive(receiverContext: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_USER_UNLOCKED) return
            completeBootGate("user-unlocked")
        }
    }

    private val manualControlReceiver = object : BroadcastReceiver() {
        override fun onReceive(receiverContext: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_ENABLE_COVER_DEX -> {
                    if (CoverQsModeConfig.readTransaction(context).isStableFull) return
                    dispatch(CoverEvent.EnabledChanged(true))
                    log("manual cover DeX enable requested")
                }
                ACTION_DISABLE_COVER_DEX -> {
                    dispatch(CoverEvent.EnabledChanged(false))
                    log("manual cover DeX disable requested")
                }
                ACTION_RESTART_COVER_DEX -> {
                    if (CoverQsModeConfig.readTransaction(context).isStableFull) return
                    dispatch(CoverEvent.EnabledChanged(false))
                    dispatch(CoverEvent.EnabledChanged(true))
                    log("manual cover DeX restart requested")
                }
                CoverQsGridConfig.ACTION_SET -> {
                    val grid = CoverQsGridConfig.fromColumns(
                        intent.getIntExtra(
                            CoverQsGridConfig.EXTRA_COLUMNS,
                            CoverQsGridConfig.read(context).columns
                        )
                    )
                    Settings.System.putInt(
                        context.contentResolver,
                        CoverQsGridConfig.SETTINGS_KEY,
                        grid.columns
                    )
                    context.sendBroadcast(
                        Intent(CoverQsGridConfig.ACTION_CHANGED).apply {
                            setPackage("com.android.systemui")
                            putExtra(CoverQsGridConfig.EXTRA_COLUMNS, grid.columns)
                        }
                    )
                    log("cover QS grid changed to ${grid.label} capacity=${grid.capacity}")
                }
            }
        }
    }

    /**
     * SystemUI 首帧门控 armed 信号:收到后 safety layer 才允许释放,
     * 避免锁屏页横向滑动动画在门控生效前可见。
     */
    private val firstFrameGateReceiver = object : BroadcastReceiver() {
        override fun onReceive(receiverContext: Context?, intent: Intent?) {
            if (intent?.action == CoverRuntime.COVER_WIDGET_AUTH_ACTION) {
                NativeSecondaryHomeRouter.markCoverWidgetAuthentication()
                return
            }
            if (intent?.action != CoverRuntime.COVER_FIRST_FRAME_GATE_ACTION) return
            val armed = intent.getBooleanExtra(
                CoverRuntime.EXTRA_COVER_FIRST_FRAME_GATE_ARMED,
                false
            )
            val pending = intent.getBooleanExtra(
                CoverRuntime.EXTRA_COVER_FIRST_FRAME_GATE_PENDING,
                false
            )
            if (!armed && !pending) return
            val generation = intent.getLongExtra(
                CoverRuntime.EXTRA_COVER_FIRST_FRAME_GATE_GENERATION,
                Long.MIN_VALUE
            )
            handler.post {
                if (pending) {
                    CoverKeyguardSurfacePolicy.onFirstFrameGatePending(generation)
                }
                if (armed) {
                    CoverKeyguardSurfacePolicy.onFirstFrameGateArmed(generation)
                    CoverTimeoutPolicy.onCoverKeyguardSurfaceReady()
                }
            }
        }
    }

    fun start(initialFoldState: FoldState) {
        context.registerReceiver(
            bootCompletedReceiver,
            IntentFilter(Intent.ACTION_BOOT_COMPLETED),
            Context.RECEIVER_NOT_EXPORTED
        )
        context.registerReceiver(
            userUnlockedReceiver,
            IntentFilter(Intent.ACTION_USER_UNLOCKED),
            Context.RECEIVER_NOT_EXPORTED
        )
        context.registerReceiver(
            manualControlReceiver,
            IntentFilter().apply {
                addAction(ACTION_ENABLE_COVER_DEX)
                addAction(ACTION_DISABLE_COVER_DEX)
                addAction(ACTION_RESTART_COVER_DEX)
                addAction(CoverQsGridConfig.ACTION_SET)
            },
            CONTROL_PERMISSION,
            handler,
            Context.RECEIVER_EXPORTED
        )
        context.registerReceiver(
            firstFrameGateReceiver,
            IntentFilter().apply {
                addAction(CoverRuntime.COVER_FIRST_FRAME_GATE_ACTION)
                addAction(CoverRuntime.COVER_WIDGET_AUTH_ACTION)
            },
            CoverRuntime.COVER_BROADCAST_PERMISSION,
            handler,
            Context.RECEIVER_EXPORTED
        )
        displayManager.registerDisplayListener(displayListener, handler)
        dispatch(CoverEvent.FoldObserved(RuntimeFacts.sessionFoldState(initialFoldState)))
        if (CoverQsModeConfig.readTransaction(context).isStableFull) {
            dispatch(CoverEvent.EnabledChanged(false))
        }
        bootCompleted = isBootCompleted()
        if (bootCompleted) {
            dispatch(CoverEvent.DisplayObserved(currentDisplayState()))
        } else {
            log("coordinator awaiting BOOT_COMPLETED before preview launch")
        }
        log("coordinator started initialFold=$initialFoldState display=${currentDisplayState()}")
    }

    fun onBootCompletedPhase() {
        if (bootCompleted || bootPhaseObserved) return
        bootPhaseObserved = true
        awaitUnlockedUserAfterBootPhase()
    }

    fun onFoldState(state: FoldState) {
        dispatch(CoverEvent.FoldObserved(RuntimeFacts.sessionFoldState(state)))
    }

    internal fun onQsModeChanged(mode: CoverQsMode) {
        dispatch(CoverEvent.EnabledChanged(mode == CoverQsMode.ORIGINAL))
    }

    internal fun onFullDexModeChanged(restartLauncher: Boolean) {
        val token = fullDexModeGeneration.incrementAndGet()
        handler.post {
            val removed = NativeSecondaryHomeRouter.removeTargetHomeForModeChange()
            val finishRestart: (Boolean) -> Unit = { launcherReady ->
                handler.post {
                    if (token != fullDexModeGeneration.get()) return@post
                    log(
                        "full DeX Home restart removed=$removed launcherReady=$launcherReady " +
                            "token=$token"
                    )
                    dispatch(CoverEvent.HomeModeChanged)
                }
                Unit
            }
            if (
                restartLauncher &&
                RuntimeFacts.isTargetSessionEligible() &&
                currentDisplayState() == DisplayState.READY &&
                session.enabled
            ) {
                DisplayChannelModePolicy.restartLauncherProcess(finishRestart)
            } else {
                finishRestart(true)
            }
        }
    }

    internal fun onCoverDisplayResolutionChanged(
        previous: com.flexunlock.dexlsp.CoverDisplaySnapshot?,
        current: com.flexunlock.dexlsp.CoverDisplaySnapshot?
    ) {
        handler.post {
            if (!bootCompleted) return@post
            val observed = currentDisplayState()
            val identityChanged =
                previous?.id != current?.id ||
                    previous?.uniqueId != current?.uniqueId ||
                    previous?.type != current?.type
            if (identityChanged) {
                dispatch(CoverEvent.FoldObserved(RuntimeFacts.sessionFoldState(RuntimeFacts.foldState)))
                dispatch(CoverEvent.DisplayIdentityChanged(observed))
            } else if (observed != session.display) {
                dispatch(CoverEvent.DisplayObserved(observed))
            }
            if (identityChanged) {
                keyguardReconcileToken = NativeSecondaryHomeRouter.currentKeyguardTransitionToken()
                keyguardHomeRequestToken = Long.MIN_VALUE
            }
            if (observed == DisplayState.READY) {
                handler.post {
                    if (RuntimeFacts.isTargetSecurityRestricted()) {
                        reconcileLockedCoverHomeOwner(keyguardReconcileToken)
                    } else {
                        onCoverHomeOwner(NativeSecondaryHomeRouter.currentCoverHomeOwner())
                    }
                }
            }
        }
    }

    fun isEnabled(): Boolean = session.enabled

    fun onCoverHomeOwner(owner: NativeSecondaryHomeRouter.CoverHomeOwner) {
        handler.post {
            if (
                !RuntimeFacts.isTargetSessionEligible() ||
                currentDisplayState() != DisplayState.READY ||
                !session.enabled
            ) {
                return@post
            }
            when (owner) {
                NativeSecondaryHomeRouter.CoverHomeOwner.SECONDARY_LAUNCHER -> {
                    when {
                        RuntimeFacts.isTargetSecurityRestricted() ->
                            reconcileLockedCoverHomeOwner(keyguardReconcileToken)
                        else -> dispatch(CoverEvent.ShellObserved(ShellState.ACTIVE))
                    }
                }
                NativeSecondaryHomeRouter.CoverHomeOwner.SYSTEM_UI_SUB_HOME -> {
                    if (
                        NativeSecondaryHomeRouter.isCoverKeyguardShowing() ||
                        CoverLockTransitionPolicy.isLockPending() ||
                        NativeSecondaryHomeRouter.isPreservingUnlockedWidgetPage()
                    ) {
                        log(
                            "display-1 SubHomeActivity owns locked cover; " +
                                "SecondaryLauncher recovery suppressed"
                        )
                    } else {
                        // Samsung's native swipe-up gesture launches SystemUI's
                        // SubHome directly by component, bypassing our
                        // resolveSecondaryHomeActivity route, and nothing native
                        // ever restores the module desktop afterwards. Keyguard
                        // dismissal also shows SubHome transiently while the
                        // native task-stack restoration picks the foreground, so
                        // give that a short window before reclaiming Home.
                        log(
                            "display-1 unlocked SubHomeActivity observed; " +
                                "scheduling module desktop reclaim"
                        )
                        scheduleUnlockedSubHomeReclaim(session.generation, attempt = 1)
                    }
                }
                NativeSecondaryHomeRouter.CoverHomeOwner.NONE,
                NativeSecondaryHomeRouter.CoverHomeOwner.OTHER -> Unit
            }
        }
    }

    private fun scheduleUnlockedSubHomeReclaim(generation: Long, attempt: Int) {
        val trustedLaunchDelay =
            NativeSecondaryHomeRouter.trustedKeyguardAppLaunchRemainingMillis()
        handler.postDelayed(
            { reclaimUnlockedSubHome(generation, attempt) },
            maxOf(UNLOCKED_SUB_HOME_RECLAIM_DELAY_MILLIS, trustedLaunchDelay)
        )
    }

    private fun reclaimUnlockedSubHome(generation: Long, attempt: Int) {
        if (
            session.generation != generation ||
            !RuntimeFacts.isClosed() ||
            !com.flexunlock.dexlsp.CoverDisplayResolver.isBuiltInTarget() ||
            currentDisplayState() != DisplayState.READY ||
            !session.enabled ||
            NativeSecondaryHomeRouter.isCoverKeyguardShowing() ||
            CoverLockTransitionPolicy.isLockPending() ||
            CoverLockTransitionPolicy.isAccessRestricted()
        ) {
            return
        }
        val owner = NativeSecondaryHomeRouter.currentCoverHomeOwner()
        if (owner != NativeSecondaryHomeRouter.CoverHomeOwner.SYSTEM_UI_SUB_HOME) {
            log("display-1 SubHome reclaim not needed owner=$owner attempt=$attempt")
            return
        }
        val requested = NativeSecondaryHomeRouter.startCoverHome(generation)
        log("display-1 unlocked SubHome reclaim attempt=$attempt requested=$requested")
        if (attempt < UNLOCKED_SUB_HOME_RECLAIM_MAX_ATTEMPTS) {
            scheduleUnlockedSubHomeReclaim(generation, attempt + 1)
        }
    }

    fun onCoverKeyguardShown(transitionToken: Long) {
        keyguardReconcileToken = transitionToken
        keyguardHomeRequestToken = Long.MIN_VALUE
        handler.post { reconcileLockedCoverHomeOwner(transitionToken) }
        handler.postDelayed(
            {
                if (
                    transitionToken == keyguardReconcileToken &&
                    keyguardHomeRequestToken != transitionToken &&
                    NativeSecondaryHomeRouter.isCoverKeyguardShowing()
                ) {
                    reconcileLockedCoverHomeOwner(transitionToken)
                }
            },
            KEYGUARD_HOME_RECONCILE_DELAY_MILLIS
        )
    }

    fun onCoverKeyguardDismissed(transitionToken: Long) {
        keyguardReconcileToken = transitionToken
        handler.post {
            val dismissed = NativeSecondaryHomeRouter.dismissCoverKeyguardHome(transitionToken)
            log(
                "Keyguard dismissed; foreground restore then native resume " +
                    "requested=$dismissed token=$transitionToken"
            )
            if (
                session.shell == ShellState.STARTING &&
                currentDisplayState() == DisplayState.READY &&
                RuntimeFacts.isTargetSessionEligible() &&
                session.enabled &&
                !NativeSecondaryHomeRouter.hasCoverAppForegroundOrPendingLaunch()
            ) {
                startNativeDexHome(session.generation)
            } else if (session.shell == ShellState.STARTING) {
                log("initial Secondary Home start deferred to cover App foreground")
            }
        }
    }

    private fun reconcileLockedCoverHomeOwner(token: Long) {
        if (token != keyguardReconcileToken) return
        if (
            !RuntimeFacts.isClosed() ||
            !com.flexunlock.dexlsp.CoverDisplayResolver.isBuiltInTarget() ||
            currentDisplayState() == DisplayState.ABSENT ||
            !session.enabled ||
            !NativeSecondaryHomeRouter.isCoverKeyguardShowing()
        ) {
            return
        }

        val owner = NativeSecondaryHomeRouter.currentCoverHomeOwner()
        if (owner == NativeSecondaryHomeRouter.CoverHomeOwner.SYSTEM_UI_SUB_HOME) {
            log("display-1 Keyguard owner converged owner=$owner token=$token")
            return
        }

        val requested = NativeSecondaryHomeRouter.startCoverKeyguardHome(
            session.generation,
            token
        )
        if (requested) {
            keyguardHomeRequestToken = token
        }
        log(
            "display-1 Keyguard owner reconcile owner=$owner " +
                "requested=$requested token=$token"
        )
    }

    private fun awaitUnlockedUserAfterBootPhase() {
        if (bootCompleted) return
        val userUnlocked = context.getSystemService(UserManager::class.java)?.isUserUnlocked == true
        if (userUnlocked) {
            completeBootGate("system-phase")
        } else {
            log("coordinator boot phase observed; awaiting ACTION_USER_UNLOCKED")
        }
    }

    private fun completeBootGate(source: String) {
        if (bootCompleted) return
        bootCompleted = true
        log("coordinator boot gate completed source=$source")
        CoverDisplayPolicy.onCoverDisplayStateChanged()
        if (session.shell == ShellState.STARTING) {
            dispatch(CoverEvent.ShellObserved(ShellState.ABSENT))
        }
        dispatch(CoverEvent.DisplayObserved(currentDisplayState()))
    }

    private fun observeDisplay(displayId: Int) {
        if (!com.flexunlock.dexlsp.CoverDisplayResolver.matches(displayId)) return
        if (!bootCompleted) return
        CoverDisplayPolicy.onCoverDisplayStateChanged()
        CoverTimeoutPolicy.onCoverDisplayStateChanged()
        val observed = currentDisplayState()
        if (observed == session.display) return
        dispatch(CoverEvent.DisplayObserved(observed))
        if (observed == DisplayState.READY) {
            handler.post {
                if (NativeSecondaryHomeRouter.isCoverKeyguardShowing()) {
                    reconcileLockedCoverHomeOwner(keyguardReconcileToken)
                } else {
                    onCoverHomeOwner(NativeSecondaryHomeRouter.currentCoverHomeOwner())
                }
            }
        }
    }

    private fun currentDisplayState(): DisplayState {
        val displayId = com.flexunlock.dexlsp.CoverDisplayResolver.currentId()
            ?: return DisplayState.ABSENT
        val display = displayManager.getDisplay(displayId) ?: return DisplayState.ABSENT
        return if (display.state == Display.STATE_ON) {
            DisplayState.READY
        } else {
            DisplayState.PRESENT
        }
    }

    private fun isBootCompleted(): Boolean {
        return runCatching {
            val systemProperties = Class.forName("android.os.SystemProperties")
            systemProperties.getMethod("getBoolean", String::class.java, Boolean::class.javaPrimitiveType)
                .invoke(null, "sys.boot_completed", false) as Boolean
        }.getOrDefault(false)
    }

    private fun dispatch(event: CoverEvent) {
        handler.post {
            val transition = reduce(session, event)
            if (transition.state == session && transition.effects.isEmpty()) return@post
            val previous = session
            session = transition.state
            log("event=$event state=$previous -> $session effects=${transition.effects}")
            transition.effects.forEach(::execute)
        }
    }

    private fun execute(effect: CoverEffect) {
        when (effect) {
            CoverEffect.EnsureDexStarted -> {
                log("stage-A DeX compatibility bridge requested start")
                dispatch(CoverEvent.DexObserved(DexState.ON))
            }
            CoverEffect.EnsureDexStopped -> {
                dispatch(CoverEvent.DexObserved(DexState.OFF))
            }
            is CoverEffect.StartCoverHome -> startNativeDexHome(effect.generation)
            is CoverEffect.StopCoverHome -> stopNativeDexHome(effect.generation)
            CoverEffect.StopSamsungDisplayOneShell -> {
                log("Samsung display-1 shell cleanup deferred to lifecycle adapter")
            }
        }
    }

    private fun startNativeDexHome(generation: Long) {
        if (currentDisplayState() != DisplayState.READY || !RuntimeFacts.isTargetSessionEligible()) {
            dispatch(CoverEvent.ShellObserved(ShellState.ABSENT))
            return
        }

        if (
            RuntimeFacts.isTargetSecurityRestricted()
        ) {
            log("Secondary Home start deferred while Keyguard owns cover generation=$generation")
            return
        }

        val currentOwner = NativeSecondaryHomeRouter.currentCoverHomeOwner()
        if (isTargetHomeActive(
                com.flexunlock.dexlsp.CoverDisplayResolver.isBuiltInTarget(),
                CoverLaunchAllowlist.fullDexEnabled(),
                currentOwner
            )
        ) {
            dispatch(CoverEvent.ShellObserved(ShellState.ACTIVE))
            log("Secondary Home start skipped; target already active generation=$generation")
            return
        }

        if (homeStartIssuedGeneration == generation) {
            log("Secondary Home start already issued generation=$generation")
            return
        }
        homeStartIssuedGeneration = generation

        val started = NativeSecondaryHomeRouter.startCoverHome(generation)
        if (!started) {
            val owner = NativeSecondaryHomeRouter.currentCoverHomeOwner()
            dispatch(
                CoverEvent.ShellObserved(
                    if (isTargetHomeActive(
                            com.flexunlock.dexlsp.CoverDisplayResolver.isBuiltInTarget(),
                            CoverLaunchAllowlist.fullDexEnabled(),
                            owner
                        )
                    ) {
                        ShellState.ACTIVE
                    } else {
                        ShellState.ABSENT
                    }
                )
            )
            return
        }
        handler.postDelayed(
            {
                if (
                    session.generation != generation ||
                    session.shell != ShellState.STARTING ||
                    currentDisplayState() != DisplayState.READY ||
                    !RuntimeFacts.isTargetSessionEligible() ||
                    !session.enabled
                ) {
                    return@postDelayed
                }
                if (
                    RuntimeFacts.isTargetSecurityRestricted()
                ) {
                    log("Home owner verification deferred to Keyguard generation=$generation")
                    return@postDelayed
                }
                val owner = NativeSecondaryHomeRouter.currentCoverHomeOwner()
                if (isTargetHomeActive(
                        com.flexunlock.dexlsp.CoverDisplayResolver.isBuiltInTarget(),
                        CoverLaunchAllowlist.fullDexEnabled(),
                        owner
                    )
                ) {
                    dispatch(CoverEvent.ShellObserved(ShellState.ACTIVE))
                } else {
                    log(
                        "Home owner verification failed generation=$generation owner=$owner; " +
                            "requesting bounded retry"
                    )
                    dispatch(CoverEvent.ShellObserved(ShellState.ABSENT))
                }
            },
            HOME_OWNER_VERIFY_DELAY_MILLIS
        )
    }

    private fun stopNativeDexHome(generation: Long) {
        homeStartIssuedGeneration = Long.MIN_VALUE
        val removed = NativeSecondaryHomeRouter.stopCoverHome(generation)
        log("Samsung SecondaryLauncher stop generation=$generation removed=$removed")
        dispatch(CoverEvent.ShellObserved(ShellState.ABSENT))
    }

    private fun log(message: String) {
        Log.i(TAG, message)
        XposedBridge.log("$TAG: $message")
    }

    companion object {
        private const val TAG = "FlexUnlock-SystemBridge"
        private const val KEYGUARD_HOME_RECONCILE_DELAY_MILLIS = 120L
        private const val HOME_OWNER_VERIFY_DELAY_MILLIS = 450L
        private const val UNLOCKED_SUB_HOME_RECLAIM_DELAY_MILLIS = 350L
        private const val UNLOCKED_SUB_HOME_RECLAIM_MAX_ATTEMPTS = 3
        private const val CONTROL_PERMISSION = "com.flexunlock.dexlsp.permission.CONTROL_COVER_DEX"
        private const val ACTION_ENABLE_COVER_DEX =
            "com.flexunlock.dexlsp.action.ENABLE_COVER_DEX"
        private const val ACTION_DISABLE_COVER_DEX =
            "com.flexunlock.dexlsp.action.DISABLE_COVER_DEX"
        private const val ACTION_RESTART_COVER_DEX =
            "com.flexunlock.dexlsp.action.RESTART_COVER_DEX"

        @Volatile
        private var instance: CoverSessionCoordinator? = null

        fun initialize(context: Context, initialFoldState: FoldState): CoverSessionCoordinator {
            return instance ?: synchronized(this) {
                instance ?: CoverSessionCoordinator(context).also { coordinator ->
                    instance = coordinator
                    coordinator.start(initialFoldState)
                }
            }
        }

        fun current(): CoverSessionCoordinator? = instance
    }
}
