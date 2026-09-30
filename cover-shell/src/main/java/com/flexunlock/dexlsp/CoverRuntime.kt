package com.flexunlock.dexlsp

import android.app.AndroidAppHelper
import android.content.BroadcastReceiver
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.View
import com.flexunlock.dexlsp.config.CoverDisplayConfig
import de.robv.android.xposed.XposedHelpers

internal fun coverUiSessionEligible(sessionEligible: Boolean, fullDexEnabled: Boolean): Boolean =
    sessionEligible && !fullDexEnabled

internal fun coverViewEligible(
    coverDisplay: Boolean,
    builtInTarget: Boolean,
    sessionEligible: Boolean
): Boolean = coverDisplay && builtInTarget && sessionEligible

internal object CoverRuntime {
    const val MODULE_PACKAGE = "com.flexunlock.dexlsp"
    const val SAMSUNG_LAUNCHER_PACKAGE = "com.sec.android.app.launcher"
    const val SYSTEM_UI_PACKAGE = "com.android.systemui"
    const val ONE_HAND_OPERATION_PACKAGE = "com.samsung.android.sidegesturepad"
    const val GOOD_LOCK_PACKAGE = "com.samsung.android.goodlock"
    const val LOCKSTAR_PACKAGE = "com.samsung.systemui.lockstar"
    const val DRESSROOM_PACKAGE = "com.samsung.android.app.dressroom"
    fun coverDisplaySnapshot(): CoverDisplaySnapshot? = CoverDisplayResolver.current()

    fun isCoverDisplay(displayId: Int?): Boolean = CoverDisplayResolver.matches(displayId)

    const val COVER_WORKSPACE_STATE_ACTION =
        "com.flexunlock.dexlsp.action.COVER_WORKSPACE_STATE"
    const val COVER_WORKSPACE_STATE_REQUEST_ACTION =
        "com.flexunlock.dexlsp.action.REQUEST_COVER_WORKSPACE_STATE"
    const val COVER_RECENTS_TRANSITION_ACTION =
        "com.flexunlock.dexlsp.action.COVER_RECENTS_TRANSITION"
    const val COVER_QUICK_PANEL_STATE_ACTION =
        "com.flexunlock.dexlsp.action.COVER_QUICK_PANEL_STATE"
    const val COVER_SHELL_TRANSITION_ACTION =
        "com.flexunlock.dexlsp.action.COVER_SHELL_TRANSITION"
    const val COVER_APP_TRANSITION_GEOMETRY_ACTION =
        "com.flexunlock.dexlsp.action.COVER_APP_TRANSITION_GEOMETRY"
    const val COVER_FIRST_FRAME_GATE_ACTION =
        "com.flexunlock.dexlsp.action.COVER_FIRST_FRAME_GATE"
    const val COVER_WIDGET_AUTH_ACTION =
        "com.flexunlock.dexlsp.action.COVER_WIDGET_AUTH"
    const val EXTRA_COVER_FIRST_FRAME_GATE_ARMED = "cover_first_frame_gate_armed"
    const val EXTRA_COVER_FIRST_FRAME_GATE_PENDING = "cover_first_frame_gate_pending"
    const val EXTRA_COVER_FIRST_FRAME_GATE_GENERATION = "cover_first_frame_gate_generation"
    const val EXTRA_COVER_WORKSPACE_VISIBLE = "cover_workspace_visible"
    const val EXTRA_COVER_WORKSPACE_EPOCH = "cover_workspace_epoch"
    const val EXTRA_COVER_WORKSPACE_SEQUENCE = "cover_workspace_sequence"
    const val EXTRA_COVER_WORKSPACE_SCREEN = "cover_workspace_screen"
    const val EXTRA_COVER_WORKSPACE_STATE = "cover_workspace_state"
    const val EXTRA_COVER_WORKSPACE_TRANSITION = "cover_workspace_transition"
    const val EXTRA_COVER_WORKSPACE_FOLDER = "cover_workspace_folder"
    const val EXTRA_COVER_WORKSPACE_REASON = "cover_workspace_reason"
    const val EXTRA_COVER_RECENTS_TRANSITION_ACTIVE = "cover_recents_transition_active"
    const val EXTRA_COVER_RECENTS_TRANSITION_REASON = "cover_recents_transition_reason"
    const val EXTRA_COVER_QUICK_PANEL_BLOCKS_RECENTS =
        "cover_quick_panel_blocks_recents"
    const val EXTRA_COVER_SHELL_TRANSITION_ACTIVE = "cover_shell_transition_active"
    const val EXTRA_COVER_SHELL_TRANSITION_REASON = "cover_shell_transition_reason"
    const val EXTRA_COVER_APP_TRANSITION_ACTIVE = "cover_app_transition_active"
    const val EXTRA_COVER_APP_TRANSITION_TOP = "cover_app_transition_top"
    const val EXTRA_COVER_APP_TRANSITION_REASON = "cover_app_transition_reason"

    private const val CLOSED_DEVICE_STATE = 0
    private const val DEVICE_STATE_PROPERTY = "debug.tracing.device_state"
    private const val DEVICE_STATE_CACHE_MS = 80L
    private const val TAG = "FlexUnlock-CoverShell"

    private data class DeviceStateSnapshot(
        val property: String,
        val identifier: Int?,
        val capturedAt: Long
    )

    @Volatile
    private var cachedDeviceState = DeviceStateSnapshot("", null, Long.MIN_VALUE)

    fun isCoverView(view: View): Boolean = coverViewEligible(
        isCoverDisplay(displayIdOf(view.context)),
        CoverDisplayResolver.isBuiltInTarget(),
        isCoverSessionEligible()
    )

    fun isBuiltInCoverView(view: View): Boolean =
        isCoverDisplay(displayIdOf(view.context)) && isBuiltInCoverSessionEligible()

    fun displayIdOf(context: Context?): Int? = runCatching {
        context?.display?.displayId
    }.getOrNull()

    fun contextForDisplay(base: Context, displayId: Int): Context? {
        if (displayId == Display.INVALID_DISPLAY) return null
        if (displayIdOf(base) == displayId) return base
        val displayManager = base.getSystemService(DisplayManager::class.java) ?: return null
        val display = displayManager.getDisplay(displayId) ?: return null
        return runCatching { base.createDisplayContext(display) }.getOrNull()
    }

    fun isDisplayAvailable(displayId: Int): Boolean {
        if (displayId == Display.INVALID_DISPLAY) return false
        if (displayId == Display.DEFAULT_DISPLAY) return true
        val application = AndroidAppHelper.currentApplication() ?: return false
        val displayManager = application.getSystemService(DisplayManager::class.java) ?: return false
        return displayManager.getDisplay(displayId) != null
    }

    fun isCoverDisplayOn(): Boolean {
        val displayId = CoverDisplayResolver.currentId() ?: return false
        val application = AndroidAppHelper.currentApplication() ?: return false
        val displayManager = application.getSystemService(DisplayManager::class.java) ?: return false
        return displayManager.getDisplay(displayId)?.state == Display.STATE_ON
    }

    fun isCoverSessionDefinitelyClosed(): Boolean {
        val state = deviceStateSnapshot()
        val property = state.property
        if (property.startsWith("0:") || property.contains("CLOSED", ignoreCase = true)) {
            return true
        }
        if (
            property.startsWith("2:") ||
            property.startsWith("3:") ||
            property.contains("HALF_OPENED", ignoreCase = true) ||
            property.contains("OPENED", ignoreCase = true)
        ) {
            return false
        }
        return state.identifier == CLOSED_DEVICE_STATE
    }

    fun isCoverSessionEligible(): Boolean {
        val target = CoverDisplayResolver.current() ?: return false
        if (target.type == DISPLAY_TYPE_HDMI || target.type == DISPLAY_TYPE_DISPLAY_PORT) return true
        return target.type == DISPLAY_TYPE_BUILT_IN && isFoldedSessionEligible()
    }

    fun isCoverUiSessionEligible(): Boolean = coverUiSessionEligible(
        isBuiltInCoverSessionEligible(),
        AndroidAppHelper.currentApplication()?.let {
            com.flexunlock.dexlsp.config.CoverDisplayConfig.readFullDex(it)
        } == true
    )

    fun isBuiltInCoverSessionEligible(): Boolean =
        CoverDisplayResolver.current()?.type == DISPLAY_TYPE_BUILT_IN && isFoldedSessionEligible()

    private fun isFoldedSessionEligible(): Boolean {
        val state = deviceStateSnapshot()
        val property = state.property
        if (property.startsWith("0:") || property.contains("CLOSED", ignoreCase = true)) {
            return true
        }
        if (
            property.startsWith("2:") ||
            property.startsWith("3:") ||
            property.contains("HALF_OPENED", ignoreCase = true) ||
            property.contains("OPENED", ignoreCase = true)
        ) {
            return false
        }
        return when (state.identifier) {
            CLOSED_DEVICE_STATE -> true
            null -> isCoverDisplayOn()
            else -> false
        }
    }

    private const val DISPLAY_TYPE_BUILT_IN = 1
    private const val DISPLAY_TYPE_HDMI = 2
    private const val DISPLAY_TYPE_DISPLAY_PORT = 6

    fun log(scope: String, message: String) {
        val text = "$scope: $message"
        Log.i(TAG, text)
    }

    /**
     * 模块内部广播接收器的权限门槛。
     *
     * Launcher / SystemUI / system_server 均为系统签名应用,且都持有 signature 级
     * android.permission.STATUS_BAR_SERVICE(实测两方 granted=true);
     * 第三方应用无法获得。注册 receiver 时传入该权限,系统会在投递阶段直接
     * 拒绝无权限的发送方,无需运行时反射。
     *
     * 注意:不能使用 android.permission.DUMP——三星 Launcher 实际不持有
     * DUMP(仅 SystemUI 持有),会导致 Launcher → SystemUI 的 workspace 广播
     * 被系统拦截,SystemUI 收不到"桌面可见"状态而隐藏顶部状态栏。
     */
    const val COVER_BROADCAST_PERMISSION = "android.permission.STATUS_BAR_SERVICE"

    /**
     * 校验跨进程广播的发送者是否为受信任的系统组件。
     *
     * 模块内部通过隐式广播在 Launcher / SystemUI / system_server 之间同步状态,
     * 但这些广播原本以 RECEIVER_EXPORTED 注册且无权限保护,任意第三方应用
     * 均可伪造 action 注入状态(DoS / UI 欺骗)。注册时已用
     * COVER_BROADCAST_PERMISSION(signature 级 DUMP)作为系统级门槛,此处
     * 再按 PendingResult 发送者 UID 做白名单冗余校验。发送者必须是:
     *  - system_server(SYSTEM_UID)
     *  - 三星 Launcher
     *  - SystemUI
     * UID 在部分系统版本/广播路径上不可用时(如 Android 16 隐式广播返回 -1),
     * 不再 fail-closed 拒绝——权限门槛已保证发送方持有系统签名权限,
     * 避免误伤合法模块内部同步。
     */
    fun isTrustedCoverSender(
        context: Context?,
        receiver: BroadcastReceiver
    ): Boolean {
        val contextRef = context ?: return true
        val sendingUid = sendingUidOf(receiver) ?: return true
        if (sendingUid == Process.SYSTEM_UID) return true
        val packageManager = contextRef.packageManager
        return TRUSTED_SENDER_PACKAGES.any { packageName ->
            runCatching { packageManager.getPackageUid(packageName, 0) }
                .getOrDefault(-1) == sendingUid
        }
    }

    /**
     * 获取广播发送者 UID。
     *
     * BroadcastReceiver.getSentFromUid() 为公开 API(API 34+),在 onReceive 中
     * 返回发送者 UID。部分系统版本/广播路径(如 Android 16 隐式广播)返回 -1,
     * 此时调用方按"无法判定但已通过系统权限门槛"处理。
     */
    private fun sendingUidOf(receiver: BroadcastReceiver): Int? {
        if (android.os.Build.VERSION.SDK_INT < 34) return null
        return runCatching {
            receiver.getSentFromUid().takeIf { it != -1 }
        }.getOrNull()
    }

    private val TRUSTED_SENDER_PACKAGES = setOf(
        SAMSUNG_LAUNCHER_PACKAGE,
        SYSTEM_UI_PACKAGE
    )

    private fun deviceStateSnapshot(): DeviceStateSnapshot {
        val now = SystemClock.uptimeMillis()
        cachedDeviceState.takeIf {
            it.capturedAt != Long.MIN_VALUE && now - it.capturedAt <= DEVICE_STATE_CACHE_MS
        }?.let {
            return it
        }
        return synchronized(this) {
            val current = cachedDeviceState
            if (
                current.capturedAt != Long.MIN_VALUE &&
                now - current.capturedAt <= DEVICE_STATE_CACHE_MS
            ) {
                current
            } else {
                DeviceStateSnapshot(
                    property = deviceStateProperty(),
                    identifier = currentDeviceStateIdentifier(),
                    capturedAt = now
                ).also { cachedDeviceState = it }
            }
        }
    }

    private fun deviceStateProperty(): String = runCatching {
        val systemProperties = Class.forName("android.os.SystemProperties")
        systemProperties.getMethod(
            "get",
            String::class.java,
            String::class.java
        ).invoke(null, DEVICE_STATE_PROPERTY, "") as? String ?: ""
    }.getOrDefault("")

    private fun currentDeviceStateIdentifier(): Int? = runCatching {
        val managerClass = Class.forName("android.hardware.devicestate.DeviceStateManagerGlobal")
        val manager = managerClass.getMethod("getInstance").invoke(null)
            ?: return@runCatching null
        val state = managerClass.getMethod("getDeviceState").invoke(manager)
            ?: return@runCatching null
        if (state is Number) {
            state.toInt()
        } else {
            val identifier = state.javaClass.methods.firstOrNull { method ->
                method.name == "getIdentifier" && method.parameterCount == 0
            }?.invoke(state)
            (identifier as? Number)?.toInt()
        }
    }.getOrNull()
}

// ---------------------------------------------------------------------------
// OneUI version detection + multi-candidate class lookup (7 / 8 / 8.5)
// ---------------------------------------------------------------------------

enum class CoverOneUiVersion { V7, V8, V85, UNKNOWN }

private const val ONRUI_COMPAT_SETTINGS = "flexunlock_oneui_compat"

const val MODULE_CONTROL_PERMISSION = "com.flexunlock.dexlsp.permission.CONTROL_COVER_DEX"
const val HIDE_COVER_HOME_STATUS_BAR_SETTINGS = "hide_cover_home_status_bar"
const val ACTION_SET_HIDE_COVER_HOME_STATUS_BAR =
    "com.flexunlock.dexlsp.action.SET_HIDE_COVER_HOME_STATUS_BAR"
const val EXTRA_HIDE_COVER_HOME_STATUS_BAR = "hide_cover_home_status_bar"

const val COVER_HOME_ICON_SIZE_SETTINGS = "cover_home_icon_size_px"
const val COVER_DRAWER_ICON_SIZE_SETTINGS = "cover_drawer_icon_size_px"
const val ACTION_SET_COVER_ICON_SIZE = "com.flexunlock.dexlsp.action.SET_COVER_ICON_SIZE"
const val EXTRA_COVER_ICON_SURFACE = "cover_icon_surface"
const val EXTRA_COVER_ICON_SIZE_PX = "cover_icon_size_px"
const val COVER_ICON_SURFACE_HOME = "home"
const val COVER_ICON_SURFACE_DRAWER = "drawer"
const val DEFAULT_COVER_HOME_ICON_SIZE_PX = 88
const val DEFAULT_COVER_DRAWER_ICON_SIZE_PX = 80
const val MIN_COVER_ICON_SIZE_PX = 64
const val MAX_COVER_ICON_SIZE_PX = 104

const val ACTION_SET_ONRUI_COMPAT = "com.flexunlock.dexlsp.action.SET_ONRUI_COMPAT"
const val EXTRA_ONRUI_COMPAT = "value"
const val ACTION_QS_FORCE_PORTRAIT = "com.flexunlock.dexlsp.action.QS_FORCE_PORTRAIT"
const val ACTION_QS_RESTORE_ORIENTATION = "com.flexunlock.dexlsp.action.QS_RESTORE_ORIENTATION"

@Volatile
var qsForcePortrait: Boolean = false

private val qsOrientationReceiverInstalled = java.util.concurrent.atomic.AtomicBoolean(false)

/**
 * Registers receivers (in the cover Launcher process) that force the desktop
 * to portrait while the QS panel is open on a rotated cover display, and
 * restore the lock state afterwards. The QS panel itself is portrait-only on
 * Samsung firmware; on a rotated display it fails to lay out correctly.
 */
fun installQsOrientationReceivers(context: android.content.Context) {
    if (!qsOrientationReceiverInstalled.compareAndSet(false, true)) return
    runCatching {
        val forceFilter = android.content.IntentFilter(ACTION_QS_FORCE_PORTRAIT)
        context.registerReceiver(
            object : android.content.BroadcastReceiver() {
                override fun onReceive(
                    context: android.content.Context,
                    intent: android.content.Intent
                ) {
                    qsForcePortrait = true
                    android.util.Log.i(
                        "FlexUnlock-CoverShell",
                        "QS force-portrait flag set"
                    )
                }
            },
            forceFilter,
            android.content.Context.RECEIVER_EXPORTED
        )
        val restoreFilter = android.content.IntentFilter(ACTION_QS_RESTORE_ORIENTATION)
        context.registerReceiver(
            object : android.content.BroadcastReceiver() {
                override fun onReceive(
                    context: android.content.Context,
                    intent: android.content.Intent
                ) {
                    qsForcePortrait = false
                    android.util.Log.i(
                        "FlexUnlock-CoverShell",
                        "QS restore-orientation flag cleared"
                    )
                }
            },
            restoreFilter,
            android.content.Context.RECEIVER_EXPORTED
        )
    }
}

private val oneUiCompatReceiverInstalled = java.util.concurrent.atomic.AtomicBoolean(false)

/**
 * Registers a receiver (in a privileged process such as SystemUI) that writes
 * the OneUI compatibility override into Settings.Global. The cover control app
 * itself cannot write secure settings (signature permission), so it broadcasts
 * the choice instead.
 */
fun installOneUiCompatReceiver(context: android.content.Context) {
    if (!oneUiCompatReceiverInstalled.compareAndSet(false, true)) return
    runCatching {
        android.util.Log.i(
            "FlexUnlock-CoverShell",
            "oneui compat receiver registering in " + context.packageName
        )
        val filter = android.content.IntentFilter(ACTION_SET_ONRUI_COMPAT)
        context.registerReceiver(
            object : android.content.BroadcastReceiver() {
                override fun onReceive(
                    context: android.content.Context,
                    intent: android.content.Intent
                ) {
                    val value = intent.getIntExtra(EXTRA_ONRUI_COMPAT, -1)
                    if (value !in setOf(0, 7, 8, 85)) return
                    runCatching {
                        val persisted = android.provider.Settings.Global.putInt(
                            context.contentResolver,
                            ONRUI_COMPAT_SETTINGS,
                            value
                        )
                        if (!persisted) {
                            android.util.Log.w(
                                "FlexUnlock-CoverShell",
                                "oneui compat override persistence rejected value=$value"
                            )
                            return@runCatching
                        }
                        android.util.Log.i(
                            "FlexUnlock-CoverShell",
                            "oneui compat override set to $value"
                        )
                        // Soft-restart SystemUI so the module hooks are
                        // re-registered against the newly selected OneUI
                        // class names (no full device reboot needed).
                        android.os.Handler(android.os.Looper.getMainLooper())
                            .postDelayed({
                                runCatching {
                                    android.os.Process.killProcess(
                                        android.os.Process.myPid()
                                    )
                                }
                            }, 1500L)
                    }
                }
            },
            filter,
            MODULE_CONTROL_PERMISSION,
            null,
            android.content.Context.RECEIVER_EXPORTED
        )
    }.onFailure {
        oneUiCompatReceiverInstalled.set(false)
        android.util.Log.w(
            "FlexUnlock-CoverShell",
            "oneui compat receiver unavailable",
            it
        )
    }
}

private val coverHomeStatusBarReceiverInstalled = java.util.concurrent.atomic.AtomicBoolean(false)

fun isCoverHomeStatusBarHidden(context: android.content.Context?): Boolean = runCatching {
    context != null &&
        android.provider.Settings.Global.getInt(
            context.contentResolver,
            HIDE_COVER_HOME_STATUS_BAR_SETTINGS,
            0
        ) != 0
}.getOrDefault(false)

/**
 * Persists the module-app preference from SystemUI, which owns WRITE_SECURE_SETTINGS.
 * The receiver requires the module's signature permission, so unrelated apps cannot
 * mutate injected UI state even though the receiver must be exported across processes.
 */
fun installCoverHomeStatusBarConfigReceiver(
    context: android.content.Context,
    onChanged: (Boolean) -> Unit
) {
    if (!coverHomeStatusBarReceiverInstalled.compareAndSet(false, true)) return
    runCatching {
        context.registerReceiver(
            object : android.content.BroadcastReceiver() {
                override fun onReceive(
                    receiverContext: android.content.Context,
                    intent: android.content.Intent
                ) {
                    if (
                        intent.action != ACTION_SET_HIDE_COVER_HOME_STATUS_BAR ||
                        !intent.hasExtra(EXTRA_HIDE_COVER_HOME_STATUS_BAR)
                    ) return
                    val hidden = intent.getBooleanExtra(
                        EXTRA_HIDE_COVER_HOME_STATUS_BAR,
                        false
                    )
                    val persisted = android.provider.Settings.Global.putInt(
                        receiverContext.contentResolver,
                        HIDE_COVER_HOME_STATUS_BAR_SETTINGS,
                        if (hidden) 1 else 0
                    )
                    if (!persisted) return
                    android.util.Log.i(
                        "FlexUnlock-CoverShell",
                        "cover Home status bar hidden=$hidden"
                    )
                    onChanged(hidden)
                }
            },
            android.content.IntentFilter(ACTION_SET_HIDE_COVER_HOME_STATUS_BAR),
            MODULE_CONTROL_PERMISSION,
            null,
            android.content.Context.RECEIVER_EXPORTED
        )
    }.onFailure {
        coverHomeStatusBarReceiverInstalled.set(false)
        android.util.Log.w(
            "FlexUnlock-CoverShell",
            "cover Home status bar receiver unavailable",
            it
        )
    }
}

private val coverIconSizeReceiverInstalled = java.util.concurrent.atomic.AtomicBoolean(false)

fun coverIconSizePx(context: android.content.Context?, surface: String): Int {
    val defaultValue = when (surface) {
        COVER_ICON_SURFACE_HOME -> DEFAULT_COVER_HOME_ICON_SIZE_PX
        COVER_ICON_SURFACE_DRAWER -> DEFAULT_COVER_DRAWER_ICON_SIZE_PX
        else -> return DEFAULT_COVER_HOME_ICON_SIZE_PX
    }
    val settingsKey = when (surface) {
        COVER_ICON_SURFACE_HOME -> COVER_HOME_ICON_SIZE_SETTINGS
        COVER_ICON_SURFACE_DRAWER -> COVER_DRAWER_ICON_SIZE_SETTINGS
        else -> return defaultValue
    }
    return runCatching {
        android.provider.Settings.Global.getInt(
            context?.contentResolver,
            settingsKey,
            defaultValue
        ).coerceIn(MIN_COVER_ICON_SIZE_PX, MAX_COVER_ICON_SIZE_PX)
    }.getOrDefault(defaultValue)
}

fun installCoverIconSizeConfigReceiver(
    context: android.content.Context,
    onChanged: (String, Int) -> Unit
) {
    if (!coverIconSizeReceiverInstalled.compareAndSet(false, true)) return
    runCatching {
        context.registerReceiver(
            object : android.content.BroadcastReceiver() {
                override fun onReceive(
                    receiverContext: android.content.Context,
                    intent: android.content.Intent
                ) {
                    if (intent.action != ACTION_SET_COVER_ICON_SIZE) return
                    val surface = intent.getStringExtra(EXTRA_COVER_ICON_SURFACE) ?: return
                    val settingsKey = when (surface) {
                        COVER_ICON_SURFACE_HOME -> COVER_HOME_ICON_SIZE_SETTINGS
                        COVER_ICON_SURFACE_DRAWER -> COVER_DRAWER_ICON_SIZE_SETTINGS
                        else -> return
                    }
                    val sizePx = intent.getIntExtra(
                        EXTRA_COVER_ICON_SIZE_PX,
                        Int.MIN_VALUE
                    )
                    if (sizePx !in MIN_COVER_ICON_SIZE_PX..MAX_COVER_ICON_SIZE_PX) return
                    val persisted = android.provider.Settings.Global.putInt(
                        receiverContext.contentResolver,
                        settingsKey,
                        sizePx
                    )
                    if (!persisted) return
                    android.util.Log.i(
                        "FlexUnlock-CoverShell",
                        "cover $surface icon size=${sizePx}px"
                    )
                    onChanged(surface, sizePx)
                }
            },
            android.content.IntentFilter(ACTION_SET_COVER_ICON_SIZE),
            MODULE_CONTROL_PERMISSION,
            null,
            android.content.Context.RECEIVER_EXPORTED
        )
    }.onFailure {
        coverIconSizeReceiverInstalled.set(false)
        android.util.Log.w(
            "FlexUnlock-CoverShell",
            "cover icon-size receiver unavailable",
            it
        )
    }
}

private fun readSystemPropertyCompat(name: String): String = runCatching {
    val systemProperties = Class.forName("android.os.SystemProperties")
    systemProperties.getMethod("get", String::class.java, String::class.java)
        .invoke(null, name, "") as? String ?: ""
}.getOrDefault("")

fun coverOneUiVersion(): CoverOneUiVersion {
    // manual override from Settings.Global (0=auto, 7, 8, 85)
    val manual = runCatching {
        android.provider.Settings.Global.getInt(
            AndroidAppHelper.currentApplication()?.contentResolver,
            ONRUI_COMPAT_SETTINGS
        )
    }.getOrDefault(0)
    return when (manual) {
        7 -> CoverOneUiVersion.V7
        8 -> CoverOneUiVersion.V8
        85 -> CoverOneUiVersion.V85
        else -> {
            val prop = readSystemPropertyCompat("ro.build.version.oneui")
            when {
                prop.startsWith("85") || prop.startsWith("805") -> CoverOneUiVersion.V85
                prop.startsWith("8") || prop.startsWith("80") -> CoverOneUiVersion.V8
                prop.startsWith("7") -> CoverOneUiVersion.V7
                else -> CoverOneUiVersion.UNKNOWN
            }
        }
    }
}

/**
 * Find the first available class among version-specific candidates.
 * Order is chosen by detected OneUI version so the native build is preferred;
 * fallback candidates keep the module working on other versions.
 */
fun findCompatClass(
    classLoader: ClassLoader,
    v85Names: List<String>,
    v8Names: List<String> = emptyList(),
    v7Names: List<String> = emptyList(),
    matches: (Class<*>) -> Boolean
): Class<*>? {
    val candidates = when (coverOneUiVersion()) {
        CoverOneUiVersion.V85 -> v85Names + v8Names + v7Names
        CoverOneUiVersion.V8 -> v8Names + v85Names + v7Names
        CoverOneUiVersion.V7 -> v7Names + v85Names + v8Names
        CoverOneUiVersion.UNKNOWN -> v85Names + v8Names + v7Names
    }.distinct()
    return candidates.firstNotNullOfOrNull { name ->
        XposedHelpers.findClassIfExists(name, classLoader)?.takeIf(matches)
    }
}

fun findCompatClass(
    classLoader: ClassLoader,
    v85Name: String,
    v8Name: String? = null,
    v7Name: String? = null
): Class<*>? = findCompatClass(
    classLoader = classLoader,
    v85Names = listOf(v85Name),
    v8Names = listOfNotNull(v8Name),
    v7Names = listOfNotNull(v7Name),
    matches = { true }
)

