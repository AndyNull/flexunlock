package com.flexunlock.dexlsp.system.runtime

import android.app.ActivityOptions
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.util.Log
import android.view.WindowInsets
import android.view.WindowManager
import com.flexunlock.dexlsp.CoverDisplayResolver
import com.flexunlock.dexlsp.CoverDisplaySnapshot
import com.flexunlock.dexlsp.config.AppDisplayProfile
import com.flexunlock.dexlsp.config.AppDisplayProfileConfig
import com.flexunlock.dexlsp.config.AppWindowMode
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

object CoverAppLaunchProfilePolicy {
    private const val TAG = "FlexUnlock-SystemBridge"
    private const val ACTIVITY_STARTER_CLASS = "com.android.server.wm.ActivityStarter"
    private const val CONTROL_PACKAGE = "com.flexunlock.dexlsp"
    private const val WINDOWING_MODE_FULLSCREEN = 1
    private const val WINDOWING_MODE_FREEFORM = 5
    private const val COVER_DENSITY_MIN_DPI = 240
    private const val COVER_DENSITY_MAX_DPI = 480
    private const val COVER_DENSITY_FALLBACK_DPI = 340
    private const val DISPLAY_TYPE_BUILT_IN = 1

    @Volatile
    private var systemContext: Context? = null

    @Volatile
    private var customizedDensitySetter: Method? = null

    private val densityUnavailableLogged = AtomicBoolean(false)
    private val lastGoodCoverDensityDpi = AtomicInteger(0)
    private val densityPinnedPackages = ConcurrentHashMap.newKeySet<String>()

    fun bind(context: Context) {
        systemContext = context
    }

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        val starterClass = XposedHelpers.findClassIfExists(
            ACTIVITY_STARTER_CLASS,
            lpparam.classLoader
        ) ?: return log("app launch profile unavailable: ActivityStarter missing")
        customizedDensitySetter = ActivityOptions::class.java.methods.firstOrNull { method ->
            method.name == "setCustomizedCoverDensity" &&
                method.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType))
        }?.apply { isAccessible = true }

        runCatching {
            val methods = starterClass.declaredMethods.filter { method ->
                method.name == "setInitialState" &&
                    method.parameterTypes.firstOrNull()?.name ==
                    "com.android.server.wm.ActivityRecord" &&
                    method.parameterTypes.getOrNull(1) == ActivityOptions::class.java
            }
            methods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        applyProfile(param)
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        applyProfile(param)
                    }
                })
            }
            log(
                "app launch profile installed methods=${methods.size} " +
                    "customDensity=${customizedDensitySetter != null}"
            )
        }.onFailure { error ->
            log("app launch profile unavailable: ${error.message}")
        }
        runCatching { installFullModeTaskDensity(lpparam.classLoader) }
            .onFailure { error -> log("FULL app density pin unavailable: ${error.message}") }
    }

    private fun installFullModeTaskDensity(classLoader: ClassLoader) {
        val taskClass = XposedHelpers.findClassIfExists(
            "com.android.server.wm.Task",
            classLoader
        ) ?: return
        val taskFragmentClass = XposedHelpers.findClassIfExists(
            "com.android.server.wm.TaskFragment",
            classLoader
        ) ?: return
        val taskUtilsClass = XposedHelpers.findClassIfExists(
            "com.android.server.wm.CoverLauncherTaskUtils",
            classLoader
        ) ?: return
        val methods = taskFragmentClass.declaredMethods.filter { method ->
            method.name == "resolveOverrideConfiguration" &&
                method.parameterTypes.singleOrNull()?.name == "android.content.res.Configuration"
        }
        methods.forEach { method ->
            method.isAccessible = true
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val task = taskFromFragment(param.thisObject, taskClass) ?: return
                    pinFullModeTaskDensity(task, taskUtilsClass)
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    val task = taskFromFragment(param.thisObject, taskClass) ?: return
                    val density = pinFullModeTaskDensity(task, taskUtilsClass) ?: return
                    runCatching {
                        val resolved = XposedHelpers.callMethod(
                            param.thisObject,
                            "getResolvedOverrideConfiguration"
                        )
                        XposedHelpers.setIntField(resolved, "densityDpi", density)
                    }
                }
            })
        }
        installCoverModeActivityDensity(classLoader, taskClass)
        installCoverLauncherDensityOverride(taskUtilsClass, taskClass)
        log("cover app density pin installed methods=${methods.size}")
    }

    private fun installCoverLauncherDensityOverride(
        taskUtilsClass: Class<*>,
        taskClass: Class<*>
    ) {
        val methods = taskUtilsClass.declaredMethods.filter { method ->
            method.name == "applyCustomizedDensityScaleIfNeeded" &&
                method.parameterTypes.firstOrNull() == taskClass &&
                method.parameterTypes.getOrNull(1) == Int::class.javaPrimitiveType
        }
        methods.forEach { method ->
            method.isAccessible = true
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val task = param.args.firstOrNull() ?: return
                    val displayId = (param.args.getOrNull(1) as? Number)?.toInt() ?: return
                    if (!isCoverDensityDisplay(displayId)) return
                    val packageName = targetPackageName(taskUtilsClass, task) ?: return
                    if (CoverAppDisplayProfiles.profileFor(packageName)
                            ?.takeUnless { it.isDefault } != null
                    ) {
                        runCatching {
                            XposedHelpers.setBooleanField(
                                task,
                                "mIsCoverLauncherPolicyEnabled",
                                true
                            )
                        }
                    }
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    val task = param.args.firstOrNull() ?: return
                    val displayId = (param.args.getOrNull(1) as? Number)?.toInt() ?: return
                    if (!isCoverDensityDisplay(displayId)) return
                    val packageName = targetPackageName(taskUtilsClass, task)
                    val density = pinTaskDensity(task, packageName, displayId) ?: return
                    log(
                        "cover density override applied package=$packageName " +
                            "displayId=$displayId density=$density"
                    )
                }
            })
        }
        log("cover launcher density override installed methods=${methods.size}")
    }

    private fun targetPackageName(taskUtilsClass: Class<*>, task: Any): String? =
        runCatching {
            XposedHelpers.callStaticMethod(
                taskUtilsClass,
                "getTargetPackageName",
                task
            ) as? String
        }.getOrNull()

    private fun installCoverModeActivityDensity(
        classLoader: ClassLoader,
        taskClass: Class<*>
    ) {
        val activityClass = XposedHelpers.findClassIfExists(
            "com.android.server.wm.ActivityRecord",
            classLoader
        ) ?: return
        activityClass.declaredMethods
            .filter { method ->
                method.name == "resolveOverrideConfiguration" &&
                    method.parameterTypes.singleOrNull()?.name == "android.content.res.Configuration"
            }
            .forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject
                        val displayId = displayIdOf(activity) ?: return
                        if (!isCoverDensityDisplay(displayId)) return
                        val task = runCatching {
                            XposedHelpers.callMethod(activity, "getTask")
                        }.getOrNull()?.takeIf(taskClass::isInstance) ?: return
                        val packageName = packageNameOf(activity) ?: return
                        val density = CoverAppDisplayProfiles.profileFor(packageName)
                            ?.takeUnless { it.isDefault }
                            ?.let { pinTaskDensity(task, packageName, displayId) }
                            ?: return
                        runCatching {
                            val requested = XposedHelpers.callMethod(
                                activity,
                                "getRequestedOverrideConfiguration"
                            )
                            XposedHelpers.setIntField(requested, "densityDpi", density)
                            val resolved = XposedHelpers.callMethod(
                                activity,
                                "getResolvedOverrideConfiguration"
                            )
                            XposedHelpers.setIntField(resolved, "densityDpi", density)
                        }
                    }
                })
            }
    }

    private fun isCoverDensityDisplay(displayId: Int): Boolean {
        val snapshot = CoverDisplayResolver.current() ?: return false
        return RuntimeFacts.isTargetSessionEligible() && displayId == snapshot.id
    }

    private fun taskFromFragment(fragment: Any, taskClass: Class<*>): Any? {
        if (taskClass.isInstance(fragment)) return fragment
        return runCatching {
            XposedHelpers.callMethod(fragment, "asTask")
        }.getOrNull()?.takeIf(taskClass::isInstance)
    }

    private fun pinFullModeTaskDensity(task: Any, taskUtilsClass: Class<*>): Int? {
        val snapshot = CoverDisplayResolver.current() ?: return null
        val displayId = displayIdOf(task) ?: return null
        if (!isCoverDensityDisplay(displayId)) return null
        val packageName = runCatching {
            XposedHelpers.callStaticMethod(taskUtilsClass, "getTargetPackageName", task) as? String
        }.getOrNull()
        val density = pinTaskDensity(task, packageName, displayId)
        if (density != null) {
            log("FULL app density pinned package=$packageName displayId=$displayId density=$density")
        }
        return density
    }

    private fun applyProfile(param: XC_MethodHook.MethodHookParam) {
        if (!RuntimeFacts.isTargetSessionEligible()) return
        val record = param.args.firstOrNull() ?: return
        val packageName = packageNameOf(record) ?: return
        val snapshot = CoverDisplayResolver.current() ?: return
        val options = (param.args.getOrNull(1) as? ActivityOptions)
            ?: ActivityOptions.makeBasic().also { param.args[1] = it }
        val launchDisplayId = runCatching {
            (XposedHelpers.callMethod(options, "getLaunchDisplayId") as? Number)?.toInt()
        }.getOrNull() ?: -1
        val recordDisplayId = displayIdOf(record)
        val onCover = launchDisplayId == snapshot.id ||
            recordDisplayId == snapshot.id ||
            (launchDisplayId < 0 && (recordDisplayId == null || recordDisplayId < 0))
        if (!onCover) return

        if (packageName == CONTROL_PACKAGE) {
            XposedHelpers.callMethod(
                options,
                "setLaunchWindowingMode",
                WINDOWING_MODE_FULLSCREEN
            )
            log("control activity forced fullscreen displayId=${snapshot.id}")
            return
        }

        val profile = CoverAppDisplayProfiles.profileFor(packageName)
        when {
            profile?.windowMode == AppWindowMode.POPUP ->
                applyPopup(options, record, profile, snapshot)
            else -> {
                val scaleProfile = profile?.takeUnless { it.isDefault }
                    ?: AppDisplayProfile(packageName = packageName)
                val hadCustomDensity = densityPinnedPackages.contains(packageName)
                if (scaleProfile.isDefault && !hadCustomDensity) return
                applyScale(options, record, scaleProfile, snapshot)
                if (scaleProfile.isDefault) {
                    densityPinnedPackages.remove(packageName)
                } else {
                    densityPinnedPackages.add(packageName)
                }
            }
        }
    }

    private fun applyScale(
        options: ActivityOptions,
        record: Any,
        profile: AppDisplayProfile,
        snapshot: CoverDisplaySnapshot
    ) {
        val context = systemContext
            ?: return log("app scale skipped package=${profile.packageName}: context unavailable")
        XposedHelpers.callMethod(options, "setLaunchWindowingMode", WINDOWING_MODE_FULLSCREEN)
        options.setLaunchBounds(null)
        val nativeDensity = nativeCoverDensity(context, snapshot)
        val density = densityForPercent(nativeDensity, profile.fullscreenPercent)
        val setter = customizedDensitySetter
        if (setter == null && densityUnavailableLogged.compareAndSet(false, true)) {
            log("customized cover density setter missing; pinning record/task density only")
        }
        setter?.let { method ->
            runCatching { method.invoke(options, density) }
                .onFailure { error ->
                    log("app scale options skipped package=${profile.packageName}: ${error.message}")
                }
        }
        pinRecordDensity(record, density)
        taskOf(record)?.let { pinRecordDensity(it, density) }
        log(
            "app scale prepared package=${profile.packageName} displayId=${snapshot.id} " +
                "percent=${profile.fullscreenPercent}% nativeDpi=$nativeDensity density=$density"
        )
    }

    private fun applyPopup(
        options: ActivityOptions,
        record: Any,
        profile: AppDisplayProfile,
        snapshot: CoverDisplaySnapshot
    ) {
        if (!isResizable(record)) {
            log("app popup skipped package=${profile.packageName}: activity not resizable")
            return
        }
        val safeArea = safeArea(snapshot)
            ?: PopupArea(0, 0, snapshot.width, snapshot.height)
        val calculatedBounds = PopupBoundsCalculator.calculate(
            safeArea,
            profile.popupWidthPercent,
            profile.popupHeightPercent
        )
        val bounds = Rect(
            calculatedBounds.left,
            calculatedBounds.top,
            calculatedBounds.right,
            calculatedBounds.bottom
        )
        runCatching {
            XposedHelpers.callMethod(options, "setLaunchWindowingMode", WINDOWING_MODE_FREEFORM)
            XposedHelpers.callMethod(options, "setLaunchBounds", bounds)
        }.onSuccess {
            log(
                "app popup prepared package=${profile.packageName} displayId=${snapshot.id} " +
                    "size=${profile.popupWidthPercent}%x${profile.popupHeightPercent}% " +
                    "bounds=${bounds.left},${bounds.top}-${bounds.right},${bounds.bottom}"
            )
        }.onFailure { error ->
            log("app popup skipped package=${profile.packageName}: ${error.message}")
        }
    }

    private fun safeArea(snapshot: CoverDisplaySnapshot): PopupArea? {
        val context = systemContext ?: return null
        return runCatching {
            val display = context.getSystemService(DisplayManager::class.java)
                ?.getDisplay(snapshot.id) ?: return@runCatching null
            val displayContext = context.createDisplayContext(display)
            val metrics = displayContext.getSystemService(WindowManager::class.java)
                ?.maximumWindowMetrics ?: return@runCatching null
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or
                    WindowInsets.Type.displayCutout() or
                    WindowInsets.Type.mandatorySystemGestures()
            )
            PopupArea(
                insets.left,
                insets.top,
                snapshot.width - insets.right,
                snapshot.height - insets.bottom
            ).takeIf { it.width > 0 && it.height > 0 }
        }.getOrNull()
    }

    private fun packageNameOf(record: Any): String? {
        val intentPackage = runCatching {
            (XposedHelpers.getObjectField(record, "intent") as? android.content.Intent)
                ?.component
                ?.packageName
        }.getOrNull()
        if (!intentPackage.isNullOrBlank()) return intentPackage
        return runCatching {
            XposedHelpers.getObjectField(record, "packageName") as? String
        }.getOrNull()?.takeIf(String::isNotBlank)
    }

    fun pinTaskDensity(task: Any, packageName: String?, displayId: Int): Int? {
        val profile = CoverAppDisplayProfiles.profileFor(packageName)?.takeUnless { it.isDefault }
            ?: return null
        val snapshot = CoverDisplayResolver.current() ?: return null
        if (displayId >= 0 && snapshot.id != displayId) return null
        val context = systemContext ?: return null
        val density = densityForPercent(nativeCoverDensity(context, snapshot), profile.fullscreenPercent)
        pinRecordDensity(task, density)
        return density
    }

    private fun pinRecordDensity(record: Any, density: Int) {
        listOf("mCustomizedCoverDensity", "customizedCoverDensity").forEach { fieldName ->
            runCatching { XposedHelpers.setIntField(record, fieldName, density) }
        }
        runCatching { XposedHelpers.callMethod(record, "setCustomizedCoverDensity", density) }
        runCatching {
            XposedHelpers.callMethod(record, "getRequestedOverrideConfiguration")
                .also { XposedHelpers.setIntField(it, "densityDpi", density) }
        }
        runCatching {
            XposedHelpers.callMethod(record, "getResolvedOverrideConfiguration")
                .also { XposedHelpers.setIntField(it, "densityDpi", density) }
        }
    }

    private fun displayIdOf(record: Any): Int? = runCatching {
        (XposedHelpers.callMethod(record, "getDisplayId") as? Number)?.toInt()
    }.getOrNull()?.takeIf { it >= 0 } ?: runCatching {
        XposedHelpers.getIntField(record, "mDisplayId")
    }.getOrNull()?.takeIf { it >= 0 }

    private fun nativeCoverDensity(context: Context, snapshot: CoverDisplaySnapshot): Int {
        val display = context.getSystemService(DisplayManager::class.java)
            ?.getDisplay(snapshot.id)
        val fromRealMetrics = display?.let { target ->
            val metrics = android.util.DisplayMetrics()
            target.getRealMetrics(metrics)
            metrics.densityDpi
        }
        val fromDisplayContext = display?.let { target ->
            runCatching {
                context.createDisplayContext(target).resources.displayMetrics.densityDpi
            }.getOrNull()
        }
        val resolved = resolveNativeCoverDensityDpi(
            fromRealMetrics = fromRealMetrics,
            fromDisplayContext = fromDisplayContext,
            lastGood = lastGoodCoverDensityDpi.get()
        )
        lastGoodCoverDensityDpi.set(resolved)
        return resolved
    }

    private fun taskOf(record: Any): Any? {
        val getter = record.javaClass.methods.firstOrNull {
            it.name == "getTask" && it.parameterCount == 0
        }
        if (getter != null) return runCatching { getter.invoke(record) }.getOrNull()
        return runCatching { XposedHelpers.getObjectField(record, "task") }.getOrNull()
    }

    private fun existingTaskState(record: Any): Boolean? {
        val getter = record.javaClass.methods.firstOrNull {
            it.name == "getTask" && it.parameterCount == 0
        }
        if (getter != null) return runCatching { getter.invoke(record) != null }.getOrNull()
        val taskField = runCatching { XposedHelpers.findField(record.javaClass, "task") }.getOrNull()
            ?: return null
        return runCatching { taskField.get(record) != null }.getOrNull()
    }

    private fun isResizable(record: Any): Boolean {
        val info = runCatching {
            XposedHelpers.getObjectField(record, "info") as? ActivityInfo
        }.getOrNull() ?: return false
        val resizeMode = runCatching {
            XposedHelpers.getIntField(info, "resizeMode")
        }.getOrNull() ?: return false
        return runCatching {
            XposedHelpers.callStaticMethod(
                ActivityInfo::class.java,
                "isResizeableMode",
                resizeMode
            ) as? Boolean
        }.getOrNull() == true
    }

    internal fun densityForPercent(nativeDensity: Int, percent: Int): Int =
        (nativeDensity * percent.coerceIn(
            AppDisplayProfileConfig.FULLSCREEN_MIN_PERCENT,
            AppDisplayProfileConfig.FULLSCREEN_MAX_PERCENT
        ) / 100f).roundToInt().coerceIn(200, 640)

    internal fun resolveNativeCoverDensityDpi(
        fromRealMetrics: Int?,
        fromDisplayContext: Int?,
        lastGood: Int
    ): Int =
        fromRealMetrics?.let(::sanitizeCoverDensityDpi)
            ?: fromDisplayContext?.let(::sanitizeCoverDensityDpi)
            ?: lastGood.takeIf { it in COVER_DENSITY_MIN_DPI..COVER_DENSITY_MAX_DPI }
            ?: COVER_DENSITY_FALLBACK_DPI

    internal fun sanitizeCoverDensityDpi(rawDpi: Int): Int? =
        rawDpi.takeIf { it in COVER_DENSITY_MIN_DPI..COVER_DENSITY_MAX_DPI }

    private fun log(message: String) {
        Log.i(TAG, message)
        XposedBridge.log("$TAG: $message")
    }
}

internal fun shouldPinFullModeTaskDensity(
    fullMode: Boolean,
    closed: Boolean,
    builtInTarget: Boolean,
    targetDisplayId: Int,
    taskDisplayId: Int
): Boolean = fullMode && closed && builtInTarget && targetDisplayId == 0 && taskDisplayId == 0

internal data class PopupArea(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

internal data class PopupBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
}

internal object PopupBoundsCalculator {
    fun calculate(
        safeArea: PopupArea,
        widthPercent: Int,
        heightPercent: Int
    ): PopupBounds {
        val width = (safeArea.width * widthPercent.coerceIn(50, 100) / 100f)
            .roundToInt()
            .coerceIn(minOf(320, safeArea.width), safeArea.width)
        val height = (safeArea.height * heightPercent.coerceIn(50, 100) / 100f)
            .roundToInt()
            .coerceIn(minOf(320, safeArea.height), safeArea.height)
        val left = safeArea.left + (safeArea.width - width) / 2
        val top = safeArea.top + (safeArea.height - height) / 2
        return PopupBounds(left, top, left + width, top + height)
    }
}
