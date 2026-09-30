package com.flexunlock.dexlsp.system.runtime

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.os.Binder
import android.provider.Settings
import android.util.Log
import android.view.inputmethod.EditorInfo
import com.flexunlock.dexlsp.CoverDisplayResolver
import com.flexunlock.dexlsp.config.CoverDisplayConfig
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import kotlin.math.roundToInt

internal fun fullDexImeOptions(current: Int, active: Boolean): Int =
    if (active) current or EditorInfo.IME_FLAG_NO_EXTRACT_UI else current

internal fun fullDexImeDisplayFlags(current: Int, active: Boolean): Int =
    if (active) current or (1 shl 17) else current

internal fun imeDensityForDisplay(displayInfo: Any, percent: Int): Int {
    val nativeDensity = XposedHelpers.getIntField(displayInfo, "logicalDensityDpi")
    return scaledImeDensity(nativeDensity, percent)
}

internal fun scaledImeDensity(nativeDensity: Int, percent: Int): Int =
    (nativeDensity * percent / 100f).roundToInt()

object FullDexImePolicy {
    private const val TAG = "FlexUnlock-FullDexIme"
    private const val SOFT_INPUT_STATE_MASK = 0x0f
    private const val SOFT_INPUT_STATE_HIDDEN = 2
    private const val SOFT_INPUT_STATE_ALWAYS_HIDDEN = 3
    private const val START_INPUT_FLAG_IS_TEXT_EDITOR = 2

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        installHoneyboardDisplayFlag(lpparam)
        installImeDisplayDensity(lpparam)
        installCoverImeWindowContextDensity(lpparam)
        installCoverDefaultImeGuard(lpparam)
        installCoverImeDisplayRouting(lpparam)
        installCoverImeRebind(lpparam)
        runCatching {
            val serviceClass = XposedHelpers.findClass(
                "com.android.server.inputmethod.InputMethodManagerService",
                lpparam.classLoader
            )
            serviceClass.declaredMethods
                .filter { method ->
                    (method.name == "startInputOrWindowGainedFocusWithResult" ||
                        method.name == "startInputOrWindowGainedFocusInternalLocked") &&
                        method.parameterTypes.contains(EditorInfo::class.java)
                }
                .forEach { method ->
                    val editorIndex = method.parameterTypes.indexOf(EditorInfo::class.java)
                    val clientIndex = method.parameterTypes.indexOfFirst {
                        it.name == "com.android.internal.inputmethod.IInputMethodClient"
                    }
                    method.isAccessible = true
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val editor = param.args.getOrNull(editorIndex) as? EditorInfo ?: return
                            val context = XposedHelpers.getObjectField(
                                param.thisObject,
                                "mContext"
                            ) as? Context ?: return
                            val client = param.args.getOrNull(clientIndex) ?: return
                            val controller = XposedHelpers.getObjectField(
                                param.thisObject,
                                "mClientController"
                            )
                            val binder = XposedHelpers.callMethod(client, "asBinder")
                            val state = XposedHelpers.callMethod(controller, "getClient", binder) ?: return
                            val displayId = XposedHelpers.getIntField(state, "mSelfReportedDisplayId")
                            if (!CoverDisplayResolver.matches(displayId)) return
                            val softInputIndex = editorIndex - 2
                            val softInputMode = param.args.getOrNull(softInputIndex) as? Int
                            if (softInputMode != null) {
                                val stateBits = softInputMode and SOFT_INPUT_STATE_MASK
                                if (stateBits == SOFT_INPUT_STATE_HIDDEN ||
                                    stateBits == SOFT_INPUT_STATE_ALWAYS_HIDDEN
                                ) {
                                    param.args[softInputIndex] =
                                        softInputMode and SOFT_INPUT_STATE_MASK.inv()
                                    log("cover IME soft-input hide state cleared displayId=$displayId")
                                }
                            }
                            val startInputFlagsIndex = editorIndex - 3
                            val startInputFlags = param.args.getOrNull(startInputFlagsIndex) as? Int
                            if (startInputFlags != null && editor.inputType != 0) {
                                param.args[startInputFlagsIndex] =
                                    startInputFlags or START_INPUT_FLAG_IS_TEXT_EDITOR
                                log("cover IME text-editor flag forced displayId=$displayId package=${editor.packageName}")
                            }
                            syncSelectedIme(param.thisObject, context, displayId)
                            if (CoverDisplayConfig.readFullDex(context)) {
                                editor.imeOptions = fullDexImeOptions(editor.imeOptions, true)
                            }
                        }
                    })
                }
            log("full DeX no-extract IME policy installed")
        }.onFailure { log("install failed: ${it.message}") }
    }

    private fun syncSelectedIme(service: Any, context: Context, displayId: Int) {
        val userId = runCatching {
            XposedHelpers.getIntField(service, "mCurrentImeUserId")
        }.getOrDefault(0)
        val desired = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.DEFAULT_INPUT_METHOD
        )?.takeIf { it.isNotBlank() } ?: return
        val userData = runCatching {
            XposedHelpers.callMethod(service, "getUserData", userId)
        }.getOrNull() ?: return
        val binding = XposedHelpers.getObjectField(userData, "mBindingController")
        val selected = XposedHelpers.getObjectField(binding, "mSelectedMethodId") as? String
        if (selected == desired) return
        runCatching {
            XposedHelpers.callMethod(service, "setInputMethodLocked", -1, 0, userId, desired)
            log("cover IME selection synchronized displayId=$displayId ime=$desired")
        }.onFailure { log("cover IME selection sync failed: ${it.message}") }
    }

    private fun installHoneyboardDisplayFlag(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            val binderServiceClass = XposedHelpers.findClass(
                "com.android.server.display.DisplayManagerService\$BinderService",
                lpparam.classLoader
            )
            binderServiceClass.declaredMethods
                .filter { method ->
                    method.name == "getDisplayInfo" &&
                        method.parameterTypes.contentEquals(
                            arrayOf(Int::class.javaPrimitiveType)
                        )
                }
                .forEach { method ->
                    method.isAccessible = true
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val displayId = (param.args.firstOrNull() as? Number)?.toInt() ?: return
                            if (!CoverDisplayResolver.matches(displayId)) return
                            val service = XposedHelpers.getObjectField(param.thisObject, "this\$0")
                            val context = XposedHelpers.getObjectField(service, "mContext") as? Context
                                ?: return
                            if (!CoverDisplayConfig.readFullDex(context)) return
                            val displayInfo = param.result ?: return
                            val copy = XposedHelpers.newInstance(displayInfo.javaClass, displayInfo)
                            val flags = XposedHelpers.getIntField(copy, "flags")
                            XposedHelpers.setIntField(copy, "flags", fullDexImeDisplayFlags(flags, true))
                            param.result = copy
                        }
                    })
                }
            log("full DeX IME display flag policy installed")
        }.onFailure { log("Honeyboard display flag install failed: ${it.message}") }
    }

    private fun installImeDisplayDensity(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            val binderServiceClass = XposedHelpers.findClass(
                "com.android.server.display.DisplayManagerService\$BinderService",
                lpparam.classLoader
            )
            XposedBridge.hookAllMethods(
                binderServiceClass,
                "getDisplayInfo",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val displayId = (param.args.firstOrNull() as? Number)?.toInt() ?: return
                        if (!CoverDisplayResolver.matches(displayId)) return
                        val service = XposedHelpers.getObjectField(param.thisObject, "this\$0")
                        val context = XposedHelpers.getObjectField(service, "mContext") as? Context ?: return
                        val packages = context.packageManager.getPackagesForUid(Binder.getCallingUid()) ?: return
                        val enabledImePackages = Settings.Secure.getString(
                            context.contentResolver,
                            Settings.Secure.ENABLED_INPUT_METHODS
                        )?.split(':')?.mapNotNull { it.substringBefore('/').takeIf(String::isNotBlank) }
                            ?: return
                        if (packages.none(enabledImePackages::contains)) return
                        val displayInfo = param.result ?: return
                        val percent = imeScalePercent(context)
                        val density = imeDensityForDisplay(displayInfo, percent)
                        val nativeDensity = XposedHelpers.getIntField(displayInfo, "logicalDensityDpi")
                        if (density == nativeDensity) return
                        val copy = XposedHelpers.newInstance(displayInfo.javaClass, displayInfo)
                        XposedHelpers.setIntField(copy, "logicalDensityDpi", density)
                        param.result = copy
                        log("IME display density applied displayId=$displayId density=$density package=${packages.firstOrNull(enabledImePackages::contains)}")
                    }
                }
            )
            log("IME display density policy installed")
        }.onFailure { log("IME display density policy unavailable: ${it.message}") }
    }

    private fun installCoverDefaultImeGuard(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            val serviceClass = XposedHelpers.findClass(
                "com.android.server.inputmethod.InputMethodManagerService",
                lpparam.classLoader
            )
            serviceClass.declaredMethods
                .filter { it.name == "setDefaultInputMethod" && it.parameterCount == 0 }
                .forEach { method ->
                    method.isAccessible = true
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val service = param.thisObject
                            val context = XposedHelpers.getObjectField(
                                service,
                                "mContext"
                            ) as? Context ?: return
                            if (CoverDisplayConfig.readFullDex(context)) return
                            val userId = runCatching {
                                XposedHelpers.getIntField(service, "mCurrentImeUserId")
                            }.getOrDefault(0)
                            val userData = runCatching {
                                XposedHelpers.callMethod(service, "getUserData", userId)
                            }.getOrNull() ?: return
                            val client = XposedHelpers.getObjectField(userData, "mCurClient")
                            val clientDisplayId = client?.let {
                                runCatching {
                                    XposedHelpers.getIntField(it, "mSelfReportedDisplayId")
                                }.getOrNull()
                            }
                            val binding = XposedHelpers.getObjectField(userData, "mBindingController")
                            val bindingDisplayId = runCatching {
                                XposedHelpers.getIntField(binding, "mDisplayIdToShowIme")
                            }.getOrNull()
                            val tokenDisplayId = runCatching {
                                XposedHelpers.getIntField(binding, "mCurTokenDisplayId")
                            }.getOrNull()
                            val displayId = clientDisplayId ?: bindingDisplayId ?: tokenDisplayId ?: return
                            if (!CoverDisplayResolver.matches(displayId)) return
                            param.setResult(null)
                            log("cover default IME reset suppressed displayId=$displayId")
                        }
                    })
                }
            log("cover default IME guard installed")
        }.onFailure { log("cover default IME guard unavailable: ${it.message}") }
    }

    private fun installCoverImeDisplayRouting(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            val serviceClass = XposedHelpers.findClass(
                "com.android.server.inputmethod.InputMethodManagerService",
                lpparam.classLoader
            )
            serviceClass.declaredMethods
                .filter { method ->
                    method.name == "computeImeDisplayIdForTargetInner" &&
                        method.parameterCount == 3 &&
                        method.returnType == Int::class.javaPrimitiveType
                }
                .forEach { method ->
                    method.isAccessible = true
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val displayId = (param.args.firstOrNull() as? Number)?.toInt() ?: return
                            if (!CoverDisplayResolver.matches(displayId)) return
                            val resolved = (param.result as? Number)?.toInt() ?: return
                            if (resolved == displayId) return
                            param.result = displayId
                            log("cover IME display routed displayId=$displayId resolved=$resolved")
                        }
                    })
                }
            log("cover IME display routing installed")
        }.onFailure { log("cover IME display routing unavailable: ${it.message}") }
    }

    private fun installCoverImeRebind(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            val serviceClass = XposedHelpers.findClass(
                "com.android.server.inputmethod.InputMethodManagerService",
                lpparam.classLoader
            )
            serviceClass.declaredMethods
                .filter { it.name == "setInputMethodLocked" }
                .forEach { method ->
                    method.isAccessible = true
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val service = param.thisObject
                            val context = XposedHelpers.getObjectField(service, "mContext") as? Context
                                ?: return
                            val userId = runCatching {
                                XposedHelpers.getIntField(service, "mCurrentImeUserId")
                            }.getOrDefault(0)
                            val userData = runCatching {
                                XposedHelpers.callMethod(service, "getUserData", userId)
                            }.getOrNull() ?: return
                            val binding = XposedHelpers.getObjectField(userData, "mBindingController")
                            val client = XposedHelpers.getObjectField(userData, "mCurClient")
                            val displayId = client?.let {
                                runCatching {
                                    XposedHelpers.getIntField(it, "mSelfReportedDisplayId")
                                }.getOrNull()
                            } ?: runCatching {
                                XposedHelpers.getIntField(binding, "mDisplayIdToShowIme")
                            }.getOrNull() ?: runCatching {
                                XposedHelpers.getIntField(binding, "mCurTokenDisplayId")
                            }.getOrNull() ?: return
                            if (!CoverDisplayResolver.matches(displayId)) return
                            val selected = XposedHelpers.getObjectField(binding, "mSelectedMethodId") as? String
                            val current = XposedHelpers.getObjectField(binding, "mCurMethod")
                            if (selected.isNullOrBlank() || current != null) return
                            val desired = Settings.Secure.getString(
                                context.contentResolver,
                                Settings.Secure.DEFAULT_INPUT_METHOD
                            )?.takeIf { it.isNotBlank() }
                            if (desired != null && desired != selected) {
                                XposedHelpers.callMethod(
                                    service,
                                    "setInputMethodLocked",
                                    -1,
                                    0,
                                    userId,
                                    desired
                                )
                                log("cover IME rebind selection corrected displayId=$displayId ime=$desired")
                                return
                            }
                            XposedHelpers.callMethod(binding, "bindCurrentMethod")
                            log("cover IME rebound displayId=$displayId ime=$selected")
                        }
                    })
                }
            serviceClass.declaredMethods
                .filter { it.name == "startInputUncheckedLocked" }
                .forEach { method ->
                    method.isAccessible = true
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val service = param.thisObject
                            val userId = runCatching {
                                XposedHelpers.getIntField(service, "mCurrentImeUserId")
                            }.getOrDefault(0)
                            val userData = runCatching {
                                XposedHelpers.callMethod(service, "getUserData", userId)
                            }.getOrNull() ?: return
                            val binding = XposedHelpers.getObjectField(userData, "mBindingController")
                            val displayId = runCatching {
                                XposedHelpers.getIntField(binding, "mDisplayIdToShowIme")
                            }.getOrNull() ?: return
                            if (!CoverDisplayResolver.matches(displayId)) return
                            val selected = XposedHelpers.getObjectField(binding, "mSelectedMethodId") as? String
                            if (selected.isNullOrBlank() || selected.startsWith("com.samsung.android.honeyboard/")) return
                            val current = XposedHelpers.getObjectField(binding, "mCurId") as? String
                            val hasConnection = XposedHelpers.getBooleanField(binding, "mHasMainConnection")
                            if (current == selected && hasConnection) return
                            XposedHelpers.callMethod(binding, "unbindCurrentMethod")
                            val rebound = XposedHelpers.callMethod(binding, "bindCurrentMethod")
                            param.result = rebound
                            log("cover third-party IME bind forced displayId=$displayId ime=$selected")
                        }
                    })
                }
            log("cover IME rebind installed")
        }.onFailure { log("cover IME rebind unavailable: ${it.message}") }
    }

    private fun installCoverImeWindowContextDensity(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            val listenerClass = XposedHelpers.findClass(
                "com.android.server.wm.WindowContextListenerController",
                lpparam.classLoader
            )
            XposedBridge.hookAllMethods(
                listenerClass,
                "registerWindowContainerListener",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.args.size != 6 || (param.args[3] as? Int) != 2011) return
                        val process = param.args[0] ?: return
                        val appInfo = XposedHelpers.getObjectField(process, "mInfo") as? ApplicationInfo
                            ?: return
                        val packageName = appInfo.packageName
                        val container = param.args[2] ?: return
                        val display = XposedHelpers.callMethod(container, "getDisplayContent") ?: return
                        val displayId = XposedHelpers.getIntField(display, "mDisplayId")
                        if (!CoverDisplayResolver.matches(displayId)) return
                        val service = XposedHelpers.getObjectField(container, "mWmService")
                        val context = XposedHelpers.getObjectField(service, "mContext") as? Context ?: return
                        val percent = imeScalePercent(context)
                        if (percent == 100) return
                        val displayInfo = XposedHelpers.getObjectField(display, "mDisplayInfo")
                        val density = imeDensityForDisplay(displayInfo, percent)
                        if (density <= 0) return
                        val override = Configuration().apply { densityDpi = density }
                        XposedHelpers.callMethod(container, "onRequestedOverrideConfigurationChanged", override)
                        log("cover IME window density applied displayId=$displayId density=$density package=$packageName")
                    }
                }
            )
            log("cover IME window density policy installed")
        }.onFailure { log("cover IME window density unavailable: ${it.message}") }
    }

    private fun log(message: String) {
        Log.i(TAG, message)
        XposedBridge.log("$TAG: $message")
    }

    private fun imeScalePercent(context: Context): Int {
        if (!CoverDisplayConfig.readImeCompact(context)) return 100
        return CoverDisplayConfig.readImeCompactPercent(context)
    }
}
