package com.flexunlock.dexlsp.system.runtime

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.util.Pair
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

internal fun suppressInactiveInnerSecondaryHome(
    stableFullQs: Boolean,
    coverDisplayId: Int?,
    targetDisplayId: Int?
): Boolean = stableFullQs && coverDisplayId == 0 && targetDisplayId == 1

internal fun isTargetHomeActive(
    targetBuiltIn: Boolean,
    fullDexEnabled: Boolean,
    owner: NativeSecondaryHomeRouter.CoverHomeOwner
): Boolean = owner == NativeSecondaryHomeRouter.CoverHomeOwner.SECONDARY_LAUNCHER

internal fun restartableTargetHome(
    targetDisplayId: Int,
    taskDisplayId: Int?,
    activityType: Int?,
    owner: NativeSecondaryHomeRouter.CoverHomeOwner
): Boolean = taskDisplayId == targetDisplayId &&
    activityType == ACTIVITY_TYPE_HOME &&
    owner != NativeSecondaryHomeRouter.CoverHomeOwner.NONE &&
    owner != NativeSecondaryHomeRouter.CoverHomeOwner.SYSTEM_UI_SUB_HOME

internal fun duplicateSecondaryHomeTaskIds(taskIds: List<Int>): List<Int> {
    val retainedTaskId = taskIds.maxOrNull() ?: return emptyList()
    return taskIds.filter { it != retainedTaskId }
}

internal data class ExternalTaskSnapshot(
    val taskId: Int,
    val component: String,
    val lastActiveTime: Long
)

internal fun duplicateExternalTaskIds(tasks: List<ExternalTaskSnapshot>): List<Int> =
    tasks.groupBy { it.component }
        .values
        .flatMap { group ->
            group.sortedWith(
                compareByDescending<ExternalTaskSnapshot> { it.lastActiveTime }
                    .thenByDescending { it.taskId }
            ).drop(1).map { it.taskId }
        }

internal fun isTrustedKeyguardAppLaunchTransition(
    launchToken: Long?,
    dismissToken: Long,
    deadlineUptimeMillis: Long,
    nowUptimeMillis: Long
): Boolean = launchToken != null &&
    dismissToken == launchToken + 1L &&
    nowUptimeMillis <= deadlineUptimeMillis

internal fun trustedKeyguardAppLaunchRemainingMillis(
    deadlineUptimeMillis: Long?,
    nowUptimeMillis: Long
): Long = ((deadlineUptimeMillis ?: nowUptimeMillis) - nowUptimeMillis).coerceAtLeast(0L)

internal fun isTrustedKeyguardAppPackage(
    armedPackage: String?,
    targetPackage: String?
): Boolean = armedPackage != null && armedPackage == targetPackage

internal fun isCoverWidgetAuthenticationTransition(
    armedToken: Long?,
    dismissToken: Long,
    deadlineUptimeMillis: Long,
    nowUptimeMillis: Long
): Boolean = armedToken != null &&
    dismissToken == armedToken + 1L &&
    nowUptimeMillis <= deadlineUptimeMillis

object NativeSecondaryHomeRouter {
    private const val TAG = "FlexUnlock-SystemBridge"
    private const val ROOT_WINDOW_CONTAINER = "com.android.server.wm.RootWindowContainer"
    private const val ACTIVITY_TASK_SUPERVISOR = "com.android.server.wm.ActivityTaskSupervisor"
    private const val ACTIVITY_TASK_MANAGER_INTERNAL = "com.android.server.wm.ActivityTaskManagerInternal"
    private const val LOCAL_SERVICES = "com.android.server.LocalServices"
    private const val ACTIVITY_TASK_MANAGER_SERVICE =
        "com.android.server.wm.ActivityTaskManagerService"
    private const val WINDOW_MANAGER_SERVICE = "com.android.server.wm.WindowManagerService"
    private const val DEFAULT_DISPLAY_ID = 0
    private const val MAX_RUNNING_TASKS_TO_INSPECT = 500
    private const val SAMSUNG_LAUNCHER_PACKAGE = "com.sec.android.app.launcher"
    private const val SECONDARY_LAUNCHER_ACTIVITY =
        "com.honeyspace.dexservice.SecondaryLauncher"
    private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
    private const val SUB_HOME_ACTIVITY = "com.android.systemui.subscreen.SubHomeActivity"
    private const val TRANSITION_INFO = "android.window.TransitionInfo"
    private const val TRANSITION_MODE_CLOSE = 2
    private const val TRANSITION_MODE_TO_FRONT = 3
    private const val TRANSITION_MODE_TO_BACK = 4
    private const val TRANSITION_FLAG_SHOW_WALLPAPER = 1
    private const val TRANSITION_FLAG_NO_ANIMATION = 1 shl 18
    private const val RESTORATION_ANIMATION_SUPPRESSION_TIMEOUT_MS = 2_000L
    private const val COVER_WIDGET_AUTH_TIMEOUT_MS = 120_000L

    enum class CoverHomeOwner {
        NONE,
        SECONDARY_LAUNCHER,
        SYSTEM_UI_SUB_HOME,
        OTHER
    }

    @Volatile
    private var systemContext: Context? = null

    @Volatile
    private var classLoader: ClassLoader? = null

    @Volatile
    private var activityTaskManagerLocalService: Any? = null

    @Volatile
    private var observedKeyguardShowing: Boolean? = null

    private val keyguardTransitionToken = AtomicLong(0L)

    @Volatile
    private var lastPublishedOwner = CoverHomeOwner.NONE

    @Volatile
    private var coverKeyguardActivityRecord: Any? = null

    @Volatile
    private var restorableCoverActivityRecord: Any? = null

    @Volatile
    private var restorableCoverTaskId: Int? = null

    @Volatile
    private var secondaryLauncherActivityRecord: Any? = null

    private data class TrustedKeyguardAppLaunch(
        val transitionToken: Long,
        val component: ComponentName,
        val deadlineUptimeMillis: Long
    )

    @Volatile
    private var trustedKeyguardAppLaunch: TrustedKeyguardAppLaunch? = null

    private data class PendingCoverWidgetAuthentication(
        val transitionToken: Long,
        val deadlineUptimeMillis: Long
    )

    @Volatile
    private var pendingCoverWidgetAuthentication: PendingCoverWidgetAuthentication? = null

    @Volatile
    private var preserveUnlockedWidgetPage = false

    private data class PendingRestorationAnimationSuppression(
        val transitionToken: Long,
        val remainingTaskIds: Set<Int>,
        val keyguardRootTaskId: Int,
        val deadlineUptimeMillis: Long
    )

    @Volatile
    private var pendingRestorationAnimationSuppression:
        PendingRestorationAnimationSuppression? = null

    private val coverSessionTaskIds = ConcurrentHashMap.newKeySet<Int>()
    private val externalHomeRouteLocks = ConcurrentHashMap<Int, Any>()

    internal fun onCoverDisplayResolutionChanged(
        previous: com.flexunlock.dexlsp.CoverDisplaySnapshot?,
        current: com.flexunlock.dexlsp.CoverDisplaySnapshot?
    ) {
        if (
            previous?.id == current?.id &&
            previous?.uniqueId == current?.uniqueId &&
            previous?.type == current?.type
        ) return
        keyguardTransitionToken.incrementAndGet()
        coverKeyguardActivityRecord = null
        restorableCoverActivityRecord = null
        restorableCoverTaskId = null
        secondaryLauncherActivityRecord = null
        trustedKeyguardAppLaunch = null
        pendingCoverWidgetAuthentication = null
        preserveUnlockedWidgetPage = false
        pendingRestorationAnimationSuppression = null
        coverSessionTaskIds.clear()
        lastPublishedOwner = CoverHomeOwner.NONE
        CoverTimeoutPolicy.onCoverHomeOwnerChanged(CoverHomeOwner.NONE)
        if (current != null) systemContext?.let(::adoptExistingCoverSessionTasks)
        log("cover display route state cleared display=${previous?.id}->${current?.id}")
    }

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        classLoader = lpparam.classLoader
        runCatching {
            val rootWindowContainer = XposedHelpers.findClass(
                ROOT_WINDOW_CONTAINER,
                lpparam.classLoader
            )
            val methods = rootWindowContainer.declaredMethods.filter { method ->
                method.name == "resolveSecondaryHomeActivity" &&
                    method.parameterCount == 2 &&
                    method.parameterTypes.firstOrNull() == Int::class.javaPrimitiveType &&
                    method.returnType == Pair::class.java
            }
            methods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!RuntimeFacts.isTargetSessionEligible()) return
                        if (CoverSessionCoordinator.current()?.isEnabled() == false) return
                        val taskDisplayArea = param.args.getOrNull(1) ?: return
                        val targetDisplayId = displayIdOf(taskDisplayArea) ?: return
                        val coverDisplayId = com.flexunlock.dexlsp.CoverDisplayResolver.currentId()
                        val stableFullQs = systemContext?.let {
                            com.flexunlock.dexlsp.CoverQsModeConfig.readTransaction(it).isStableFull
                        } == true
                        if (suppressInactiveInnerSecondaryHome(
                                stableFullQs,
                                coverDisplayId,
                                targetDisplayId
                            )
                        ) {
                            param.result = null
                            log("FULL inactive inner secondary Home suppressed display=$targetDisplayId")
                            return
                        }
                        if (coverDisplayId != targetDisplayId) return
                        val builtInTarget =
                            com.flexunlock.dexlsp.CoverDisplayResolver.isBuiltInTarget()
                        if (
                            CoverLockTransitionPolicy.isAccessRestricted() &&
                            builtInTarget
                        ) {
                            val activityInfo = resolveSystemUiSubHome() ?: return
                            val originalIntent = (param.result as? Pair<*, *>)?.second as? Intent
                            val keyguardIntent = Intent(
                                originalIntent ?: Intent(Intent.ACTION_MAIN)
                            ).apply {
                                action = Intent.ACTION_MAIN
                                `package` = SYSTEM_UI_PACKAGE
                                component = ComponentName(SYSTEM_UI_PACKAGE, SUB_HOME_ACTIVITY)
                            }
                            param.result = Pair.create(activityInfo, keyguardIntent)
                            log("display-1 restricted Home resolved to SystemUI SubHome")
                            return
                        }
                        if (
                            isCoverKeyguardShowing() &&
                            builtInTarget
                        ) {
                            log("display-1 native secondary Home resolution retained for Keyguard")
                            return
                        }

                        val activityInfo = resolveSecondaryLauncher() ?: return
                        val originalIntent = (param.result as? Pair<*, *>)?.second as? Intent
                        val homeIntent = Intent(originalIntent ?: Intent(Intent.ACTION_MAIN)).apply {
                            action = Intent.ACTION_MAIN
                            addCategory(Intent.CATEGORY_SECONDARY_HOME)
                            `package` = SAMSUNG_LAUNCHER_PACKAGE
                            component = ComponentName(
                                SAMSUNG_LAUNCHER_PACKAGE,
                                SECONDARY_LAUNCHER_ACTIVITY
                            )
                        }
                        param.result = Pair.create(activityInfo, homeIntent)
                        log("display-1 secondary Home resolved to Samsung SecondaryLauncher")
                    }
                })
            }
            log("native secondary-home route installed methods=${methods.size}")
        }.onFailure { error ->
            log("native secondary-home route unavailable: ${error.message}")
        }
        installTopResumedObservation(lpparam)
        installKeyguardTransitionObservation(lpparam)
        installRestorationTransitionNoAnimation(lpparam)
    }

    private fun installRestorationTransitionNoAnimation(
        lpparam: XC_LoadPackage.LoadPackageParam
    ) {
        runCatching {
            val transitionClass = XposedHelpers.findClass(
                "com.android.server.wm.Transition",
                lpparam.classLoader
            )
            val transitionInfoClass = XposedHelpers.findClass(
                TRANSITION_INFO,
                lpparam.classLoader
            )
            val methods = transitionClass.declaredMethods.filter { method ->
                method.name == "calculateTransitionInfo" &&
                    Modifier.isStatic(method.modifiers) &&
                    method.returnType == transitionInfoClass
            }
            methods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val transitionInfo = param.result ?: return
                        markPendingRestorationTransitionNoAnimation(transitionInfo)
                    }
                })
            }
            log("display-1 restoration transition filter installed methods=${methods.size}")
        }.onFailure { error ->
            log("display-1 restoration transition filter unavailable: ${error.message}")
        }
    }

    private fun markPendingRestorationTransitionNoAnimation(transitionInfo: Any) {
        val pending = pendingRestorationAnimationSuppression ?: return
        val now = SystemClock.uptimeMillis()
        if (
            now > pending.deadlineUptimeMillis ||
            pending.transitionToken != currentKeyguardTransitionToken()
        ) {
            pendingRestorationAnimationSuppression = null
            log(
                "display-1 restoration transition suppression expired " +
                    "token=${pending.transitionToken}"
            )
            return
        }

        val markedTaskIds = linkedSetOf<Int>()
        var closedKeyguardRootTaskId: Int? = null
        runCatching {
            val changes = XposedHelpers.callMethod(transitionInfo, "getChanges") as? List<*>
                ?: return@runCatching
            changes.forEach { change ->
                change ?: return@forEach
                val taskInfo = XposedHelpers.callMethod(change, "getTaskInfo") ?: return@forEach
                val displayId = runCatching {
                    XposedHelpers.getIntField(taskInfo, "displayId")
                }.getOrNull() ?: return@forEach
                if (!com.flexunlock.dexlsp.CoverDisplayResolver.matches(displayId)) return@forEach
                val taskId = XposedHelpers.getIntField(taskInfo, "taskId")
                if (taskId !in pending.remainingTaskIds) return@forEach
                val mode = (XposedHelpers.callMethod(change, "getMode") as? Number)?.toInt()
                    ?: return@forEach
                if (mode != TRANSITION_MODE_TO_FRONT) return@forEach
                val flags = (XposedHelpers.callMethod(change, "getFlags") as? Number)?.toInt()
                    ?: return@forEach
                XposedHelpers.callMethod(
                    change,
                    "setFlags",
                    flags or
                        TRANSITION_FLAG_SHOW_WALLPAPER or
                        TRANSITION_FLAG_NO_ANIMATION
                )
                markedTaskIds += taskId
            }

            if (markedTaskIds.isEmpty()) return@runCatching
            changes.forEach { change ->
                change ?: return@forEach
                val taskInfo = XposedHelpers.callMethod(change, "getTaskInfo") ?: return@forEach
                val displayId = runCatching {
                    XposedHelpers.getIntField(taskInfo, "displayId")
                }.getOrNull() ?: return@forEach
                if (!com.flexunlock.dexlsp.CoverDisplayResolver.matches(displayId)) return@forEach
                val taskId = XposedHelpers.getIntField(taskInfo, "taskId")
                if (taskId != pending.keyguardRootTaskId) return@forEach
                val mode = (XposedHelpers.callMethod(change, "getMode") as? Number)?.toInt()
                    ?: return@forEach
                if (mode != TRANSITION_MODE_TO_BACK) return@forEach
                XposedHelpers.callMethod(change, "setMode", TRANSITION_MODE_CLOSE)
                closedKeyguardRootTaskId = taskId
            }
        }.onFailure { error ->
            log(
                "display-1 restoration transition suppression failed " +
                    "token=${pending.transitionToken}: ${error.message}"
            )
        }
        if (markedTaskIds.isEmpty()) return

        val remainingTaskIds = pending.remainingTaskIds - markedTaskIds
        pendingRestorationAnimationSuppression = if (remainingTaskIds.isEmpty()) {
            null
        } else {
            pending.copy(remainingTaskIds = remainingTaskIds)
        }
        log(
            "display-1 restoration transition normalized " +
                "token=${pending.transitionToken} appTaskIds=$markedTaskIds " +
                "keyguardRootClosed=$closedKeyguardRootTaskId remaining=$remainingTaskIds"
        )
    }

    private fun installKeyguardTransitionObservation(
        lpparam: XC_LoadPackage.LoadPackageParam
    ) {
        runCatching {
            val activityTaskManagerService = XposedHelpers.findClass(
                ACTIVITY_TASK_MANAGER_SERVICE,
                lpparam.classLoader
            )
            val methods = activityTaskManagerService.declaredMethods.filter { method ->
                method.name == "setLockScreenShown" &&
                    method.parameterTypes.size >= 2 &&
                    method.parameterTypes[0] == Boolean::class.javaPrimitiveType &&
                    method.parameterTypes[1] == Boolean::class.javaPrimitiveType
            }
            methods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val showing = param.args.firstOrNull() as? Boolean ?: return
                        observedKeyguardShowing = showing
                        if (showing) {
                            pendingCoverWidgetAuthentication = null
                            preserveUnlockedWidgetPage = false
                        }
                        CoverLockTransitionPolicy.onKeyguardStateChanged(showing)
                        CoverTimeoutPolicy.onKeyguardStateChanged(showing)
                        val token = keyguardTransitionToken.incrementAndGet()
                        log("display-1 Keyguard transition showing=$showing token=$token")
                        when {
                            showing -> CoverSessionCoordinator.current()?.onCoverKeyguardShown(token)
                            CoverLockTransitionPolicy.isLockPending() -> log(
                                "display-1 Keyguard dismissal ignored while lock is pending token=$token"
                            )
                            else -> CoverSessionCoordinator.current()?.onCoverKeyguardDismissed(token)
                        }
                    }
                })
            }
            log("display-1 lock-screen transition observation installed methods=${methods.size}")
        }.onFailure { error ->
            log("display-1 lock-screen transition observation unavailable: ${error.message}")
        }
    }

    private fun installTopResumedObservation(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            val activityTaskSupervisor = XposedHelpers.findClass(
                ACTIVITY_TASK_SUPERVISOR,
                lpparam.classLoader
            )
            val methods = activityTaskSupervisor.declaredMethods.filter { method ->
                method.name == "updateTopResumedActivityIfNeeded" &&
                    method.parameterCount == 1 &&
                    method.parameterTypes.firstOrNull() == String::class.java
            }
            methods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val activityRecord = param.result
                        rememberCoverForeground(activityRecord)
                        rememberCoverSessionTask(activityRecord)
                        rememberCoverKeyguardActivity(activityRecord)
                        publishCoverHomeOwner(ownerOfActivityRecord(activityRecord))
                    }
                })
            }
            log("display-1 top-resumed Home observation installed methods=${methods.size}")
        }.onFailure { error ->
            log("display-1 top-resumed Home observation unavailable: ${error.message}")
        }
    }

    private fun publishCoverHomeOwner(owner: CoverHomeOwner) {
        if (owner == CoverHomeOwner.OTHER) return
        CoverTimeoutPolicy.onCoverHomeOwnerChanged(owner)
        if (owner == lastPublishedOwner) return
        lastPublishedOwner = owner
        log("display-1 top-resumed Home owner=$owner")
        CoverSessionCoordinator.current()?.onCoverHomeOwner(owner)
    }

    private fun rememberCoverForeground(activityRecord: Any?) {
        if (!RuntimeFacts.isTargetSessionEligible() || activityRecord == null) return
        val displayId = runCatching {
            (XposedHelpers.callMethod(activityRecord, "getDisplayId") as? Number)?.toInt()
        }.getOrNull() ?: return
        if (!com.flexunlock.dexlsp.CoverDisplayResolver.matches(displayId)) return
        val component = componentOf(activityRecord) ?: return
        if (isTrustedKeyguardAppPackage(
                trustedKeyguardAppLaunch?.component?.packageName,
                component.packageName
            )
        ) {
            log(
                "display-1 trusted Keyguard App reached foreground; " +
                    "holding through dismiss component=$component"
            )
        }
        when {
            component.isSecondaryLauncher() -> {
                preserveUnlockedWidgetPage = false
                secondaryLauncherActivityRecord = activityRecord
                if (!CoverLockTransitionPolicy.isAccessRestricted()) {
                    restorableCoverActivityRecord = null
                    restorableCoverTaskId = null
                    log("display-1 foreground restore target cleared for Secondary Home")
                }
            }
            component.packageName == SYSTEM_UI_PACKAGE -> Unit
            !CoverLockTransitionPolicy.isAccessRestricted() -> {
                preserveUnlockedWidgetPage = false
                val taskId = taskIdOf(activityRecord) ?: return
                restorableCoverActivityRecord = activityRecord
                restorableCoverTaskId = taskId
                log(
                    "display-1 foreground restore target recorded " +
                        "taskId=$taskId component=$component"
                )
            }
        }
    }

    private fun rememberCoverKeyguardActivity(activityRecord: Any?) {
        if (ownerOfActivityRecord(activityRecord) == CoverHomeOwner.SYSTEM_UI_SUB_HOME) {
            coverKeyguardActivityRecord = activityRecord
        }
    }

    private fun rememberCoverSessionTask(activityRecord: Any?) {
        if (!RuntimeFacts.isTargetSessionEligible() || activityRecord == null) return
        val displayId = runCatching {
            (XposedHelpers.callMethod(activityRecord, "getDisplayId") as? Number)?.toInt()
        }.getOrNull() ?: return
        if (!com.flexunlock.dexlsp.CoverDisplayResolver.matches(displayId)) return
        val component = runCatching {
            XposedHelpers.getObjectField(activityRecord, "mActivityComponent") as? ComponentName
        }.getOrNull() ?: return
        if (
            component.isSecondaryLauncher() ||
            component.packageName == SYSTEM_UI_PACKAGE
        ) {
            return
        }
        val taskId = runCatching {
            val task = XposedHelpers.getObjectField(activityRecord, "task")
            XposedHelpers.getIntField(task, "mTaskId")
        }.getOrNull() ?: return
        if (coverSessionTaskIds.add(taskId)) {
            log("display-1 cover session task tracked taskId=$taskId component=$component")
        }
    }

    fun isCoverKeyguardShowing(): Boolean {
        observedKeyguardShowing?.let { return it }
        val localService = activityTaskManagerLocalService ?: return false
        return runCatching {
            val activityTaskManager = XposedHelpers.getSurroundingThis(localService)
            val keyguardController = XposedHelpers.getObjectField(
                activityTaskManager,
                "mKeyguardController"
            )
            val showing = XposedHelpers.callMethod(
                keyguardController,
                "isKeyguardShowing",
                DEFAULT_DISPLAY_ID
            ) as Boolean
            val goingAway = runCatching {
                XposedHelpers.callMethod(
                    keyguardController,
                    "isKeyguardGoingAway",
                    DEFAULT_DISPLAY_ID
                ) as Boolean
            }.getOrDefault(false)
            showing && !goingAway
        }.onFailure { error ->
            log("display-1 Keyguard state lookup failed: ${error.message}")
        }.getOrDefault(false)
    }

    fun observedCoverHomeOwner(): CoverHomeOwner = lastPublishedOwner

    fun currentCoverHomeOwner(): CoverHomeOwner {
        val context = systemContext ?: return CoverHomeOwner.NONE
        val activityManager = context.getSystemService(ActivityManager::class.java)
            ?: return CoverHomeOwner.NONE
        val topTask = activityManager.getRunningTasks(MAX_RUNNING_TASKS_TO_INSPECT)
            .firstOrNull { com.flexunlock.dexlsp.CoverDisplayResolver.matches(runningTaskDisplayId(it)) }
            ?: return CoverHomeOwner.NONE
        return ownerOfComponent(topTask.topActivity)
    }

    private fun ownerOfActivityRecord(activityRecord: Any?): CoverHomeOwner {
        if (activityRecord == null) return CoverHomeOwner.NONE
        val displayId = runCatching {
            (XposedHelpers.callMethod(activityRecord, "getDisplayId") as? Number)?.toInt()
        }.getOrNull() ?: return CoverHomeOwner.OTHER
        if (!com.flexunlock.dexlsp.CoverDisplayResolver.matches(displayId)) return CoverHomeOwner.OTHER
        val component = runCatching {
            XposedHelpers.getObjectField(activityRecord, "mActivityComponent") as? ComponentName
        }.getOrNull()
        return ownerOfComponent(component)
    }

    private fun ownerOfComponent(component: ComponentName?): CoverHomeOwner {
        return when {
            component == null -> CoverHomeOwner.NONE
            component.isSecondaryLauncher() -> CoverHomeOwner.SECONDARY_LAUNCHER
            component.packageName == SYSTEM_UI_PACKAGE &&
                component.className == SUB_HOME_ACTIVITY -> CoverHomeOwner.SYSTEM_UI_SUB_HOME
            else -> CoverHomeOwner.OTHER
        }
    }

    private fun componentOf(activityRecord: Any): ComponentName? = runCatching {
        XposedHelpers.getObjectField(activityRecord, "mActivityComponent") as? ComponentName
    }.getOrNull()

    private fun taskIdOf(activityRecord: Any): Int? = runCatching {
        val task = XposedHelpers.getObjectField(activityRecord, "task")
        XposedHelpers.getIntField(task, "mTaskId")
    }.getOrNull()

    fun bind(context: Context) {
        systemContext = context
        activityTaskManagerLocalService = resolveActivityTaskManagerLocalService()
        adoptExistingCoverSessionTasks(context)
        context.getSystemService(android.hardware.display.DisplayManager::class.java)
            ?.displays
            ?.map { it.displayId }
            ?.forEach(::onExternalDisplayAdded)
        log(
            "native secondary-home route bound " +
                "localService=${activityTaskManagerLocalService != null} " +
                "trackedTasks=${coverSessionTaskIds.size}"
        )
    }

    private fun adoptExistingCoverSessionTasks(context: Context) {
        if (!RuntimeFacts.isTargetSessionEligible()) return
        val activityManager = context.getSystemService(ActivityManager::class.java) ?: return
        activityManager.getRunningTasks(MAX_RUNNING_TASKS_TO_INSPECT)
            .filter { com.flexunlock.dexlsp.CoverDisplayResolver.matches(runningTaskDisplayId(it)) }
            .forEach { task ->
                val component = task.topActivity ?: task.baseActivity ?: return@forEach
                if (
                    component.packageName != SYSTEM_UI_PACKAGE &&
                    !component.isSecondaryLauncher()
                ) {
                    coverSessionTaskIds.add(task.taskId)
                }
            }
    }

    fun currentKeyguardTransitionToken(): Long = keyguardTransitionToken.get()

    fun markTrustedKeyguardAppLaunch(component: ComponentName) {
        val token = currentKeyguardTransitionToken()
        trustedKeyguardAppLaunch = TrustedKeyguardAppLaunch(
            transitionToken = token,
            component = component,
            deadlineUptimeMillis = SystemClock.uptimeMillis() +
                TRUSTED_KEYGUARD_APP_LAUNCH_TIMEOUT_MS
        )
        log("display-1 trusted Keyguard App launch armed token=$token component=$component")
    }

    fun isTrustedKeyguardAppLaunch(component: ComponentName): Boolean {
        val pending = trustedKeyguardAppLaunch ?: return false
        return isTrustedKeyguardAppPackage(
            pending.component.packageName,
            component.packageName
        ) &&
            trustedKeyguardAppLaunchRemainingMillis() > 0L
    }

    fun trustedKeyguardAppLaunchRemainingMillis(): Long {
        val pending = trustedKeyguardAppLaunch ?: return 0L
        val remaining = trustedKeyguardAppLaunchRemainingMillis(
            pending.deadlineUptimeMillis,
            SystemClock.uptimeMillis()
        )
        if (remaining == 0L) trustedKeyguardAppLaunch = null
        return remaining
    }

    fun markCoverWidgetAuthentication() {
        if (
            !RuntimeFacts.isClosed() ||
            !com.flexunlock.dexlsp.CoverDisplayResolver.isBuiltInTarget() ||
            !isCoverKeyguardShowing()
        ) return
        val now = SystemClock.uptimeMillis()
        pendingCoverWidgetAuthentication = PendingCoverWidgetAuthentication(
            transitionToken = currentKeyguardTransitionToken(),
            deadlineUptimeMillis = now + COVER_WIDGET_AUTH_TIMEOUT_MS
        )
        log("display-1 cover widget authentication armed token=${currentKeyguardTransitionToken()}")
    }

    fun isPreservingUnlockedWidgetPage(): Boolean {
        if (preserveUnlockedWidgetPage) return true
        val pending = pendingCoverWidgetAuthentication ?: return false
        val now = SystemClock.uptimeMillis()
        if (now > pending.deadlineUptimeMillis) {
            pendingCoverWidgetAuthentication = null
            return false
        }
        val token = currentKeyguardTransitionToken()
        return token == pending.transitionToken || token == pending.transitionToken + 1L
    }

    fun hasCoverAppForegroundOrPendingLaunch(): Boolean =
        trustedKeyguardAppLaunchRemainingMillis() > 0L || currentTopCoverTaskId() != null

    fun startCoverHome(
        generation: Long,
        transitionToken: Long = currentKeyguardTransitionToken()
    ): Boolean = startHomeOnCoverDisplay(
        generation = generation,
        requireKeyguard = false,
        transitionToken = transitionToken
    )

    fun startCoverKeyguardHome(generation: Long, transitionToken: Long): Boolean =
        startHomeOnCoverDisplay(
            generation = generation,
            requireKeyguard = true,
            transitionToken = transitionToken
        )

    private fun startHomeOnCoverDisplay(
        generation: Long,
        requireKeyguard: Boolean,
        transitionToken: Long
    ): Boolean {
        val displayId = com.flexunlock.dexlsp.CoverDisplayResolver.currentId()
            ?: return false
        if (!requireKeyguard && !com.flexunlock.dexlsp.CoverDisplayResolver.isBuiltInTarget()) {
            val lock = externalHomeRouteLocks.computeIfAbsent(displayId) { Any() }
            return synchronized(lock) {
                startHomeOnCoverDisplayLocked(generation, requireKeyguard, transitionToken)
            }
        }
        return startHomeOnCoverDisplayLocked(generation, requireKeyguard, transitionToken)
    }

    private fun startHomeOnCoverDisplayLocked(
        generation: Long,
        requireKeyguard: Boolean,
        transitionToken: Long
    ): Boolean {
        if (
            requireKeyguard &&
            (!RuntimeFacts.isClosed() || !com.flexunlock.dexlsp.CoverDisplayResolver.isBuiltInTarget())
        ) return false
        if (!requireKeyguard && !RuntimeFacts.isTargetSessionEligible()) return false
        val coverDisplayId = com.flexunlock.dexlsp.CoverDisplayResolver.currentId()
            ?: return false
        if (!isHomeRouteCurrent(requireKeyguard, transitionToken)) {
            log(
                "display-1 stale Home request suppressed generation=$generation " +
                    "keyguard=$requireKeyguard token=$transitionToken " +
                    "currentToken=${currentKeyguardTransitionToken()}"
            )
            return false
        }
        if (
            !requireKeyguard &&
            !com.flexunlock.dexlsp.CoverDisplayResolver.isBuiltInTarget() &&
            reuseExistingExternalHome(coverDisplayId, generation)
        ) return true
        val localService = activityTaskManagerLocalService
            ?: resolveActivityTaskManagerLocalService()?.also {
                activityTaskManagerLocalService = it
            }
            ?: return false

        return runCatching {
            val activityTaskManager = XposedHelpers.getSurroundingThis(localService)
            val globalLock = XposedHelpers.getObjectField(activityTaskManager, "mGlobalLock")
            val rootWindowContainer = XposedHelpers.getObjectField(
                activityTaskManager,
                "mRootWindowContainer"
            )
            val currentUser = XposedHelpers.getIntField(rootWindowContainer, "mCurrentUser")
            val windowManagerService = XposedHelpers.findClass(
                WINDOW_MANAGER_SERVICE,
                classLoader
            )

            XposedHelpers.callStaticMethod(windowManagerService, "boostPriorityForLockedSection")
            try {
                synchronized(globalLock) {
                    if (!isHomeRouteCurrent(requireKeyguard, transitionToken)) {
                        return@synchronized false
                    }
                    XposedHelpers.callMethod(
                        rootWindowContainer,
                        "startHomeOnDisplay",
                        "FlexUnlock CLOSED generation=$generation keyguard=$requireKeyguard",
                        currentUser,
                        coverDisplayId,
                        false,
                        false
                    ) as Boolean
                }
            } finally {
                XposedHelpers.callStaticMethod(
                    windowManagerService,
                    "resetPriorityAfterLockedSection"
                )
            }
        }.onSuccess { started ->
            log(
                "display-1 native Home request generation=$generation " +
                    "keyguard=$requireKeyguard token=$transitionToken started=$started"
            )
        }.onFailure { error ->
            log(
                "display-1 native Home request failed generation=$generation " +
                    "keyguard=$requireKeyguard token=$transitionToken: ${error.message}"
            )
        }.getOrDefault(false)
    }

    private fun isHomeRouteCurrent(requireKeyguard: Boolean, transitionToken: Long): Boolean {
        if (transitionToken != currentKeyguardTransitionToken()) return false
        if (requireKeyguard && !com.flexunlock.dexlsp.CoverDisplayResolver.isBuiltInTarget()) return false
        if (!requireKeyguard && RuntimeFacts.isTargetSecurityRestricted()) return false
        val showing = isCoverKeyguardShowing() &&
            com.flexunlock.dexlsp.CoverDisplayResolver.isBuiltInTarget()
        return transitionToken == currentKeyguardTransitionToken() && showing == requireKeyguard
    }

    fun dismissCoverKeyguardHome(transitionToken: Long): Boolean {
        val keyguardActivity = coverKeyguardActivityRecord ?: return false
        if (!isKeyguardDismissRouteCurrent(transitionToken)) return false
        val widgetAuthentication = pendingCoverWidgetAuthentication
        if (isCoverWidgetAuthenticationTransition(
                widgetAuthentication?.transitionToken,
                transitionToken,
                widgetAuthentication?.deadlineUptimeMillis ?: Long.MIN_VALUE,
                SystemClock.uptimeMillis()
            )
        ) {
            pendingCoverWidgetAuthentication = null
            preserveUnlockedWidgetPage = true
            log("display-1 Keyguard dismissal retained authenticated widget page token=$transitionToken")
            return false
        }
        val localService = activityTaskManagerLocalService
            ?: resolveActivityTaskManagerLocalService()?.also {
                activityTaskManagerLocalService = it
            }
            ?: return false

        return runCatching {
            val activityTaskManager = XposedHelpers.getSurroundingThis(localService)
            val globalLock = XposedHelpers.getObjectField(activityTaskManager, "mGlobalLock")
            val rootWindowContainer = XposedHelpers.getObjectField(
                activityTaskManager,
                "mRootWindowContainer"
            )
            val windowManagerService = XposedHelpers.findClass(
                WINDOW_MANAGER_SERVICE,
                classLoader
            )
            var restoredTaskId: Int? = null
            var restoredComponent: ComponentName? = null
            var finishResult: Int? = null
            var noAnimationTargets = 0

            XposedHelpers.callStaticMethod(windowManagerService, "boostPriorityForLockedSection")
            try {
                synchronized(globalLock) {
                    if (
                        isKeyguardDismissRouteCurrent(transitionToken) &&
                        ownerOfActivityRecord(keyguardActivity) ==
                        CoverHomeOwner.SYSTEM_UI_SUB_HOME
                    ) {
                        val trustedLaunch = trustedKeyguardAppLaunch
                        val launchOwnsTransition = isTrustedKeyguardAppLaunchTransition(
                            trustedLaunch?.transitionToken,
                            transitionToken,
                            trustedLaunch?.deadlineUptimeMillis ?: Long.MIN_VALUE,
                            SystemClock.uptimeMillis()
                        )
                        if (!launchOwnsTransition) trustedKeyguardAppLaunch = null
                        val restoreTarget = if (launchOwnsTransition) {
                            log(
                                "display-1 foreground restore skipped for trusted Keyguard " +
                                    "App token=$transitionToken component=${trustedLaunch?.component}"
                            )
                            null
                        } else {
                            resolveRestorableCoverActivity()
                                ?: if (restorableCoverActivityRecord == null) {
                                    resolveSecondaryLauncherActivity()
                                } else {
                                    null
                                }
                        }
                        if (restoreTarget != null) {
                            noAnimationTargets += markRestorationNoAnimationActivity(
                                activityTaskManager,
                                restoreTarget
                            )
                            val task = XposedHelpers.getObjectField(restoreTarget, "task")
                            val targetTaskId = taskIdOf(restoreTarget)
                                ?: error("restorable ActivityRecord task id missing")
                            val rootTask = XposedHelpers.callMethod(restoreTarget, "getRootTask")
                                ?: error("restorable ActivityRecord root task missing")
                            val targetRootTaskId = XposedHelpers.getIntField(rootTask, "mTaskId")
                            val keyguardRootTask = XposedHelpers.callMethod(
                                keyguardActivity,
                                "getRootTask"
                            ) ?: error("Keyguard ActivityRecord root task missing")
                            val keyguardRootTaskId = XposedHelpers.getIntField(
                                keyguardRootTask,
                                "mTaskId"
                            )
                            val restorationTaskIds = setOf(targetRootTaskId)
                            pendingRestorationAnimationSuppression =
                                PendingRestorationAnimationSuppression(
                                    transitionToken = transitionToken,
                                    remainingTaskIds = restorationTaskIds,
                                    keyguardRootTaskId = keyguardRootTaskId,
                                    deadlineUptimeMillis = SystemClock.uptimeMillis() +
                                        RESTORATION_ANIMATION_SUPPRESSION_TIMEOUT_MS
                                )
                            log(
                                "display-1 restoration transition suppression armed " +
                                    "token=$transitionToken appTaskIds=$restorationTaskIds " +
                                    "keyguardRootTaskId=$keyguardRootTaskId"
                            )
                            val moveMethod = rootTask.javaClass.declaredMethods
                                .firstOrNull { method ->
                                    method.name == "moveTaskToFront" &&
                                        method.parameterTypes.size == 6 &&
                                        method.parameterTypes[1] ==
                                        Boolean::class.javaPrimitiveType &&
                                        method.parameterTypes[5] == String::class.java
                                }
                                ?: error("Task.moveTaskToFront signature missing")
                            moveMethod.isAccessible = true
                            moveMethod.invoke(
                                rootTask,
                                task,
                                true,
                                null,
                                null,
                                true,
                                "FlexUnlock restore cover foreground"
                            )
                            restoredTaskId = targetTaskId
                            restoredComponent = componentOf(restoreTarget)
                        }

                        val finishMethod = keyguardActivity.javaClass.declaredMethods
                            .firstOrNull { method ->
                                method.name == "finishIfPossible" &&
                                    method.parameterTypes.size == 5 &&
                                    method.parameterTypes[0] == Int::class.javaPrimitiveType &&
                                    method.parameterTypes[3] == String::class.java &&
                                    method.parameterTypes[4] == Boolean::class.javaPrimitiveType
                            }
                            ?: error("ActivityRecord.finishIfPossible signature missing")
                        finishMethod.isAccessible = true
                        finishResult = (finishMethod.invoke(
                            keyguardActivity,
                            0,
                            null,
                            null,
                            "FlexUnlock cover Keyguard dismissed",
                            false
                        ) as? Number)?.toInt()

                        if (restoredTaskId != null || (finishResult ?: 0) > 0) {
                            XposedHelpers.callMethod(
                                rootWindowContainer,
                                "resumeFocusedTasksTopActivities"
                            )
                        }
                    }
                }
            } finally {
                XposedHelpers.callStaticMethod(
                    windowManagerService,
                    "resetPriorityAfterLockedSection"
                )
            }

            val dismissed = (finishResult ?: 0) > 0
            if (dismissed && coverKeyguardActivityRecord === keyguardActivity) {
                coverKeyguardActivityRecord = null
            }
            log(
                "display-1 Keyguard foreground restoration token=$transitionToken " +
                    "restoredTaskId=$restoredTaskId component=$restoredComponent " +
                    "finishResult=$finishResult noAnimationTargets=$noAnimationTargets " +
                    "dismissed=$dismissed"
            )
            dismissed
        }.onFailure { error ->
            log(
                "display-1 Keyguard foreground restoration failed token=$transitionToken: " +
                    error.message
            )
        }.getOrDefault(false)
    }

    private fun reuseExistingExternalHome(displayId: Int, generation: Long): Boolean {
        val context = systemContext ?: return false
        val activityManager = context.getSystemService(ActivityManager::class.java) ?: return false
        val matchingTasks = activityManager.getRunningTasks(MAX_RUNNING_TASKS_TO_INSPECT)
            .filter { task ->
                runningTaskDisplayId(task) == displayId &&
                    ownerOfComponent(task.baseActivity ?: task.topActivity) ==
                    CoverHomeOwner.SECONDARY_LAUNCHER
            }
        if (matchingTasks.isEmpty()) return false

        val duplicateTaskIds = duplicateSecondaryHomeTaskIds(matchingTasks.map { it.taskId })
        val activityTaskManager = if (duplicateTaskIds.isEmpty()) null else runCatching {
            val managerClass = Class.forName("android.app.ActivityTaskManager")
            managerClass.getMethod("getService").invoke(null)
        }.getOrNull()
        duplicateTaskIds.forEach { taskId ->
            runCatching {
                XposedHelpers.callMethod(activityTaskManager, "removeTask", taskId) as Boolean
            }.onSuccess { removed ->
                log("external duplicate Home removed taskId=$taskId removed=$removed")
            }.onFailure { error ->
                log("external duplicate Home remove failed taskId=$taskId: ${error.message}")
            }
        }
        log(
            "external Home reused display=$displayId generation=$generation " +
                "taskId=${matchingTasks.maxOf { it.taskId }} duplicates=${duplicateTaskIds.size}"
        )
        return true
    }

    private fun pruneDuplicateExternalTasks(displayId: Int) {
        val context = systemContext ?: return
        val activityManager = context.getSystemService(ActivityManager::class.java) ?: return
        val duplicateTaskIds = duplicateExternalTaskIds(
            activityManager.getRunningTasks(MAX_RUNNING_TASKS_TO_INSPECT)
                .asSequence()
                .filter { task ->
                    runningTaskDisplayId(task) == displayId &&
                        runningTaskActivityType(task) == ACTIVITY_TYPE_STANDARD
                }
                .mapNotNull { task ->
                    val component = task.baseActivity ?: task.topActivity ?: return@mapNotNull null
                    ExternalTaskSnapshot(
                        task.taskId,
                        component.flattenToString(),
                        longField(task, "lastActiveTime") ?: 0L
                    )
                }
                .toList()
        )
        if (duplicateTaskIds.isEmpty()) return
        val activityTaskManager = runCatching {
            val managerClass = Class.forName("android.app.ActivityTaskManager")
            managerClass.getMethod("getService").invoke(null)
        }.getOrNull() ?: return
        duplicateTaskIds.forEach { taskId ->
            runCatching {
                XposedHelpers.callMethod(activityTaskManager, "removeTask", taskId) as Boolean
            }.onSuccess { removed ->
                log("external duplicate app removed display=$displayId taskId=$taskId removed=$removed")
            }.onFailure { error ->
                log("external duplicate app remove failed display=$displayId " +
                    "taskId=$taskId: ${error.message}")
            }
        }
    }

    fun onExternalDisplayAdded(displayId: Int) {
        val context = systemContext ?: return
        val display = context.getSystemService(android.hardware.display.DisplayManager::class.java)
            ?.getDisplay(displayId) ?: return
        val uniqueId = runCatching {
            XposedHelpers.callMethod(display, "getUniqueId") as? String
        }.getOrNull() ?: return
        if (!uniqueId.startsWith("virtual:com.android.shell,2000,scrcpy,", ignoreCase = true)) {
            return
        }
        val lock = externalHomeRouteLocks.computeIfAbsent(displayId) { Any() }
        synchronized(lock) {
            pruneDuplicateExternalTasks(displayId)
            reuseExistingExternalHome(displayId, SystemClock.uptimeMillis())
        }
    }

    fun onExternalDisplayRemoved(displayId: Int) {
        val context = systemContext ?: return
        val activityManager = context.getSystemService(ActivityManager::class.java) ?: return
        val staleTasks = activityManager.getRunningTasks(MAX_RUNNING_TASKS_TO_INSPECT)
            .filter { task ->
                runningTaskDisplayId(task) == displayId
            }
        if (staleTasks.isNotEmpty()) {
            val activityTaskManager = runCatching {
                val managerClass = Class.forName("android.app.ActivityTaskManager")
                managerClass.getMethod("getService").invoke(null)
            }.getOrNull()
            staleTasks.forEach { task ->
                runCatching {
                    XposedHelpers.callMethod(activityTaskManager, "removeTask", task.taskId)
                }.onSuccess {
                    coverSessionTaskIds.remove(task.taskId)
                    log("external display removed task cleared display=$displayId taskId=${task.taskId}")
                }.onFailure { error ->
                    log("external display task cleanup failed display=$displayId " +
                        "taskId=${task.taskId}: ${error.message}")
                }
            }
        }
        staleTasks.forEach { task -> coverSessionTaskIds.remove(task.taskId) }
        secondaryLauncherActivityRecord = null
        lastPublishedOwner = CoverHomeOwner.NONE
        log("external display route cache cleared display=$displayId tasks=${staleTasks.size}")
    }

    private fun markRestorationNoAnimationActivity(
        activityTaskManager: Any,
        restoredActivity: Any
    ): Int {
        return runCatching {
            val taskSupervisor = XposedHelpers.getObjectField(
                activityTaskManager,
                "mTaskSupervisor"
            )
            @Suppress("UNCHECKED_CAST")
            val noAnimationActivities = XposedHelpers.getObjectField(
                taskSupervisor,
                "mNoAnimActivities"
            ) as MutableList<Any>
            if (noAnimationActivities.contains(restoredActivity)) {
                0
            } else {
                noAnimationActivities += restoredActivity
                log("display-1 restoration App activity marked no-animation")
                1
            }
        }.onFailure { error ->
            log("display-1 restoration App no-animation unavailable: ${error.message}")
        }.getOrDefault(0)
    }

    private fun resolveRestorableCoverActivity(): Any? {
        val savedActivity = restorableCoverActivityRecord ?: return null
        val savedTaskId = restorableCoverTaskId ?: return null
        return runCatching {
            // 锁屏快捷启动(如相机图标)优先:若 display 1 顶部 task 已是用户
            // 主动启动的新 App(其 taskId 不同于旧记录),说明 Keyguard dismiss
            // 源于快捷入口,不应再把旧 App task 恢复到前台。比较 taskId 而非
            // top-resumed Activity,可避免相机尚未 resume 的时序窗口误判。
            val topTaskId = currentTopCoverTaskId()
            if (topTaskId != null && topTaskId != savedTaskId) {
                log(
                    "display-1 Keyguard dismiss via new launch; skipping old restore " +
                        "topTaskId=$topTaskId savedTaskId=$savedTaskId"
                )
                return@runCatching null
            }
            val task = XposedHelpers.getObjectField(savedActivity, "task") ?: return@runCatching null
            if (
                taskIdOf(savedActivity) != savedTaskId ||
                savedTaskId !in coverSessionTaskIds ||
                XposedHelpers.callMethod(task, "isAttached") != true
            ) {
                return@runCatching null
            }
            val target = XposedHelpers.callMethod(task, "topRunningActivityLocked")
                ?: return@runCatching null
            val component = componentOf(target) ?: return@runCatching null
            val finishing = runCatching {
                XposedHelpers.getBooleanField(target, "finishing")
            }.getOrDefault(true)
            val focusable = runCatching {
                XposedHelpers.callMethod(target, "isFocusable") as? Boolean
            }.getOrNull() == true
            val attached = runCatching {
                XposedHelpers.callMethod(target, "isAttached") as? Boolean
            }.getOrNull() == true
            val displayId = runCatching {
                (XposedHelpers.callMethod(target, "getDisplayId") as? Number)?.toInt()
            }.getOrNull()
            if (
                finishing ||
                !focusable ||
                !attached ||
                !com.flexunlock.dexlsp.CoverDisplayResolver.matches(displayId) ||
                component.isSecondaryLauncher() ||
                component.packageName == SYSTEM_UI_PACKAGE
            ) {
                null
            } else {
                target
            }
        }.onFailure { error ->
            log("display-1 foreground restore target invalid: ${error.message}")
        }.getOrNull()
    }

    /**
     * display 1 当前顶层 task id;仅当该 task 属于普通 App(非 Launcher、
     * 非 SystemUI、非 Keyguard 壳)时返回,否则返回 null。
     */
    private fun currentTopCoverTaskId(): Int? = runCatching {
        val coverDisplayId = com.flexunlock.dexlsp.CoverDisplayResolver.currentId()
            ?: return null
        val localService = activityTaskManagerLocalService
            ?: resolveActivityTaskManagerLocalService()
            ?: return null
        val activityTaskManager = XposedHelpers.getSurroundingThis(localService)
        val rootWindowContainer = XposedHelpers.getObjectField(
            activityTaskManager,
            "mRootWindowContainer"
        )
        val displayContent = XposedHelpers.callMethod(
            rootWindowContainer,
            "getDisplayContent",
            coverDisplayId
        ) ?: return null
        val topActivity = XposedHelpers.callMethod(displayContent, "getTopResumedActivity")
            ?: return null
        val component = componentOf(topActivity) ?: return null
        if (
            component.isSecondaryLauncher() ||
            component.packageName == SYSTEM_UI_PACKAGE
        ) {
            return null
        }
        taskIdOf(topActivity)
    }.getOrNull()

    private fun resolveSecondaryLauncherActivity(): Any? {
        val activity = secondaryLauncherActivityRecord ?: return null
        return runCatching {
            val component = componentOf(activity) ?: return@runCatching null
            val displayId = (XposedHelpers.callMethod(activity, "getDisplayId") as? Number)
                ?.toInt()
            val finishing = XposedHelpers.getBooleanField(activity, "finishing")
            val attached = XposedHelpers.callMethod(activity, "isAttached") == true
            activity.takeIf {
                component.isSecondaryLauncher() &&
                    com.flexunlock.dexlsp.CoverDisplayResolver.matches(displayId) &&
                    !finishing &&
                    attached
            }
        }.getOrNull()
    }

    private fun isKeyguardDismissRouteCurrent(transitionToken: Long): Boolean =
        RuntimeFacts.isClosed() &&
            com.flexunlock.dexlsp.CoverDisplayResolver.isBuiltInTarget() &&
            transitionToken == currentKeyguardTransitionToken() &&
            !CoverLockTransitionPolicy.isAccessRestricted() &&
            !isCoverKeyguardShowing()

    fun stopCoverHome(generation: Long): Boolean {
        val coverDisplayId = com.flexunlock.dexlsp.CoverDisplayResolver.currentId()
            ?: return false
        val context = systemContext ?: return false
        val activityManager = context.getSystemService(ActivityManager::class.java) ?: return false
        val matchingTasks = activityManager.getRunningTasks(MAX_RUNNING_TASKS_TO_INSPECT)
            .filter { task ->
                runningTaskDisplayId(task) == coverDisplayId &&
                    task.taskId in coverSessionTaskIds
            }
        if (matchingTasks.isEmpty()) {
            log(
                "display-1 non-home cover session tasks already absent " +
                    "generation=$generation; native Secondary Home retained"
            )
            return true
        }

        val activityTaskManager = runCatching {
            val managerClass = Class.forName("android.app.ActivityTaskManager")
            managerClass.getMethod("getService").invoke(null)
        }.getOrNull() ?: return false

        return matchingTasks.all { task ->
            runCatching {
                XposedHelpers.callMethod(activityTaskManager, "removeTask", task.taskId) as Boolean
            }.onSuccess { removed ->
                if (removed) coverSessionTaskIds.remove(task.taskId)
                log(
                    "display-1 cover session task remove generation=$generation " +
                        "taskId=${task.taskId} removed=$removed"
                )
            }.onFailure { error ->
                log(
                    "display-1 cover session task remove failed generation=$generation " +
                        "taskId=${task.taskId}: ${error.message}"
                )
            }.getOrDefault(false)
        }
    }

    fun removeTargetHomeForModeChange(): Boolean {
        val targetDisplayId = com.flexunlock.dexlsp.CoverDisplayResolver.currentId()
            ?: return false
        val context = systemContext ?: return false
        val activityManager = context.getSystemService(ActivityManager::class.java) ?: return false
        val matchingTasks = activityManager.getRunningTasks(MAX_RUNNING_TASKS_TO_INSPECT)
            .filter { task ->
                restartableTargetHome(
                    targetDisplayId,
                    runningTaskDisplayId(task),
                    runningTaskActivityType(task),
                    ownerOfComponent(task.baseActivity ?: task.topActivity)
                )
            }
        if (matchingTasks.isEmpty()) return true
        val activityTaskManager = runCatching {
            val managerClass = Class.forName("android.app.ActivityTaskManager")
            managerClass.getMethod("getService").invoke(null)
        }.getOrNull() ?: return false
        return matchingTasks.all { task ->
            runCatching {
                XposedHelpers.callMethod(activityTaskManager, "removeTask", task.taskId) as Boolean
            }.onSuccess { removed ->
                log("target Home remove for mode change taskId=${task.taskId} removed=$removed")
            }.onFailure { error ->
                log("target Home remove for mode change failed taskId=${task.taskId}: ${error.message}")
            }.getOrDefault(false)
        }
    }

    private fun runningTaskDisplayId(task: ActivityManager.RunningTaskInfo): Int? {
        return runCatching {
            XposedHelpers.getIntField(task, "displayId")
        }.getOrNull()
    }

    private fun longField(instance: Any, name: String): Long? = runCatching {
        XposedHelpers.getLongField(instance, name)
    }.getOrNull()

    private fun runningTaskActivityType(task: ActivityManager.RunningTaskInfo): Int? = runCatching {
        val configuration = XposedHelpers.getObjectField(task, "configuration")
        val windowConfiguration = XposedHelpers.getObjectField(configuration, "windowConfiguration")
        (XposedHelpers.callMethod(windowConfiguration, "getActivityType") as Number).toInt()
    }.getOrNull()

    private fun ComponentName.isSecondaryLauncher(): Boolean {
        return packageName == SAMSUNG_LAUNCHER_PACKAGE &&
            className == SECONDARY_LAUNCHER_ACTIVITY
    }

    private fun displayIdOf(taskDisplayArea: Any): Int? {
        return runCatching {
            val displayContent = XposedHelpers.getObjectField(taskDisplayArea, "mDisplayContent")
            XposedHelpers.getIntField(displayContent, "mDisplayId")
        }.getOrNull()
    }

    private fun resolveSystemUiSubHome() = resolveActivity(
        ComponentName(SYSTEM_UI_PACKAGE, SUB_HOME_ACTIVITY),
        "SystemUI SubHome"
    )

    private fun resolveSecondaryLauncher() = resolveActivity(
        ComponentName(SAMSUNG_LAUNCHER_PACKAGE, SECONDARY_LAUNCHER_ACTIVITY),
        "Samsung SecondaryLauncher"
    )

    private fun resolveActivity(component: ComponentName, label: String) = runCatching {
        val packageManager = systemContext?.packageManager ?: return@runCatching null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getActivityInfo(
                component,
                PackageManager.ComponentInfoFlags.of(PackageManager.GET_META_DATA.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.getActivityInfo(component, PackageManager.GET_META_DATA)
        }
    }.onFailure { error ->
        log("$label lookup failed: ${error.message}")
    }.getOrNull()

    private fun resolveActivityTaskManagerLocalService(): Any? {
        val loader = classLoader ?: return null
        return runCatching {
            val localServices = XposedHelpers.findClass(LOCAL_SERVICES, loader)
            val serviceClass = XposedHelpers.findClass(ACTIVITY_TASK_MANAGER_INTERNAL, loader)
            XposedHelpers.callStaticMethod(localServices, "getService", serviceClass)
        }.onFailure { error ->
            log("ActivityTaskManager local service unavailable: ${error.message}")
        }.getOrNull()
    }

    private fun log(message: String) {
        Log.i(TAG, message)
        XposedBridge.log("$TAG: $message")
    }

    private const val TRUSTED_KEYGUARD_APP_LAUNCH_TIMEOUT_MS = 3_000L
}

private const val ACTIVITY_TYPE_HOME = 2
private const val ACTIVITY_TYPE_STANDARD = 1
