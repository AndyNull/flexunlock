package com.flexunlock.dexlsp

import android.app.ActivityManager
import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Display
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.Collections
import java.util.WeakHashMap

internal object OneHandOperationHooks {
    private const val SCOPE = "OneHandOperation+"
    private const val DEFAULT_DISPLAY_ID = 0
    private const val SETTINGS_ACTIVITY_CLASS =
        "com.samsung.android.sidegesturepad.settings.SGPSettingsActivity"

    @Volatile
    private var desiredDisplayId = DEFAULT_DISPLAY_ID

    @Volatile
    private var pluginClassLoader: ClassLoader? = null

    @Volatile
    private var settingsDisplayEligibilityLogged = false

    private val bindingLock = Any()
    private val callbackDisplayOverride = ThreadLocal<Int?>()
    private val actionDisplayOverride = ThreadLocal<Int?>()
    private val serviceDisplayIds: MutableMap<Any, Int> = Collections.synchronizedMap(
        WeakHashMap()
    )

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.processName != CoverRuntime.ONE_HAND_OPERATION_PACKAGE) return
        pluginClassLoader = lpparam.classLoader
        desiredDisplayId = if (CoverRuntime.isCoverSessionEligible()) {
            CoverDisplayResolver.currentId() ?: DEFAULT_DISPLAY_ID
        } else {
            DEFAULT_DISPLAY_ID
        }
        CoverDisplayResolver.addListener { previous, current ->
            if (previous?.id == current?.id) return@addListener
            desiredDisplayId = if (CoverRuntime.isCoverSessionEligible()) {
                current?.id ?: DEFAULT_DISPLAY_ID
            } else {
                DEFAULT_DISPLAY_ID
            }
            syncSecondaryLauncherHome(lpparam.classLoader)
            val services = synchronized(serviceDisplayIds) { serviceDisplayIds.keys.toList() }
            services.forEach { service ->
                ensureServiceDisplay(service, effectiveDisplayId(), "display-resolution")
            }
        }

        installSettingsDisplayEligibility(lpparam.classLoader)
        installReflectionGateway(lpparam.classLoader)
        installArrowWindowRouting(lpparam.classLoader)
        installUtilsDisplayContext(lpparam.classLoader)
        installPluginWindowContexts(lpparam.classLoader)
        installControllerContext(lpparam.classLoader)
        installNativeKeyEventRouting(lpparam.classLoader)
        installServiceLifecycle(lpparam.classLoader)
        installCoverLifecycle(lpparam.classLoader)
        installRecentsRouting(lpparam.classLoader)
        CoverRuntime.log(SCOPE, "package-local display ownership hooks installed")
    }

    private fun installSettingsDisplayEligibility(classLoader: ClassLoader) {
        if (
            XposedHelpers.findClassIfExists(
                SETTINGS_ACTIVITY_CLASS,
                classLoader
            ) == null
        ) {
            return unavailable("settings display eligibility", "SGPSettingsActivity missing")
        }

        runCatching {
            XposedHelpers.findAndHookMethod(
                Display::class.java,
                "getDisplayId",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!CoverRuntime.isCoverSessionEligible()) return
                        val actualDisplayId = runCatching {
                            XposedHelpers.getIntField(param.thisObject, "mDisplayId")
                        }.getOrNull() ?: return
                        if (!CoverRuntime.isCoverDisplay(actualDisplayId)) return

                        val caller = Throwable().stackTrace.firstOrNull { frame ->
                            frame.className.startsWith(CoverRuntime.ONE_HAND_OPERATION_PACKAGE)
                        } ?: return
                        if (
                            caller.className != SETTINGS_ACTIVITY_CLASS ||
                            caller.methodName != "onCreate"
                        ) {
                            return
                        }

                        param.result = DEFAULT_DISPLAY_ID
                        if (!settingsDisplayEligibilityLogged) {
                            settingsDisplayEligibilityLogged = true
                            CoverRuntime.log(
                                SCOPE,
                                "SGPSettingsActivity display-1 eligibility branch adapted"
                            )
                        }
                    }
                }
            )
            CoverRuntime.log(SCOPE, "settings display eligibility hook installed")
        }.onFailure { unavailable("settings display eligibility", it.message) }
    }

    private fun installReflectionGateway(classLoader: ClassLoader) {
        val reflectionBase = XposedHelpers.findClassIfExists("b5.a", classLoader)
            ?: return unavailable("reflection gateway", "b5.a missing")
        val method = reflectionBase.declaredMethods.firstOrNull {
            it.name == "h" && it.parameterCount == 4
        } ?: return unavailable("reflection gateway", "h(Object,String,Class[],Object[]) missing")

        runCatching {
            method.isAccessible = true
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val methodName = param.args.getOrNull(1) as? String ?: return
                    @Suppress("UNCHECKED_CAST")
                    val values = param.args.getOrNull(3) as? Array<Any?> ?: return
                    val targetDisplayId = actionDisplayOverride.get()
                        ?: callbackDisplayOverride.get()
                        ?: effectiveDisplayId()
                    when (methodName) {
                        "watchRotation",
                        "registerSystemGestureExclusionListener",
                        "unregisterSystemGestureExclusionListener" -> {
                            if (values.size > 1) values[1] = targetDisplayId
                        }

                        "startActivityFromRecents" -> {
                            if (
                                CoverRuntime.isCoverDisplay(targetDisplayId) &&
                                values.size > 1
                            ) {
                                values[1] = launchBundle(values[1] as? Bundle, targetDisplayId)
                            }
                        }

                        "startActivityAsUser" -> {
                            if (!CoverRuntime.isCoverDisplay(targetDisplayId)) return
                            val context = param.args.firstOrNull() as? Context
                            if (context != null) {
                                CoverRuntime.contextForDisplay(context, targetDisplayId)?.let {
                                    param.args[0] = it
                                }
                            }
                            if (values.size > 1) {
                                values[1] = launchBundle(values[1] as? Bundle, targetDisplayId)
                            }
                        }

                        "injectInputEvent" -> {
                            if (!CoverRuntime.isCoverDisplay(targetDisplayId)) return
                            val event = values.firstOrNull() as? KeyEvent ?: return
                            if (event.keyCode in TARGETED_KEY_CODES) {
                                routeKeyEventToDisplay(event, targetDisplayId)
                            }
                        }
                    }
                }
            })
            CoverRuntime.log(SCOPE, "native rotation/exclusion/action gateway installed")
        }.onFailure { unavailable("reflection gateway", it.message) }
    }

    private fun installArrowWindowRouting(classLoader: ClassLoader) {
        val arrowClass = XposedHelpers.findClassIfExists("m6.l", classLoader)
            ?: return unavailable("arrow window routing", "m6.l missing")
        runCatching {
            val showMethods = arrowClass.declaredMethods.filter { method ->
                method.name == "d" && method.parameterCount == 4
            }
            showMethods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val view = getFieldOrNull(param.thisObject, "e") as? View ?: return
                        val context = CoverRuntime.contextForDisplay(
                            view.context,
                            effectiveDisplayId()
                        ) ?: return
                        moveArrowWindowOwner(param.thisObject, context)
                    }
                })
            }
            CoverRuntime.log(
                SCOPE,
                "native feedback window routing installed methods=${showMethods.size}"
            )
        }.onFailure { unavailable("arrow window routing", it.message) }
    }

    private fun installNativeKeyEventRouting(classLoader: ClassLoader) {
        val utilsClass = XposedHelpers.findClassIfExists("n6.y", classLoader)
            ?: return unavailable("native key display routing", "n6.y missing")
        runCatching {
            XposedHelpers.findAndHookMethod(
                utilsClass,
                "n1",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val coverDisplayId = CoverDisplayResolver.currentId() ?: return
                        if (effectiveDisplayId() != coverDisplayId) return
                        val keyCode = (param.args.firstOrNull() as? Number)?.toInt() ?: return
                        if (keyCode in TARGETED_KEY_CODES) {
                            actionDisplayOverride.set(coverDisplayId)
                        }
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        actionDisplayOverride.remove()
                    }
                }
            )
            CoverRuntime.log(SCOPE, "native Back/Home/Recents key display routing installed")
        }.onFailure { unavailable("native key display routing", it.message) }
    }

    private fun installUtilsDisplayContext(classLoader: ClassLoader) {
        val utilsClass = XposedHelpers.findClassIfExists("n6.y", classLoader)
            ?: return unavailable("utils display context", "n6.y missing")
        runCatching {
            XposedHelpers.findAndHookMethod(
                utilsClass,
                "m0",
                Context::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        replaceContextArgument(param)
                    }
                }
            )
            XposedHelpers.findAndHookMethod(
                utilsClass,
                "d",
                Context::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val context = replaceContextArgument(param) ?: return
                        setFieldIfPresent(param.thisObject, "b", context)
                        setFieldIfPresent(
                            param.thisObject,
                            "g",
                            context.getSystemService(WindowManager::class.java)
                        )
                    }
                }
            )
        }.onFailure { unavailable("utils display context", it.message) }
    }

    private fun installPluginWindowContexts(classLoader: ClassLoader) {
        val contextOwnedClasses = listOf(
            "m6.f",
            "l6.j",
            "v5.a0",
            "q6.m",
            "p6.m",
            "o6.q"
        )
        var installed = 0
        contextOwnedClasses.forEach { className ->
            val clazz = XposedHelpers.findClassIfExists(className, classLoader) ?: return@forEach
            runCatching {
                XposedBridge.hookAllConstructors(clazz, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val contextIndex = param.args.indexOfFirst { it is Context }
                        if (contextIndex < 0) return
                        val context = param.args[contextIndex] as Context
                        CoverRuntime.contextForDisplay(context, effectiveDisplayId())?.let {
                            param.args[contextIndex] = it
                        }
                    }
                })
                installed++
            }
        }
        CoverRuntime.log(SCOPE, "plugin window context hooks installed classes=$installed")
    }

    private fun installControllerContext(classLoader: ClassLoader) {
        val controllerClass = XposedHelpers.findClassIfExists("r5.f", classLoader)
            ?: return unavailable("controller context", "r5.f missing")
        runCatching {
            XposedBridge.hookAllConstructors(controllerClass, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val service = param.args.firstOrNull() as? Context ?: return
                    val context = CoverRuntime.contextForDisplay(service, effectiveDisplayId()) ?: return
                    rebaseUtils(param.thisObject, context)
                    setFieldIfPresent(param.thisObject, "a", context)
                    moveArrowWindow(param.thisObject, context)
                    getFieldOrNull(param.thisObject, "o")?.let { recents ->
                        setFieldIfPresent(recents, "g", context)
                    }
                }
            })
            CoverRuntime.log(SCOPE, "controller display context hook installed")
        }.onFailure { unavailable("controller context", it.message) }
    }

    private fun installServiceLifecycle(classLoader: ClassLoader) {
        val serviceClass = XposedHelpers.findClassIfExists(
            "com.samsung.android.sidegesturepad.SGPService",
            classLoader
        ) ?: return unavailable("service lifecycle", "SGPService missing")

        runCatching {
            XposedHelpers.findAndHookMethod(
                serviceClass,
                "onCreate",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        desiredDisplayId = if (CoverRuntime.isCoverSessionEligible()) {
                            CoverDisplayResolver.currentId() ?: DEFAULT_DISPLAY_ID
                        } else {
                            DEFAULT_DISPLAY_ID
                        }
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        syncSecondaryLauncherHome(classLoader)
                        serviceDisplayIds[param.thisObject] = effectiveDisplayId()
                    }
                }
            )
            XposedHelpers.findAndHookMethod(
                serviceClass,
                "onStartCommand",
                Intent::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val intent = param.args.firstOrNull() as? Intent
                        if (intent?.getStringExtra("option") == "stop") return
                        syncSecondaryLauncherHome(classLoader)
                        ensureServiceDisplay(param.thisObject, effectiveDisplayId(), "service-start")
                    }
                }
            )
            XposedHelpers.findAndHookMethod(
                serviceClass,
                "onDestroy",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        serviceDisplayIds.remove(param.thisObject)
                    }
                }
            )
        }.onFailure { unavailable("service lifecycle", it.message) }
    }

    private fun installCoverLifecycle(classLoader: ClassLoader) {
        val listenerClass = XposedHelpers.findClassIfExists("r5.k", classLoader)
            ?: return unavailable("cover lifecycle", "r5.k missing")
        val stateClass = XposedHelpers.findClassIfExists(
            "com.samsung.android.sdk.cover.ScoverState",
            classLoader
        ) ?: return unavailable("cover lifecycle", "ScoverState missing")

        runCatching {
            XposedHelpers.findAndHookMethod(
                listenerClass,
                "onCoverStateChanged",
                stateClass,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        desiredDisplayId = displayIdFromCoverState(param.args.firstOrNull())
                        syncSecondaryLauncherHome(classLoader)
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        val service = getFieldOrNull(param.thisObject, "a") ?: return
                        ensureServiceDisplay(service, effectiveDisplayId(), "cover-state")
                    }
                }
            )
            CoverRuntime.log(SCOPE, "cover lifecycle display switch installed")
        }.onFailure { unavailable("cover lifecycle", it.message) }
    }

    private fun installRecentsRouting(classLoader: ClassLoader) {
        installRecentsListFilter(classLoader)
        installRecentsTaskHelpers(classLoader)
        installForegroundMonitor(classLoader)
    }

    private fun installRecentsListFilter(classLoader: ClassLoader) {
        val runnableClass = XposedHelpers.findClassIfExists("a5.d", classLoader)
            ?: return unavailable("Recents list", "a5.d missing")
        runCatching {
            XposedHelpers.findAndHookMethod(
                runnableClass,
                "run",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!CoverRuntime.isCoverDisplay(effectiveDisplayId())) return
                        val kind = getIntFieldOrNull(param.thisObject, "d") ?: return
                        if (kind == 1) filterRecentsEntries(param.thisObject)
                    }
                }
            )
        }.onFailure { unavailable("Recents list", it.message) }
    }

    private fun installRecentsTaskHelpers(classLoader: ClassLoader) {
        val recentsClass = XposedHelpers.findClassIfExists("a5.i", classLoader)
            ?: return unavailable("Recents actions", "a5.i missing")
        runCatching {
            listOf("g", "h").forEach { methodName ->
                XposedHelpers.findAndHookMethod(
                    recentsClass,
                    methodName,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (!CoverRuntime.isCoverDisplay(effectiveDisplayId())) return
                            val task = when (methodName) {
                                "g" -> firstCoverTask(param.thisObject) { task ->
                                    isFreeformTask(classLoader, task) && isTaskVisible(task)
                                }
                                else -> firstCoverTask(param.thisObject) { task ->
                                    isFullscreenTask(task)
                                }
                            }
                            param.result = task
                        }
                    }
                )
            }
            XposedHelpers.findAndHookMethod(
                recentsClass,
                "n",
                Int::class.javaPrimitiveType,
                ComponentName::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!CoverRuntime.isCoverDisplay(effectiveDisplayId())) return
                        val taskId = (param.args.firstOrNull() as? Number)?.toInt() ?: return
                        if (!isCoverRecentTask(param.thisObject, taskId)) param.result = -1
                    }
                }
            )
        }.onFailure { unavailable("Recents actions", it.message) }
    }

    private fun installForegroundMonitor(classLoader: ClassLoader) {
        val runnableClass = XposedHelpers.findClassIfExists("a5.d", classLoader)
            ?: return
        runCatching {
            XposedHelpers.findAndHookMethod(
                runnableClass,
                "run",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!CoverRuntime.isCoverDisplay(effectiveDisplayId())) return
                        val kind = getIntFieldOrNull(param.thisObject, "d") ?: return
                        if (kind != 0) return
                        updateCoverForegroundMonitor(param.thisObject, classLoader)
                        param.result = null
                    }
                }
            )
        }.onFailure { unavailable("foreground monitor", it.message) }
    }

    private fun ensureServiceDisplay(service: Any, targetDisplayId: Int, reason: String) {
        runCatching {
            synchronized(bindingLock) {
                val oldController = getFieldOrNull(service, "j") ?: return
                val currentDisplayId = controllerDisplayId(oldController)
                if (currentDisplayId == targetDisplayId) {
                    val context = service as? Context ?: return
                    CoverRuntime.contextForDisplay(context, targetDisplayId)?.let {
                        rebaseUtils(oldController, it)
                    }
                    serviceDisplayIds[service] = targetDisplayId
                    return
                }

                val serviceContext = service as? Context ?: return
                val targetContext = CoverRuntime.contextForDisplay(serviceContext, targetDisplayId)
                    ?: return unavailable("display switch", "display $targetDisplayId unavailable")
                val hadSystemCallbacks = getBooleanFieldOrNull(service, "A") == true
                val oldDisplayId = serviceDisplayIds[service] ?: currentDisplayId
                if (hadSystemCallbacks) unregisterSystemCallbacks(service, oldDisplayId)

                runCatching { XposedHelpers.callMethod(oldController, "R") }
                rebaseUtils(oldController, targetContext)

                val controllerClass = oldController.javaClass
                val messenger = getFieldOrNull(service, "v")
                val newController = XposedHelpers.newInstance(controllerClass, service, messenger)
                setFieldIfPresent(service, "j", newController)
                restoreControllerToggleState(service, newController)
                if (hadSystemCallbacks) registerSystemCallbacks(service, targetDisplayId)

                serviceDisplayIds[service] = targetDisplayId
                CoverRuntime.log(
                    SCOPE,
                    "controller rebound display=$currentDisplayId->$targetDisplayId reason=$reason"
                )
            }
        }.onFailure { error ->
            unavailable("display switch", error.message)
        }
    }

    private fun unregisterSystemCallbacks(service: Any, displayId: Int) {
        val rotationWatcher = getFieldOrNull(service, "B")
        val exclusionListener = getFieldOrNull(service, "E")
        withCallbackDisplay(displayId) {
            if (rotationWatcher != null) {
                callWindowManagerReflection(
                    "removeRotationWatcher",
                    arrayOf("android.view.IRotationWatcher"),
                    arrayOf(rotationWatcher)
                )
            }
            if (exclusionListener != null) {
                callWindowManagerReflection(
                    "unregisterSystemGestureExclusionListener",
                    arrayOf("android.view.ISystemGestureExclusionListener", "int"),
                    arrayOf(exclusionListener, displayId)
                )
            }
        }
        setBooleanFieldIfPresent(service, "A", false)
    }

    private fun registerSystemCallbacks(service: Any, displayId: Int) {
        val rotationWatcher = getFieldOrNull(service, "B")
        val exclusionListener = getFieldOrNull(service, "E")
        withCallbackDisplay(displayId) {
            if (rotationWatcher != null) {
                callWindowManagerReflection(
                    "watchRotation",
                    arrayOf("android.view.IRotationWatcher", "int"),
                    arrayOf(rotationWatcher, displayId)
                )
            }
            if (exclusionListener != null) {
                callWindowManagerReflection(
                    "registerSystemGestureExclusionListener",
                    arrayOf("android.view.ISystemGestureExclusionListener", "int"),
                    arrayOf(exclusionListener, displayId)
                )
            }
        }
        setBooleanFieldIfPresent(service, "A", true)
    }

    private fun callWindowManagerReflection(
        methodName: String,
        signatureNames: Array<String>,
        values: Array<Any>
    ) {
        val classLoader = pluginClassLoader ?: return
        runCatching {
            val helperClass = XposedHelpers.findClassIfExists("n5.a", classLoader) ?: return
            val helper = XposedHelpers.callStaticMethod(helperClass, "m") ?: return
            val serviceManagerClass = XposedHelpers.findClassIfExists("k5.b", classLoader)
                ?: return
            val serviceManager = XposedHelpers.callStaticMethod(serviceManagerClass, "l") ?: return
            val windowBinder = XposedHelpers.callMethod(serviceManager, "k") ?: return
            val binderAdapterClass = XposedHelpers.findClassIfExists("n5.b", classLoader)
                ?: return
            val binderAdapter = XposedHelpers.callStaticMethod(binderAdapterClass, "J") ?: return
            val windowManager = XposedHelpers.callMethod(binderAdapter, "o", windowBinder) ?: return
            val signature = signatureNames.map { name ->
                if (name == "int") Int::class.javaPrimitiveType!! else {
                    XposedHelpers.findClass(name, null)
                }
            }.toTypedArray()
            XposedHelpers.callMethod(
                helper,
                "h",
                windowManager,
                methodName,
                signature,
                values
            )
        }.onFailure { unavailable("$methodName callback", it.message) }
    }

    private inline fun withCallbackDisplay(displayId: Int, block: () -> Unit) {
        val previous = callbackDisplayOverride.get()
        callbackDisplayOverride.set(displayId)
        try {
            block()
        } finally {
            if (previous == null) callbackDisplayOverride.remove()
            else callbackDisplayOverride.set(previous)
        }
    }

    private fun restoreControllerToggleState(service: Any, controller: Any) {
        if (getBooleanFieldOrNull(service, "s") != false) return
        getFieldOrNull(controller, "c")?.let { runCatching { XposedHelpers.callMethod(it, "o") } }
        getFieldOrNull(controller, "d")?.let { runCatching { XposedHelpers.callMethod(it, "o") } }
        getFieldOrNull(controller, "v")?.let {
            runCatching { XposedHelpers.callMethod(it, "h", false) }
        }
        runCatching { XposedHelpers.callMethod(controller, "T") }
    }

    private fun moveArrowWindow(controller: Any, context: Context) {
        val arrow = getFieldOrNull(controller, "b") ?: return
        moveArrowWindowOwner(arrow, context)
    }

    private fun moveArrowWindowOwner(arrow: Any, context: Context) {
        val view = getFieldOrNull(arrow, "e") as? View ?: return
        val oldWindowManager = getFieldOrNull(arrow, "b") as? WindowManager ?: return
        val targetDisplayId = CoverRuntime.displayIdOf(context) ?: return
        val currentDisplayId = runCatching {
            oldWindowManager.defaultDisplay.displayId
        }.getOrNull()
        if (currentDisplayId == targetDisplayId) return

        val newWindowManager = context.getSystemService(WindowManager::class.java) ?: return
        val layoutParams = getFieldOrNull(arrow, "c") as? WindowManager.LayoutParams ?: return
        val wasAttached = view.isAttachedToWindow
        if (wasAttached && runCatching {
                oldWindowManager.removeViewImmediate(view)
            }.isFailure
        ) {
            return unavailable(
                "feedback window move",
                "remove failed display=$currentDisplayId->$targetDisplayId"
            )
        }

        setFieldIfPresent(arrow, "b", newWindowManager)
        if (wasAttached) {
            val added = runCatching { newWindowManager.addView(view, layoutParams) }
            if (added.isFailure) {
                setFieldIfPresent(arrow, "b", oldWindowManager)
                if (!view.isAttachedToWindow) {
                    runCatching { oldWindowManager.addView(view, layoutParams) }
                }
                return unavailable(
                    "feedback window move",
                    added.exceptionOrNull()?.message
                )
            }
        }
        CoverRuntime.log(
            SCOPE,
            "feedback window moved display=$currentDisplayId->$targetDisplayId"
        )
    }

    private fun routeKeyEventToDisplay(event: KeyEvent, displayId: Int) {
        runCatching { XposedHelpers.callMethod(event, "semSetDisplayId", displayId) }
        val routedDisplayId = runCatching {
            (XposedHelpers.callMethod(event, "getDisplayId") as? Number)?.toInt()
        }.getOrNull()
        if (routedDisplayId != displayId) {
            runCatching { XposedHelpers.setIntField(event, "mDisplayId", displayId) }
        }
        val actualDisplayId = runCatching {
            (XposedHelpers.callMethod(event, "getDisplayId") as? Number)?.toInt()
        }.getOrNull()
        if (actualDisplayId != displayId) {
            unavailable(
                "native key display routing",
                "keyCode=${event.keyCode} expected=$displayId actual=$actualDisplayId"
            )
        }
    }

    private fun rebaseUtils(controller: Any, context: Context) {
        val utils = getFieldOrNull(controller, "v") ?: return
        setFieldIfPresent(utils, "b", context)
        setFieldIfPresent(utils, "g", context.getSystemService(WindowManager::class.java))
        runCatching { XposedHelpers.callMethod(utils, "d", context) }
    }

    private fun filterRecentsEntries(runnable: Any) {
        val owner = getFieldOrNull(runnable, "e") ?: return
        if (owner.javaClass.name != "a5.i") return
        val manager = getFieldOrNull(owner, "c") as? ActivityManager ?: return
        val max = getIntFieldOrNull(owner, "b") ?: 48
        val coverIds = runCatching {
            manager.getRecentTasks(max, ActivityManager.RECENT_IGNORE_UNAVAILABLE)
                .filter { CoverRuntime.isCoverDisplay(taskDisplayId(it)) }
                .mapTo(HashSet()) { it.persistentId }
        }.getOrDefault(emptySet())
        @Suppress("UNCHECKED_CAST")
        val entries = getFieldOrNull(owner, "i") as? List<Any> ?: return
        val filtered = ArrayList(entries.filter { entry ->
            val taskId = getIntFieldOrNull(entry, "e") ?: -1
            taskId <= 0 || taskId in coverIds
        })
        setFieldIfPresent(owner, "i", filtered)
    }

    private fun isCoverRecentTask(owner: Any, taskId: Int): Boolean {
        val manager = getFieldOrNull(owner, "c") as? ActivityManager ?: return false
        val max = getIntFieldOrNull(owner, "b") ?: 48
        return runCatching {
            manager.getRecentTasks(max, ActivityManager.RECENT_IGNORE_UNAVAILABLE).any { task ->
                task.persistentId == taskId &&
                    CoverRuntime.isCoverDisplay(taskDisplayId(task))
            }
        }.getOrDefault(false)
    }

    private fun firstCoverTask(
        owner: Any,
        predicate: (ActivityManager.RunningTaskInfo) -> Boolean
    ): ActivityManager.RunningTaskInfo? {
        val manager = getFieldOrNull(owner, "c") as? ActivityManager ?: return null
        return runCatching {
            manager.getRunningTasks(32).firstOrNull { task ->
                CoverRuntime.isCoverDisplay(taskDisplayId(task)) && predicate(task)
            }
        }.getOrNull()
    }

    private fun isFreeformTask(
        classLoader: ClassLoader,
        task: ActivityManager.RunningTaskInfo
    ): Boolean {
        val recentsClass = XposedHelpers.findClassIfExists("a5.i", classLoader) ?: return false
        return runCatching {
            XposedHelpers.callStaticMethod(recentsClass, "j", task) as? Boolean
        }.getOrNull() == true
    }

    private fun isFullscreenTask(task: ActivityManager.RunningTaskInfo): Boolean = runCatching {
        val configuration = XposedHelpers.getObjectField(task, "configuration")
        val windowConfiguration = XposedHelpers.getObjectField(
            configuration,
            "windowConfiguration"
        )
        val mode = XposedHelpers.callMethod(windowConfiguration, "getWindowingMode") as Number
        mode.toInt() == 1
    }.getOrDefault(false)

    private fun updateCoverForegroundMonitor(runnable: Any, classLoader: ClassLoader) {
        val monitor = getFieldOrNull(runnable, "e") ?: return
        if (monitor.javaClass.name != "a5.e") return
        val manager = getFieldOrNull(monitor, "b") as? ActivityManager ?: return
        val task = runCatching {
            manager.getRunningTasks(32).firstOrNull {
                CoverRuntime.isCoverDisplay(taskDisplayId(it)) && isTaskVisible(it)
            }
        }.getOrNull() ?: return
        val component = task.topActivity ?: task.baseActivity ?: return
        val packageName = component.packageName
        val monitorClass = XposedHelpers.findClassIfExists("a5.e", classLoader) ?: return
        val oldHome = runCatching {
            XposedHelpers.getStaticBooleanField(monitorClass, "p")
        }.getOrDefault(false)
        val isHome = runCatching {
            XposedHelpers.callStaticMethod(monitorClass, "a", task) as? Boolean
        }.getOrNull() == true
        XposedHelpers.setStaticBooleanField(monitorClass, "p", isHome)
        if (isHome) {
            setIntFieldIfPresent(monitor, "k", task.taskId)
            setFieldIfPresent(monitor, "i", packageName)
            setFieldIfPresent(monitor, "g", component)
        }

        val oldPackage = getFieldOrNull(monitor, "h") as? String
        val callback = getFieldOrNull(monitor, "f")
        if (callback != null && (packageName != oldPackage || (!oldHome && isHome))) {
            runCatching { XposedHelpers.callMethod(callback, "R", packageName, component) }
            @Suppress("UNCHECKED_CAST")
            val exceptions = getFieldOrNull(monitor, "l") as? List<String>
            if (
                exceptions != null &&
                packageName !in exceptions &&
                oldPackage != null &&
                oldPackage in exceptions
            ) {
                runCatching {
                    XposedHelpers.callMethod(
                        callback,
                        "Q",
                        packageName,
                        packageName in exceptions
                    )
                }
            }
        }
        setFieldIfPresent(monitor, "h", packageName)
    }

    private fun syncSecondaryLauncherHome(classLoader: ClassLoader) {
        val monitorClass = XposedHelpers.findClassIfExists("a5.e", classLoader) ?: return
        @Suppress("UNCHECKED_CAST")
        val homes = runCatching {
            XposedHelpers.getStaticObjectField(monitorClass, "o") as? MutableCollection<ComponentName>
        }.getOrNull() ?: return
        val secondaryLauncher = ComponentName(
            CoverRuntime.SAMSUNG_LAUNCHER_PACKAGE,
            "com.honeyspace.dexservice.SecondaryLauncher"
        )
        if (CoverRuntime.isCoverDisplay(effectiveDisplayId())) {
            if (secondaryLauncher !in homes) homes.add(secondaryLauncher)
        } else {
            homes.remove(secondaryLauncher)
        }
    }

    private fun replaceContextArgument(param: XC_MethodHook.MethodHookParam): Context? {
        val context = param.args.firstOrNull() as? Context ?: return null
        val target = CoverRuntime.contextForDisplay(context, effectiveDisplayId()) ?: return null
        param.args[0] = target
        return target
    }

    private fun launchBundle(existing: Bundle?, displayId: Int): Bundle {
        val result = existing ?: Bundle()
        val displayOptions = ActivityOptions.makeBasic().apply {
            launchDisplayId = displayId
        }.toBundle()
        result.putAll(displayOptions)
        return result
    }

    private fun displayIdFromCoverState(state: Any?): Int {
        val opened = runCatching {
            XposedHelpers.callMethod(state, "getSwitchState") as? Boolean
        }.getOrNull() ?: !CoverRuntime.isCoverSessionEligible()
        return if (opened) {
            DEFAULT_DISPLAY_ID
        } else {
            CoverDisplayResolver.currentId() ?: DEFAULT_DISPLAY_ID
        }
    }

    private fun effectiveDisplayId(): Int {
        val coverDisplayId = CoverDisplayResolver.currentId() ?: return DEFAULT_DISPLAY_ID
        return if (
            desiredDisplayId == coverDisplayId &&
            CoverRuntime.isDisplayAvailable(coverDisplayId)
        ) {
            coverDisplayId
        } else {
            DEFAULT_DISPLAY_ID
        }
    }

    private fun controllerDisplayId(controller: Any): Int {
        val context = getFieldOrNull(controller, "a") as? Context
        return CoverRuntime.displayIdOf(context) ?: DEFAULT_DISPLAY_ID
    }

    private fun isTaskVisible(task: Any): Boolean = runCatching {
        XposedHelpers.callMethod(task, "isVisible") as? Boolean
    }.getOrNull() == true

    private fun taskDisplayId(task: Any): Int = runCatching {
        XposedHelpers.getIntField(task, "displayId")
    }.getOrDefault(DEFAULT_DISPLAY_ID)

    private fun getFieldOrNull(instance: Any, field: String): Any? = runCatching {
        XposedHelpers.getObjectField(instance, field)
    }.getOrNull()

    private fun getIntFieldOrNull(instance: Any, field: String): Int? = runCatching {
        XposedHelpers.getIntField(instance, field)
    }.getOrNull()

    private fun getBooleanFieldOrNull(instance: Any, field: String): Boolean? = runCatching {
        XposedHelpers.getBooleanField(instance, field)
    }.getOrNull()

    private fun setFieldIfPresent(instance: Any, field: String, value: Any?) {
        runCatching { XposedHelpers.setObjectField(instance, field, value) }
    }

    private fun setIntFieldIfPresent(instance: Any, field: String, value: Int) {
        runCatching { XposedHelpers.setIntField(instance, field, value) }
    }

    private fun setBooleanFieldIfPresent(instance: Any, field: String, value: Boolean) {
        runCatching { XposedHelpers.setBooleanField(instance, field, value) }
    }

    private fun unavailable(feature: String, reason: String?) {
        CoverRuntime.log(SCOPE, "$feature unavailable: ${reason ?: "unknown"}")
    }

    private val TARGETED_KEY_CODES = setOf(
        KeyEvent.KEYCODE_HOME,
        KeyEvent.KEYCODE_BACK,
        KeyEvent.KEYCODE_APP_SWITCH
    )
}
