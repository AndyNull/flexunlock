package com.flexunlock.dexlsp.system.runtime

import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

object CoverRecentsPolicy {
    private const val TAG = "FlexUnlock-RecentsPolicy"
    private const val RECENTS_GESTURE_TYPE = 4
    private const val ACTIVITY_START_CONTROLLER =
        "com.android.server.wm.ActivityStartController"
    private const val SAFE_ACTIVITY_OPTIONS = "com.android.server.wm.SafeActivityOptions"
    private const val SAMSUNG_LAUNCHER_PACKAGE = "com.sec.android.app.launcher"
    private const val SECONDARY_LAUNCHER_ACTIVITY =
        "com.honeyspace.dexservice.SecondaryLauncher"
    private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
    private const val SUB_HOME_ACTIVITY = "com.android.systemui.subscreen.SubHomeActivity"
    private const val ROUTE_DIAGNOSTIC_INTERVAL_MS = 30_000L
    private const val KEYCODE_APP_SWITCH = 187

    @Volatile
    private var systemContext: Context? = null

    private val lastDiagnosticAt = ConcurrentHashMap<String, Long>()
    private val lastExternalAppSwitchAt = AtomicLong(0L)

    private val policyCalls = ThreadLocal.withInitial { ArrayDeque<Boolean>() }

    private fun currentPolicyCalls(): ArrayDeque<Boolean> =
        requireNotNull(policyCalls.get())

    fun bind(context: Context) {
        systemContext = context
        log("display-1 transient Recents Home route context bound")
    }

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        installExternalDisplayKeyRoute(lpparam)
        runCatching {
            val policyClass = XposedHelpers.findClass(
                "com.android.server.policy.PhoneWindowManager",
                lpparam.classLoader
            )
            val gestureMethods = policyClass.declaredMethods.filter { method ->
                method.name == "handleKeyGestureEvent" &&
                    method.parameterTypes.firstOrNull()?.name ==
                    "android.hardware.input.KeyGestureEvent"
            }
            gestureMethods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (suppressRoutedExternalGesture(param)) return
                        runCatching {
                            currentPolicyCalls().addLast(
                                isClosedCoverRecents(param.args.firstOrNull())
                            )
                        }.onFailure { error ->
                            log("Recents policy mark failed: ${error.message}")
                        }
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        runCatching {
                            val calls = currentPolicyCalls()
                            if (calls.isNotEmpty()) calls.removeLast()
                            if (calls.isEmpty()) policyCalls.remove()
                        }.onFailure { error ->
                            log("Recents policy unmark failed: ${error.message}")
                            policyCalls.remove()
                        }
                    }
                })
            }

            val extensionClass = XposedHelpers.findClass(
                "com.android.server.policy.PhoneWindowManagerExt",
                lpparam.classLoader
            )
            val foldedMethods = extensionClass.declaredMethods.filter { method ->
                method.name == "isFolded" && method.parameterCount == 0
            }
            foldedMethods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!RuntimeFacts.isTargetSessionEligible()) return
                        if (currentPolicyCalls().peekLast() != true) return
                        param.result = false
                    }
                })
            }
            log(
                "CLOSED display-1 Recents policy installed " +
                    "gestureMethods=${gestureMethods.size} foldedMethods=${foldedMethods.size}"
            )
        }.onFailure { error ->
            log("CLOSED display-1 Recents policy unavailable: ${error.message}")
        }
        installTransientHomeRoute(lpparam)
    }

    private fun installExternalDisplayKeyRoute(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            val policyClass = XposedHelpers.findClass(
                "com.android.server.policy.PhoneWindowManager",
                lpparam.classLoader
            )
            val methods = policyClass.declaredMethods.filter { method ->
                method.name == "interceptKeyBeforeQueueing" &&
                    method.parameterTypes.size == 2 &&
                    method.parameterTypes[0] == KeyEvent::class.java &&
                    method.parameterTypes[1] == Int::class.javaPrimitiveType
            }
            methods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        routeExternalAppSwitch(param)
                    }
                })
            }
            log(
                "external display APP_SWITCH queue route installed " +
                    "methods=${methods.size} signatures=${methods.joinToString()}"
            )
            if (methods.isNotEmpty()) return@runCatching

            val extensionClass = XposedHelpers.findClass(
                "com.android.server.policy.PhoneWindowManagerExt",
                lpparam.classLoader
            )
            val fallbackMethods = extensionClass.declaredMethods.filter { it.name == "interceptKeyTq" }
            fallbackMethods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        routeExternalAppSwitch(param)
                    }
                })
            }
            log("external display APP_SWITCH fallback route installed methods=${fallbackMethods.size}")
        }.onFailure { error ->
            log("external display APP_SWITCH route unavailable: ${error.message}")
        }
    }

    private fun routeExternalAppSwitch(param: XC_MethodHook.MethodHookParam) {
        val event = param.args.filterIsInstance<KeyEvent>().firstOrNull() ?: run {
            log("external display APP_SWITCH ignored: KeyEvent argument missing")
            return
        }
        if (event.keyCode != KEYCODE_APP_SWITCH) return
        val displayId = runCatching {
            (XposedHelpers.callMethod(event, "getDisplayId") as Number).toInt()
        }.getOrNull() ?: return
        if (!isExternalDisplay(displayId)) return
        log(
            "external display APP_SWITCH intercepted display=$displayId " +
                "action=${event.action} contextBound=${systemContext != null}"
        )
        if (event.action == KeyEvent.ACTION_DOWN) {
            lastExternalAppSwitchAt.set(SystemClock.uptimeMillis())
            launchExternalRecents(displayId)
        }
        param.result = 0
    }

    private fun suppressRoutedExternalGesture(param: XC_MethodHook.MethodHookParam): Boolean {
        val event = param.args.firstOrNull() ?: return false
        val gestureType = event.intProperty("getKeyGestureType") ?: return false
        if (gestureType != RECENTS_GESTURE_TYPE && gestureType != 1003) return false
        val age = SystemClock.uptimeMillis() - lastExternalAppSwitchAt.get()
        if (age !in 0..1_000L) return false
        val displayId = event.intProperty("getDisplayId")
        log(
            "external display APP_SWITCH follow-up gesture suppressed " +
                "type=$gestureType displayId=$displayId ageMs=$age"
        )
        param.result = null
        lastExternalAppSwitchAt.set(0L)
        return true
    }

    private fun isExternalDisplay(displayId: Int): Boolean {
        val context = systemContext ?: return false
        val display = context.getSystemService(android.hardware.display.DisplayManager::class.java)
            ?.getDisplay(displayId) ?: return false
        val type = runCatching {
            (XposedHelpers.callMethod(display, "getType") as Number).toInt()
        }.getOrNull() ?: return false
        if (type == 2 || type == 3 || type == 6) return true
        val uniqueId = runCatching {
            XposedHelpers.callMethod(display, "getUniqueId") as? String
        }.getOrNull()
        return type == 5 && uniqueId?.startsWith(
            "virtual:com.android.shell,2000,scrcpy,",
            ignoreCase = true
        ) == true
    }

    private fun launchExternalRecents(displayId: Int) {
        val context = systemContext ?: return
        runCatching {
            val intent = Intent().apply {
                component = ComponentName(
                    SAMSUNG_LAUNCHER_PACKAGE,
                    "com.android.quickstep.RecentsActivity"
                )
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            }
            val options = ActivityOptions.makeBasic().apply {
                setLaunchDisplayId(displayId)
            }
            context.startActivity(intent, options.toBundle())
            log("external display APP_SWITCH launched RecentsActivity display=$displayId")
        }.onFailure { error ->
            log("external display APP_SWITCH launch failed display=$displayId: ${error.message}")
        }
    }

    private fun installTransientHomeRoute(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            val controllerClass = XposedHelpers.findClass(
                ACTIVITY_START_CONTROLLER,
                lpparam.classLoader
            )
            val methods = controllerClass.declaredMethods.filter { method ->
                method.name == "startActivityInPackage" &&
                    method.parameterTypes.any { it == Intent::class.java } &&
                    method.parameterTypes.any { it.name == SAFE_ACTIVITY_OPTIONS }
            }
            var hookedMethods = 0
            methods.forEach { method ->
                val intentIndex = method.parameterTypes.indexOfFirst { it == Intent::class.java }
                val optionsIndex = method.parameterTypes.indexOfFirst {
                    it.name == SAFE_ACTIVITY_OPTIONS
                }
                val callingUidIndex = intentIndex - 5
                val callingPackageIndex = intentIndex - 2
                if (
                    intentIndex < 0 ||
                    optionsIndex < 0 ||
                    callingUidIndex !in method.parameterTypes.indices ||
                    callingPackageIndex !in method.parameterTypes.indices ||
                    method.parameterTypes[callingUidIndex] != Int::class.javaPrimitiveType ||
                    method.parameterTypes[callingPackageIndex] != String::class.java
                ) {
                    log(
                        "display-1 transient Recents Home route skipped incompatible signature " +
                            "method=$method"
                    )
                    return@forEach
                }
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        routeTransientRecentsHome(
                            param,
                            intentIndex,
                            optionsIndex,
                            callingUidIndex,
                            callingPackageIndex
                        )
                    }
                })
                hookedMethods++
            }
            log(
                "display-1 transient Recents Home route installed " +
                    "candidates=${methods.size} hooked=$hookedMethods"
            )
        }.onFailure { error ->
            log("display-1 transient Recents Home route unavailable: ${error.message}")
        }
    }

    private fun routeTransientRecentsHome(
        param: XC_MethodHook.MethodHookParam,
        intentIndex: Int,
        optionsIndex: Int,
        callingUidIndex: Int,
        callingPackageIndex: Int
    ) {
        val intent = param.args.getOrNull(intentIndex) as? Intent ?: return
        val component = intent.component ?: return
        if (
            component.packageName != SYSTEM_UI_PACKAGE ||
            component.className != SUB_HOME_ACTIVITY
        ) return

        val context = systemContext ?: run {
            diagnostic(
                "context-unbound",
                "display-1 transient SubHome route rejected: system context unbound"
            )
            return
        }
        if (CoverSessionCoordinator.current()?.isEnabled() == false) {
            diagnostic(
                "session-disabled",
                "display-1 transient SubHome route rejected: cover session disabled"
            )
            return
        }
        val keyguardShowing = NativeSecondaryHomeRouter.isCoverKeyguardShowing()
        val accessRestricted = CoverLockTransitionPolicy.isAccessRestricted()
        if (keyguardShowing || accessRestricted) {
            diagnostic(
                "keyguard-restricted",
                "display-1 transient SubHome retained for Keyguard " +
                    "showing=$keyguardShowing accessRestricted=$accessRestricted " +
                    "lockPending=${CoverLockTransitionPolicy.isLockPending()}"
            )
            return
        }

        val callingPackage = param.args.getOrNull(callingPackageIndex) as? String
        val callingUid = (param.args.getOrNull(callingUidIndex) as? Number)?.toInt()
        if (callingPackage != SAMSUNG_LAUNCHER_PACKAGE || callingUid == null) {
            diagnostic(
                "caller-mismatch",
                "display-1 transient SubHome route rejected caller=" +
                    "$callingPackage uid=$callingUid"
            )
            return
        }
        if (!uidOwnsPackage(context, callingUid, callingPackage)) {
            diagnostic(
                "caller-uid-mismatch",
                "display-1 transient SubHome route rejected: caller UID does not own " +
                    "$callingPackage uid=$callingUid"
            )
            return
        }

        val safeOptions = param.args.getOrNull(optionsIndex) ?: run {
            diagnostic(
                "options-missing",
                "display-1 transient SubHome route rejected: options missing"
            )
            return
        }
        val options = originalActivityOptions(safeOptions) ?: run {
            diagnostic(
                "options-unavailable",
                "display-1 transient SubHome route rejected: options unavailable"
            )
            return
        }
        val launchDisplayId = runCatching {
            (XposedHelpers.callMethod(options, "getLaunchDisplayId") as? Number)?.toInt()
        }.getOrNull() ?: run {
            diagnostic(
                "display-unavailable",
                "display-1 transient SubHome route rejected: launch display unavailable"
            )
            return
        }
        val resolvedDisplayId = com.flexunlock.dexlsp.CoverDisplayResolver.currentId()
        if (resolvedDisplayId == null) {
            diagnostic(
                "resolver-unready",
                "display-1 transient SubHome route rejected: display resolver unready " +
                    "state=${com.flexunlock.dexlsp.CoverDisplayResolver.resolution()} " +
                    "launchDisplayId=$launchDisplayId"
            )
            return
        }
        if (resolvedDisplayId != launchDisplayId) {
            diagnostic(
                "display-mismatch",
                "display-1 transient SubHome route rejected: display mismatch " +
                    "resolved=$resolvedDisplayId launch=$launchDisplayId"
            )
            return
        }
        val transientLaunch = runCatching {
            XposedHelpers.callMethod(options, "getTransientLaunch") as? Boolean
        }.getOrNull()
        if (transientLaunch != true) {
            diagnostic(
                "not-transient",
                "display-1 SubHome route retained: transientLaunch=$transientLaunch " +
                    "displayId=$launchDisplayId"
            )
            return
        }

        val secondaryComponent = ComponentName(
            SAMSUNG_LAUNCHER_PACKAGE,
            SECONDARY_LAUNCHER_ACTIVITY
        )
        if (!isActivityAvailable(context, secondaryComponent)) {
            diagnostic(
                "secondary-unavailable",
                "display-1 transient SubHome route rejected: SecondaryLauncher unavailable"
            )
            return
        }

        param.args[intentIndex] = Intent(intent).apply {
            if (`package` == SYSTEM_UI_PACKAGE) {
                `package` = SAMSUNG_LAUNCHER_PACKAGE
            }
            this.component = secondaryComponent
        }
        log(
            "display-1 transient Recents Home redirected " +
                "caller=$callingPackage uid=$callingUid displayId=$launchDisplayId " +
                "fold=${RuntimeFacts.foldState} from=$component to=$secondaryComponent"
        )
    }

    private fun originalActivityOptions(safeOptions: Any): ActivityOptions? {
        return runCatching {
            XposedHelpers.callMethod(safeOptions, "getOriginalOptions") as? ActivityOptions
        }.getOrNull() ?: runCatching {
            XposedHelpers.getObjectField(safeOptions, "mOriginalOptions") as? ActivityOptions
        }.getOrNull()
    }

    private fun uidOwnsPackage(context: Context, uid: Int, packageName: String): Boolean {
        return runCatching {
            context.packageManager.getPackagesForUid(uid)?.contains(packageName) == true
        }.getOrDefault(false)
    }

    private fun isActivityAvailable(context: Context, component: ComponentName): Boolean {
        return runCatching {
            context.packageManager.getActivityInfo(component, 0)
        }.isSuccess
    }

    private fun diagnostic(key: String, message: String) {
        val now = SystemClock.uptimeMillis()
        val previous = lastDiagnosticAt.putIfAbsent(key, now)
        if (previous != null) {
            if (now - previous < ROUTE_DIAGNOSTIC_INTERVAL_MS) return
            if (!lastDiagnosticAt.replace(key, previous, now)) return
        }
        log(message)
    }

    private fun isClosedCoverRecents(event: Any?): Boolean {
        if (!RuntimeFacts.isTargetSessionEligible() || event == null) return false
        return com.flexunlock.dexlsp.CoverDisplayResolver.matches(
            event.intProperty("getDisplayId")
        ) &&
            event.intProperty("getKeyGestureType") == RECENTS_GESTURE_TYPE
    }

    private fun Any.intProperty(methodName: String): Int? {
        return runCatching {
            javaClass.methods
                .firstOrNull { method ->
                    method.name == methodName && method.parameterCount == 0
                }
                ?.invoke(this) as? Number
        }.getOrNull()?.toInt()
    }

    private fun log(message: String) {
        Log.i(TAG, message)
        XposedBridge.log("$TAG: $message")
    }
}
