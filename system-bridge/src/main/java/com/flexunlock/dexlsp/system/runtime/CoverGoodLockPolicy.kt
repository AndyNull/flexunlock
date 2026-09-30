package com.flexunlock.dexlsp.system.runtime

import android.app.ActivityOptions
import android.content.Intent
import android.util.Log
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

internal fun shouldForceTaskLaunchToTarget(
    explicitDisplayId: Int?,
    sourceDisplayId: Int?,
    targetDisplayId: Int
): Boolean = when {
    explicitDisplayId != null && explicitDisplayId >= 0 -> explicitDisplayId == targetDisplayId
    else -> sourceDisplayId == null || sourceDisplayId < 0 || sourceDisplayId == targetDisplayId
}

object CoverGoodLockPolicy {
    private const val TAG = "FlexUnlock-SystemBridge"
    private const val ACTIVITY_STARTER = "com.android.server.wm.ActivityStarter"
    private const val FORCE_LOG_INTERVAL_MS = 2000L

    @Volatile
    private var lastForceLogAt = 0L

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            val activityStarter = XposedHelpers.findClass(
                ACTIVITY_STARTER,
                lpparam.classLoader
            )
            val methods = activityStarter.declaredMethods.filter { method ->
                method.name == "shouldLaunchingForCoverLauncherDelayed" &&
                    method.returnType == Boolean::class.javaPrimitiveType &&
                    method.parameterCount == 4
            }
            methods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val target = param.args.firstOrNull()
                        val targetPackage = target?.let { stringField(it, "packageName") }
                        log(
                            "CoverLauncher delay check closed=" + RuntimeFacts.isClosed() +
                                " target=$targetPackage"
                        )
                        if (!RuntimeFacts.isTargetSessionEligible()) return
                        val starter = param.thisObject ?: return
                        val fullDex = CoverLaunchAllowlist.fullDexEnabled()
                        if (
                            objectField(starter, "mOriginalIntentForCoverLauncher") == null &&
                            !fullDex
                        ) {
                            log("CoverLauncher delay check: no original cover intent")
                            return
                        }
                        if (targetPackage == null) {
                            log("CoverLauncher delay check: no target package")
                            return
                        }
                        val callingPackage = objectField(starter, "mRequest")
                            ?.let { objectField(it, "callingPackage") as? String }
                        val sourcePackage = objectField(starter, "mSourceRecord")
                            ?.let { stringField(it, "packageName") }
                        val allowed = CoverLaunchAllowlist.allowsCoverLaunch(
                            targetPackage,
                            callingPackage,
                            sourcePackage
                        )
                        log(
                            "CoverLauncher delay check: allow=$allowed " +
                                "target=$targetPackage calling=$callingPackage " +
                                "source=$sourcePackage"
                        )
                        if (!allowed) return
                        val sourceDisplayId = objectField(starter, "mSourceRecord")
                            ?.let(::recordDisplayId)
                        val coverDisplayId = com.flexunlock.dexlsp.CoverDisplayResolver
                            .interactionDisplayId(sourceDisplayId)
                            ?: return
                        val launchDisplayId =
                            activityOptionsDisplayId(objectField(starter, "mOptions"))
                                ?: activityOptionsDisplayId(
                                    objectField(starter, "mRequest")?.let {
                                        objectField(it, "activityOptions")
                                    }
                                )
                                ?: activityOptionsDisplayId(
                                    objectField(starter, "mLastRequest")?.let {
                                        objectField(it, "activityOptions")
                                    }
                                )
                        if (launchDisplayId != null && launchDisplayId != coverDisplayId) {
                            logForceThrottled(
                                "CoverLauncher route retained explicit display $launchDisplayId " +
                                    "target=$targetPackage"
                            )
                            return
                        }
                        // 三星默认把从 Cover 启动的目标放到内屏(display 0)再拦截,
                        // 这里放行并强制把启动目标重定向到已解析的外屏。
                        param.result = false
                        runCatching {
                            val service = objectField(starter, "mService")
                                ?: return@runCatching
                            val root = objectField(service, "mRootWindowContainer")
                                ?: return@runCatching
                            val displayContent = XposedHelpers.callMethod(
                                root,
                                "getDisplayContent",
                                coverDisplayId
                            ) ?: return@runCatching
                            val displayArea = runCatching {
                                XposedHelpers.callMethod(
                                    displayContent,
                                    "getDefaultTaskDisplayArea"
                                )
                            }.getOrElse {
                                objectField(displayContent, "defaultTaskDisplayArea")
                            } ?: return@runCatching
                            XposedHelpers.setObjectField(
                                starter,
                                "mPreferredTaskDisplayArea",
                                displayArea
                            )
                            // ActivityOptions 层强制使用当前已解析的外屏 ID
                            // (三星的 display 决策优先读 ActivityOptions,仅设
                            //  mPreferredTaskDisplayArea 不够)。
                            runCatching {
                                val request = objectField(starter, "mRequest")
                                    ?: objectField(starter, "mLastRequest")
                                val options = request?.let {
                                    objectField(it, "activityOptions")
                                }
                                if (options != null) {
                                    XposedHelpers.callMethod(
                                        options,
                                        "setLaunchDisplayId",
                                        coverDisplayId
                                    )
                                }
                            }
                            log(
                                "CoverLauncher launch redirected to display $coverDisplayId " +
                                    "target=$targetPackage"
                            )
                        }.onFailure { err ->
                            log("CoverLauncher redirect failed: ${err.message}")
                        }
                        // 把目标包注入三星 CoverLauncher 允许列表(mCoverLauncherAllowList)
                        // 与 multistar cover 显示列表(mMultiStarEnabledAppsList),
                        // 与 multistar 同机制:避免"ask to open phone"强制走内屏,
                        // 并允许 cover 会话中直接显示 app。
                        runCatching {
                            val service = objectField(starter, "mService")
                                ?: return@runCatching
                            val controller = objectField(
                                service,
                                "mCoverLauncherPropertyController"
                            ) ?: return@runCatching
                            val property = objectField(controller, "mProperty")
                                ?: return@runCatching
                            val allowList = objectField(
                                property,
                                "mCoverLauncherAllowList"
                            ) ?: return@runCatching
                            val userList = XposedHelpers.callMethod(
                                allowList,
                                "get",
                                0
                            ) as? java.util.List<*> ?: return@runCatching
                            if (!userList.contains(targetPackage)) {
                                @Suppress("UNCHECKED_CAST")
                                (userList as java.util.List<String>).add(targetPackage)
                                log(
                                    "CoverLauncher allowList injected " +
                                        "target=$targetPackage"
                                )
                            }
                            // multistar cover 显示列表(user 0 的包列表)
                            val multistarList = objectField(
                                property,
                                "mMultiStarEnabledAppsList"
                            ) as? java.util.concurrent.ConcurrentHashMap<*, *>
                            val msUserList = multistarList?.get(0) as? java.util.List<*>
                            if (msUserList != null && !msUserList.contains(targetPackage)) {
                                @Suppress("UNCHECKED_CAST")
                                (msUserList as java.util.List<String>).add(targetPackage)
                                log(
                                    "multistar cover list injected " +
                                        "target=$targetPackage"
                                )
                            }
                        }.onFailure { err ->
                            log("CoverLauncher allowList inject failed: ${err.message}")
                        }
                        log(
                            "display-$coverDisplayId CoverLauncher delay bypassed " +
                                "target=$targetPackage"
                        )
                    }
                })
            }
            // TaskLaunchParamsModifier.onCalculate 是三星决定 task 放置 display 的
            // 决策点。来源为外屏(display 1)的启动,强制把结果 display area 设为
            // display 1,避免三星 CoverLauncher 策略把任务推到内屏。
            runCatching {
                val tlpClass = XposedHelpers.findClass(
                    "com.android.server.wm.TaskLaunchParamsModifier",
                    lpparam.classLoader
                )
                XposedBridge.hookAllMethods(tlpClass, "onCalculate", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.args.size < 9) return
                        val target = runCatching {
                            val ar = param.args[2]
                            val info = ar?.let { XposedHelpers.getObjectField(it, "info") }
                            info?.let { XposedHelpers.getObjectField(it, "packageName") }
                        }.getOrNull()
                        // 仅当前目标显示器会话生效，避免干预内屏启动。
                        if (!RuntimeFacts.isTargetSessionEligible()) return
                        val targetPkg = target as? String
                        val activityName = runCatching {
                            val record = param.args[2]
                            val info = record?.let { XposedHelpers.getObjectField(it, "info") }
                            (info?.let { XposedHelpers.getObjectField(it, "name") } as? String)
                                ?: runCatching {
                                    (
                                        XposedHelpers.callMethod(
                                            record,
                                            "getActivityComponent"
                                        ) as? android.content.ComponentName
                                    )?.className
                                }.getOrNull()
                        }.getOrNull()
                        val sourcePkg = runCatching {
                            param.args.getOrNull(3)?.let {
                                XposedHelpers.getObjectField(it, "packageName")
                            }
                        }.getOrNull() as? String
                        val callingPkg = runCatching {
                            param.args.getOrNull(5)?.let {
                                XposedHelpers.getObjectField(it, "callingPackage")
                            }
                        }.getOrNull() as? String
                        val allowed = CoverLaunchAllowlist.shouldForceCoverTaskDisplay(
                            targetPkg,
                            activityName,
                            sourcePkg,
                            callingPkg
                        )
                        if (!allowed) return
                        val explicitDisplayId = activityOptionsDisplayId(param.args.getOrNull(4))
                        val sourceDisplayId = runCatching {
                            (XposedHelpers.callMethod(
                                param.args.getOrNull(3),
                                "getDisplayId"
                            ) as Number).toInt()
                        }.getOrNull()
                        val coverDisplayId = com.flexunlock.dexlsp.CoverDisplayResolver
                            .interactionDisplayId(sourceDisplayId)
                            ?: return
                        if (!shouldForceTaskLaunchToTarget(
                                explicitDisplayId,
                                sourceDisplayId,
                                coverDisplayId
                            )
                        ) return
                        val result = param.args[8] ?: return
                        val supervisor = runCatching {
                            objectField(param.thisObject, "mSupervisor")
                        }.getOrNull()
                        if (supervisor == null) return
                        val service = runCatching {
                            objectField(supervisor, "mService")
                        }.getOrNull()
                        if (service == null) return
                        val root = runCatching {
                            objectField(service, "mRootWindowContainer")
                        }.getOrNull()
                        if (root == null) return
                        val displayContent = runCatching {
                            XposedHelpers.callMethod(
                                root,
                                "getDisplayContent",
                                coverDisplayId
                            )
                        }.getOrNull()
                        if (displayContent == null) return
                        val displayInfo = runCatching {
                            XposedHelpers.callMethod(displayContent, "getDisplayInfo")
                        }.getOrNull()
                        if (displayInfo != null) {
                            val logicalWidth = intField(displayInfo, "logicalWidth") ?: 0
                            val logicalHeight = intField(displayInfo, "logicalHeight") ?: 0
                            if (logicalWidth <= 0 || logicalHeight <= 0) return
                        }
                        val area = runCatching {
                            XposedHelpers.callMethod(
                                displayContent,
                                "getDefaultTaskDisplayArea"
                            )
                        }.getOrElse {
                            objectField(displayContent, "defaultTaskDisplayArea")
                        }
                        if (area == null) return
                        XposedHelpers.setObjectField(
                            result,
                            "mPreferredTaskDisplayArea",
                            area
                        )
                        logForceThrottled(
                            "TaskLaunchParams forced display $coverDisplayId " +
                                "target=$targetPkg activity=$activityName"
                        )
                    }
                })
            }.onFailure { error ->
                log("TaskLaunchParams display route unavailable: ${error.message}")
            }
            // 三星通过 LaunchedFromAppsCoverLauncherTask 标记决定 cover 会话中
            // 是否允许 app 显示(桌面/AppsCoverLauncher 启动=true,Good Lock 启动=false)。
            // 强制标记为 true,让 Good Lock 启动的插件也能在外屏显示。
            runCatching {
                val starterClass = XposedHelpers.findClass(
                    "com.android.server.wm.ActivityStarter",
                    lpparam.classLoader
                )
                fun forceCoverLauncherFlag(param: XC_MethodHook.MethodHookParam, field: String) {
                    if (!RuntimeFacts.isTargetSessionEligible()) return
                    val task = param.args.firstOrNull() ?: return
                    runCatching {
                        val info = XposedHelpers.getObjectField(task, "mActivityInfo")
                        val pkg = info?.let { XposedHelpers.getObjectField(it, "packageName") }
                                as? String
                        if (!CoverLaunchAllowlist.contains(pkg)) return
                        XposedHelpers.setObjectField(task, field, true)
                        log("CoverLauncher flag forced $field=true target=$pkg")
                    }
                }
                XposedHelpers.findAndHookMethod(
                    starterClass,
                    "setLaunchedAppsCoverLauncher",
                    XposedHelpers.findClass("com.android.server.wm.Task", lpparam.classLoader),
                    Boolean::class.javaPrimitiveType,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            forceCoverLauncherFlag(param, "mIsLaunchedFromAppsCoverLauncher")
                        }
                    }
                )
                XposedHelpers.findAndHookMethod(
                    starterClass,
                    "setLaunchedMultistarCoverLauncher",
                    XposedHelpers.findClass("com.android.server.wm.Task", lpparam.classLoader),
                    Boolean::class.javaPrimitiveType,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            forceCoverLauncherFlag(param, "mIsLaunchedFromMultistarCoverLauncher")
                        }
                    }
                )
            }.onFailure { error ->
                log("CoverLauncher flag route unavailable: ${error.message}")
            }
            runCatching {
                val controllerClass = XposedHelpers.findClass(
                    "com.android.server.wm.CoverLauncherPropertyController",
                    lpparam.classLoader
                )
                XposedBridge.hookAllMethods(
                    controllerClass,
                    "isPackageEnabledForCoverLauncher",
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (!RuntimeFacts.isTargetSessionEligible()) return
                            val pkg = param.args.firstOrNull() as? String ?: return
                            if (!CoverLaunchAllowlist.contains(pkg)) return
                            param.result = true
                        }
                    }
                )
                log("CoverLauncher package eligibility hook installed")
            }.onFailure { error ->
                log("CoverLauncher package eligibility unavailable: ${error.message}")
            }
            log("Good Lock CoverLauncher policy installed methods=${methods.size}")
        }.onFailure { error ->
            log("Good Lock CoverLauncher policy unavailable: ${error.message}")
        }
        installIndependentDisplayTaskRoute(lpparam)
    }

    private fun installIndependentDisplayTaskRoute(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            val starterClass = XposedHelpers.findClass(ACTIVITY_STARTER, lpparam.classLoader)
            val methods = starterClass.declaredMethods.filter { method ->
                method.name == "startActivityInner" ||
                    (method.name == "setInitialState" &&
                        method.parameterTypes.firstOrNull()?.name ==
                        "com.android.server.wm.ActivityRecord" &&
                        method.parameterTypes.getOrNull(1) == ActivityOptions::class.java)
            }
            methods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (method.name == "setInitialState") {
                            splitCrossDisplayInitial(param)
                        } else {
                            splitCrossDisplayRequest(param)
                        }
                    }
                })
            }
            log("cross-display task isolation installed methods=${methods.size}")
        }.onFailure { error ->
            log("cross-display task isolation unavailable: ${error.message}")
        }
    }

    private fun splitCrossDisplayRequest(param: XC_MethodHook.MethodHookParam) {
        val starter = param.thisObject ?: return
        val request = objectField(starter, "mRequest") ?: return
        val record = param.args.firstOrNull()
        val intent = (record?.let { objectField(it, "intent") }
            ?: objectField(request, "intent")) as? Intent ?: return
        val options = param.args.getOrNull(5)
            ?: objectField(request, "activityOptions")
            ?: objectField(starter, "mOptions")
        val targetDisplayId = activityOptionsDisplayId(options) ?: return
        if (targetDisplayId <= 0) return
        if (intent.flags and Intent.FLAG_ACTIVITY_MULTIPLE_TASK != 0) return
        if (
            intent.hasCategory(Intent.CATEGORY_HOME) ||
            intent.hasCategory(Intent.CATEGORY_SECONDARY_HOME)
        ) return
        val sourceRecord = objectField(starter, "mSourceRecord")
        val sourceDisplayId = sourceRecord?.let(::recordDisplayId)
        if (sourceDisplayId == null || sourceDisplayId < 0 || sourceDisplayId == targetDisplayId) return
        intent.addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        log(
            "cross-display task isolated package=${intent.component?.packageName} " +
                "from=${sourceDisplayId ?: -1} to=$targetDisplayId"
        )
    }

    private fun splitCrossDisplayInitial(param: XC_MethodHook.MethodHookParam) {
        val starter = param.thisObject ?: return
        val record = param.args.firstOrNull() ?: return
        val options = param.args.getOrNull(1) ?: return
        val targetDisplayId = activityOptionsDisplayId(options) ?: return
        if (targetDisplayId <= 0) return
        val intent = objectField(record, "intent") as? Intent ?: return
        if (intent.flags and Intent.FLAG_ACTIVITY_MULTIPLE_TASK != 0) return
        if (
            intent.hasCategory(Intent.CATEGORY_HOME) ||
            intent.hasCategory(Intent.CATEGORY_SECONDARY_HOME)
        ) return
        val sourceDisplayId = objectField(starter, "mSourceRecord")?.let(::recordDisplayId)
        if (sourceDisplayId == null || sourceDisplayId < 0 || sourceDisplayId == targetDisplayId) return
        intent.addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        log(
            "cross-display task isolated package=${intent.component?.packageName} " +
                "from=${sourceDisplayId ?: -1} to=$targetDisplayId"
        )
    }

    private fun recordDisplayId(record: Any): Int? {
        intField(record, "displayId")?.let { return it }
        intField(record, "mDisplayId")?.let { return it }
        val displayContent = objectField(record, "mDisplayContent")
            ?: objectField(record, "task")?.let { taskDisplayContent(it) }
        return displayContent?.let { intField(it, "mDisplayId") }
    }

    private fun taskDisplayContent(task: Any): Any? =
        objectField(task, "mDisplayContent")
            ?: objectField(task, "mRootTask")?.let { objectField(it, "mDisplayContent") }

    private fun objectField(instance: Any, name: String): Any? =
        runCatching { XposedHelpers.getObjectField(instance, name) }.getOrNull()

    private fun stringField(instance: Any, name: String): String? =
        objectField(instance, name) as? String

    private fun intField(instance: Any, name: String): Int? =
        runCatching { XposedHelpers.getIntField(instance, name) }.getOrNull()

    private fun activityOptionsDisplayId(options: Any?): Int? {
        val value = options ?: return null
        return runCatching {
            when (value) {
                is ActivityOptions -> value.launchDisplayId
                else -> (XposedHelpers.callMethod(value, "getLaunchDisplayId") as? Number)?.toInt()
            }
        }.getOrNull()?.takeIf { it >= 0 }
    }

    private fun log(message: String) {
        Log.i(TAG, message)
        XposedBridge.log("$TAG: $message")
    }

    private fun logForceThrottled(message: String) {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastForceLogAt < FORCE_LOG_INTERVAL_MS) return
        lastForceLogAt = now
        Log.i(TAG, message)
    }
}
