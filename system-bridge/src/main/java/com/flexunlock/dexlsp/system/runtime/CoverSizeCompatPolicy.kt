package com.flexunlock.dexlsp.system.runtime

import android.util.Log
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

object CoverSizeCompatPolicy {
    private const val TAG = "FlexUnlock-SystemBridge"
    private const val HOOK_MARKER = "flexunlockCoverSizeCompatBypass"
    private const val HOOK_PACKAGE_MARKER = "flexunlockCoverSizeCompatPackage"
    private const val TASK_UTILS_CLASS = "com.android.server.wm.CoverLauncherTaskUtils"
    private const val TASK_CLASS = "com.android.server.wm.Task"

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        val taskUtilsClass = XposedHelpers.findClassIfExists(
            TASK_UTILS_CLASS,
            lpparam.classLoader
        ) ?: return log("cover size-compat policy unavailable: task utils missing")
        val taskClass = XposedHelpers.findClassIfExists(
            TASK_CLASS,
            lpparam.classLoader
        ) ?: return log("cover size-compat policy unavailable: Task missing")

        runCatching {
            val methods = taskUtilsClass.declaredMethods.filter { method ->
                method.name == "applyCustomizedDensityScaleIfNeeded" &&
                    method.parameterTypes.contentEquals(
                        arrayOf(
                            taskClass,
                            Int::class.javaPrimitiveType,
                            Int::class.javaPrimitiveType
                        )
                    )
            }
            methods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!RuntimeFacts.isTargetSessionEligible()) return
                        val task = param.args.firstOrNull() ?: return
                        val displayId = (param.args.getOrNull(1) as? Number)?.toInt() ?: return
                        if (!com.flexunlock.dexlsp.CoverDisplayResolver.matches(displayId)) return
                        val packageName = runCatching {
                            XposedHelpers.callStaticMethod(
                                taskUtilsClass,
                                "getTargetPackageName",
                                task
                            ) as? String
                        }.getOrNull()
                        val pinnedDensity = CoverAppLaunchProfilePolicy.pinTaskDensity(
                            task,
                            packageName,
                            displayId
                        )
                        val coverPolicyEnabled = runCatching {
                            XposedHelpers.getBooleanField(
                                task,
                                "mIsCoverLauncherPolicyEnabled"
                            )
                        }.getOrDefault(false)
                        val fullMode = DisplayChannelModePolicy.isAppliedFull() &&
                            RuntimeFacts.isClosed() &&
                            com.flexunlock.dexlsp.CoverDisplayResolver.isBuiltInTarget() &&
                            displayId == 0
                        if (!coverPolicyEnabled && !fullMode) return

                        if (coverPolicyEnabled) {
                            XposedHelpers.setBooleanField(
                                task,
                                "mIsCoverLauncherPolicyEnabled",
                                false
                            )
                        }
                        param.setObjectExtra(HOOK_MARKER, true)
                        param.setObjectExtra(HOOK_PACKAGE_MARKER, packageName)
                        param.setObjectExtra(HOOK_DENSITY_MARKER, pinnedDensity)
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.getObjectExtra(HOOK_MARKER) != true) return
                        val packageName = param.getObjectExtra(HOOK_PACKAGE_MARKER) as? String
                        val pinnedDensity = param.getObjectExtra(HOOK_DENSITY_MARKER) as? Int
                        val task = param.args.firstOrNull() ?: return
                        val displayId = (param.args.getOrNull(1) as? Number)?.toInt() ?: return
                        val fullMode = DisplayChannelModePolicy.isAppliedFull() &&
                            RuntimeFacts.isClosed() &&
                            com.flexunlock.dexlsp.CoverDisplayResolver.isBuiltInTarget() &&
                            displayId == 0
                        if (!fullMode) {
                            XposedHelpers.setBooleanField(
                                task,
                                "mIsCoverLauncherPolicyEnabled",
                                true
                            )
                        }
                        if (pinnedDensity != null) {
                            XposedHelpers.setIntField(task, "mCustomizedCoverDensity", pinnedDensity)
                            runCatching {
                                val resolved = XposedHelpers.callMethod(
                                    task,
                                    "getResolvedOverrideConfiguration"
                                )
                                XposedHelpers.setIntField(resolved, "densityDpi", pinnedDensity)
                            }
                        }
                        val customizedDensity = runCatching {
                            XposedHelpers.getIntField(task, "mCustomizedCoverDensity")
                        }.getOrNull()
                        val sizeCompatPolicy = runCatching {
                            XposedHelpers.getObjectField(task, "mSizeCompatPolicy")
                        }.getOrNull()
                        log(
                            "display-$displayId size-compat bypassed package=$packageName " +
                                "pinnedDensity=$pinnedDensity " +
                                "customizedDensity=$customizedDensity fullMode=$fullMode " +
                                "sizeCompat=${sizeCompatPolicy != null}"
                        )
                    }
                })
            }
            log("display-1 cover-policy-enabled size-compat bypass installed methods=${methods.size}")
        }.onFailure { error ->
            log("cover size-compat policy unavailable: ${error.message}")
        }
    }

    private fun log(message: String) {
        Log.i(TAG, message)
        XposedBridge.log("$TAG: $message")
    }

    private const val HOOK_DENSITY_MARKER = "flexunlockCoverSizeCompatDensity"
}
