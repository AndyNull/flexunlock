package com.flexunlock.dexlsp.system.runtime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.os.Handler
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.DisplayCutout
import com.flexunlock.dexlsp.CoverDisplayResolver
import com.flexunlock.dexlsp.CoverQsMode
import com.flexunlock.dexlsp.CoverQsModeConfig
import com.flexunlock.dexlsp.CoverQsTransition
import com.flexunlock.dexlsp.DisplayMetricsSnapshot
import com.flexunlock.dexlsp.config.CoverDisplayCandidateStatus
import com.flexunlock.dexlsp.config.CoverDisplayConfig
import com.flexunlock.dexlsp.config.CoverDisplayMode
import com.flexunlock.dexlsp.config.CoverDisplayOverride
import com.flexunlock.dexlsp.config.CoverDisplayStatus
import com.flexunlock.dexlsp.config.decodeSystemUpdatePackageStates
import com.flexunlock.dexlsp.config.displayModesMatch
import com.flexunlock.dexlsp.config.encodeSystemUpdatePackageStates
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.XposedBridge
import java.util.concurrent.atomic.AtomicLong

internal fun isUsableDisplayId(displayId: Int): Boolean = displayId >= 0

internal fun displayOverrideMatchesTarget(
    storedDisplayId: Int,
    storedUniqueId: String?,
    targetDisplayId: Int,
    targetUniqueId: String?
): Boolean = storedUniqueId?.takeIf(String::isNotBlank)?.let { it == targetUniqueId }
    ?: (storedDisplayId < 0 || storedDisplayId == targetDisplayId)

internal fun displayMetricsAspectMatches(
    width: Int,
    height: Int,
    nativeWidth: Int,
    nativeHeight: Int
): Boolean {
    val expected = width.toLong() * nativeHeight.coerceAtLeast(1)
    val actual = height.toLong() * nativeWidth.coerceAtLeast(1)
    return kotlin.math.abs(expected - actual) * 50L <= expected
}

internal fun restoredDisplayMetrics(snapshot: DisplayMetricsSnapshot): CoverDisplayOverride =
    if (snapshot.displayId == 0) {
        CoverDisplayOverride(
            snapshot.initialWidth,
            snapshot.initialHeight,
            snapshot.initialDensity
        )
    } else {
        CoverDisplayOverride(snapshot.baseWidth, snapshot.baseHeight, snapshot.baseDensity)
    }

internal fun fullDexRequiresOriginalChannel(
    enabled: Boolean,
    stableOriginal: Boolean,
    externalTarget: Boolean = false
): Boolean = enabled && !stableOriginal && !externalTarget

internal fun fullQsRequiresFullDexDisabled(mode: CoverQsMode, fullDexEnabled: Boolean): Boolean =
    mode == CoverQsMode.FULL && fullDexEnabled

internal fun isExternalDisplayType(type: Int): Boolean = type == 2 || type == 5 || type == 6

internal data class HalfModeRegion(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

internal fun resolveHalfModeRegion(
    width: Int,
    height: Int,
    cutouts: List<HalfModeRegion>
): HalfModeRegion? {
    val candidates = cutouts.flatMap { cutout ->
        buildList {
            if (cutout.left <= 0 && cutout.right in 1 until width) {
                add(HalfModeRegion(cutout.right, 0, width, height))
            }
            if (cutout.right >= width && cutout.left in 1 until width) {
                add(HalfModeRegion(0, 0, cutout.left, height))
            }
            if (cutout.top <= 0 && cutout.bottom in 1 until height) {
                add(HalfModeRegion(0, cutout.bottom, width, height))
            }
            if (cutout.bottom >= height && cutout.top in 1 until height) {
                add(HalfModeRegion(0, 0, width, cutout.top))
            }
        }
    }
    return candidates
        .filter { region -> cutouts.none { cutout -> regionsIntersect(region, cutout) } }
        .maxByOrNull { it.width.toLong() * it.height }
}

private fun regionsIntersect(first: HalfModeRegion, second: HalfModeRegion): Boolean =
    first.left < second.right && first.right > second.left &&
        first.top < second.bottom && first.bottom > second.top

internal fun halfModeDisplayOffset(fullSize: Int, cropStart: Int, cropSize: Int): Int =
    cropStart - (fullSize - cropSize) / 2

internal fun isSameDisplayTarget(
    previous: com.flexunlock.dexlsp.CoverDisplaySnapshot?,
    current: com.flexunlock.dexlsp.CoverDisplaySnapshot?
): Boolean {
    if (previous == null || current == null) return false
    return previous.uniqueId?.takeIf(String::isNotBlank)?.let { it == current.uniqueId }
        ?: (previous.id == current.id)
}

internal fun roundedRectDisplayShapeSpec(width: Int, height: Int, radius: Int): String {
    val corner = radius.coerceIn(0, minOf(width, height) / 2)
    if (corner == 0) return "M0,0 H$width V$height H0 Z"
    val right = width - corner
    val bottom = height - corner
    return "M$corner,0 H$right A$corner,$corner 0 0 1 $width,$corner " +
        "V$bottom A$corner,$corner 0 0 1 $right,$height H$corner " +
        "A$corner,$corner 0 0 1 0,$bottom V$corner A$corner,$corner 0 0 1 $corner,0 Z"
}

internal fun externalDisplayMetrics(
    physicalWidth: Int,
    physicalHeight: Int,
    densityDpi: Int
): CoverDisplayOverride = CoverDisplayOverride(
    physicalWidth,
    physicalHeight,
    densityDpi
)

internal fun displayMetricsMatch(
    currentWidth: Int,
    currentHeight: Int,
    currentDensity: Int,
    target: CoverDisplayOverride
): Boolean = currentWidth == target.width &&
    currentHeight == target.height &&
    currentDensity == target.densityDpi

internal fun supportedDisplayMode(
    modes: Array<Display.Mode>,
    requested: CoverDisplayMode
): Display.Mode? = modes.firstOrNull { mode ->
    displayModesMatch(
        CoverDisplayMode(mode.physicalWidth, mode.physicalHeight, mode.refreshRate),
        requested
    )
}

internal fun preferredDisplayModeOrNull(
    width: Int,
    height: Int,
    refreshRate: Float
): CoverDisplayMode? = CoverDisplayMode(width, height, refreshRate).takeIf {
    width > 0 && height > 0 && refreshRate.isFinite() && refreshRate > 0f
}

internal fun supportedDisplayModeValues(
    modes: Array<Display.Mode>
): List<CoverDisplayMode> = modes.mapNotNull { mode ->
    preferredDisplayModeOrNull(mode.physicalWidth, mode.physicalHeight, mode.refreshRate)
}.distinct()

internal object CoverDisplayConfigurationController {
    private const val TAG = "FlexUnlock-SystemBridge"
    private const val CONTROL_PERMISSION =
        "com.flexunlock.dexlsp.permission.CONTROL_COVER_DEX"
    private const val COVER_SHELL_PERMISSION = "android.permission.STATUS_BAR_SERVICE"
    private const val SAMSUNG_LAUNCHER_PACKAGE = "com.sec.android.app.launcher"
    @Volatile
    private var context: Context? = null

    @Volatile
    private var initialized = false

    @Volatile
    private var windowManagerService: Any? = null

    @Volatile
    private var mainHandler: Handler? = null

    private val statusRevision = AtomicLong(0L)

    @Volatile
    private var lastAppliedOverride: Pair<Int, CoverDisplayOverride?>? = null

    @Volatile
    private var halfModeDisplayId = -1

    @Volatile
    private var halfModeOriginalRoundedCorners: Any? = null

    @Volatile
    private var halfModeWindowGeometry: Triple<Int, Int, Int>? = null

    private var halfModeWindowDisplayShape: Any? = null
    private var halfModeWindowRoundedCorners: Any? = null

    @Volatile
    private var halfModeDisplayShapeCache: Any? = null

    fun install(classLoader: ClassLoader) {
        val windowManagerClass = XposedHelpers.findClass(
            "com.android.server.wm.WindowManagerService",
            classLoader
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

        val displayContentClass = XposedHelpers.findClass(
            "com.android.server.wm.DisplayContent",
            classLoader
        )
        XposedBridge.hookAllMethods(
            displayContentClass,
            "updateBaseDisplayMetrics",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    syncDisplayGeometry(param.thisObject)
                }
            }
        )
        val halfModeGeometryHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (halfModeDisplayId >= 0 &&
                    XposedHelpers.getIntField(param.thisObject, "mDisplayId") == halfModeDisplayId
                ) {
                    applyHalfModeWindowGeometry(param.thisObject)
                }
            }
        }
        listOf(
            "calculateDisplayCutoutForRotation",
            "calculateRoundedCornersForRotation"
        ).forEach { method ->
            XposedBridge.hookAllMethods(displayContentClass, method, halfModeGeometryHook)
        }
        // Samsung inlines shape calculation into calls to this per-display cache.
        val shapeHooks = XposedBridge.hookAllMethods(
            XposedHelpers.findClass("com.android.server.wm.utils.RotationCache", classLoader),
            "getOrCompute",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (param.thisObject === halfModeDisplayShapeCache) {
                        param.args[1] = halfModeWindowDisplayShape
                    }
                }
            }
        )
        log("half cover shape cache hook installed methods=${shapeHooks.size}")
        log("cover display metrics controller installed")
    }

    fun initialize(systemContext: Context, handler: Handler) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            context = systemContext
            mainHandler = handler
            val filter = IntentFilter().apply {
                addAction(CoverDisplayConfig.ACTION_SET)
                addAction(CoverDisplayConfig.ACTION_REQUEST_STATUS)
                addAction(CoverDisplayConfig.ACTION_SET_METRICS)
                addAction(CoverDisplayConfig.ACTION_SET_REFRESH_MODE)
                addAction(CoverDisplayConfig.ACTION_SET_FULL_DEX)
                addAction(CoverDisplayConfig.ACTION_SET_HALF_MODE)
                addAction(CoverDisplayConfig.ACTION_SET_CAMERA_MODE)
                addAction(CoverDisplayConfig.ACTION_SET_LOCKSCREEN_TIMEOUT)
                addAction(CoverDisplayConfig.ACTION_SET_SYSTEM_UPDATE_BLOCKED)
                addAction(CoverDisplayConfig.ACTION_SET_IME_COMPACT)
                addAction(CoverQsModeConfig.ACTION_SET)
            }
            systemContext.registerReceiver(
                object : BroadcastReceiver() {
                    override fun onReceive(receiverContext: Context?, intent: Intent?) {
                        when (intent?.action) {
                            CoverDisplayConfig.ACTION_SET -> applyRequestedIdentity(intent)
                            CoverDisplayConfig.ACTION_REQUEST_STATUS -> {
                                CoverDisplayResolver.refreshConfiguration("status-request")
                                publishStatus("request")
                            }
                            CoverDisplayConfig.ACTION_SET_METRICS -> applyRequestedMetrics(intent)
                            CoverDisplayConfig.ACTION_SET_REFRESH_MODE -> applyRequestedRefreshMode(intent)
                            CoverDisplayConfig.ACTION_SET_FULL_DEX -> applyRequestedFullDex(intent)
                            CoverDisplayConfig.ACTION_SET_HALF_MODE -> applyRequestedHalfMode(intent)
                            CoverDisplayConfig.ACTION_SET_CAMERA_MODE -> applyRequestedCameraMode(intent)
                            CoverDisplayConfig.ACTION_SET_LOCKSCREEN_TIMEOUT ->
                                applyRequestedLockscreenTimeout(intent)
                            CoverDisplayConfig.ACTION_SET_SYSTEM_UPDATE_BLOCKED ->
                                applyRequestedSystemUpdateBlocked(intent)
                            CoverDisplayConfig.ACTION_SET_IME_COMPACT -> applyRequestedImeCompact(intent)
                            CoverQsModeConfig.ACTION_SET -> applyRequestedQsMode(intent)
                        }
                    }
                },
                filter,
                CONTROL_PERMISSION,
                handler,
                Context.RECEIVER_EXPORTED
            )
            initialized = true
            CoverDisplayResolver.refreshConfiguration("configuration-initialize")
            applyStoredMetrics()
            publishStatus("initialize")
            if (fullDexRequiresOriginalChannel(
                    CoverDisplayConfig.readFullDex(systemContext),
                    CoverQsModeConfig.readTransaction(systemContext).isStableOriginal,
                    isExternalDisplayType(CoverDisplayResolver.current()?.type ?: 0)
                )
            ) {
                handler.post { restoreOriginalChannelForFullDex() }
            }
            if (CoverDisplayConfig.readHalfMode(systemContext)) {
                handler.post {
                    if (halfModeDisplayId < 0 && !applyHalfModeGeometry()) persistHalfMode(false)
                    publishStatus("half-mode-restore")
                }
            }
            log("cover display configuration channel initialized")
        }
    }

    fun onResolverChanged(
        previous: com.flexunlock.dexlsp.CoverDisplaySnapshot? = null,
        current: com.flexunlock.dexlsp.CoverDisplaySnapshot? = null
    ) {
        if (initialized) {
            val systemContext = context ?: return
            val target = (CoverDisplayResolver.resolution()
                as? com.flexunlock.dexlsp.CoverDisplayResolution.Resolved)?.snapshot
            if (CoverDisplayConfig.readHalfMode(systemContext) &&
                (target == null || isExternalDisplayType(target.type))
            ) {
                if (!persistHalfMode(false)) return
                if (!restoreHalfModeGeometry()) {
                    persistHalfMode(true)
                    return
                }
            }
            if (target?.type != 1 || !isSameDisplayTarget(previous, current)) {
                lastAppliedOverride = null
                applyStoredMetrics()
            }
            publishStatus("resolver-change")
        }
    }

    private fun applyRequestedMetrics(intent: Intent) {
        val systemContext = context ?: return
        val transaction = CoverQsModeConfig.readTransaction(systemContext)
        if (!transaction.state.isStable()) return log("cover display metrics rejected during QS transition")
        val snapshot = (CoverDisplayResolver.resolution()
            as? com.flexunlock.dexlsp.CoverDisplayResolution.Resolved)?.snapshot
            ?: return log("cover display metrics rejected: target unavailable")
        val externalTarget = isExternalDisplayType(snapshot.type)
        val raw = intent.getStringExtra(CoverDisplayConfig.EXTRA_METRICS) ?: return
        val value = CoverDisplayConfig.decodeOverride(raw)
        if (value == null && raw != CoverDisplayConfig.AUTO_VALUE) {
            return log("cover display metrics rejected: malformed payload")
        }
        if (value != null && !validMetrics(value, transaction.isStableFull && !externalTarget)) {
            return log("cover display metrics rejected: out of range value=$value")
        }
        val settingsKey = if (transaction.isStableFull && !externalTarget) {
            CoverDisplayConfig.SETTINGS_FULL_QS_OVERRIDE_KEY
        } else {
            CoverDisplayConfig.SETTINGS_OVERRIDE_KEY
        }
        if (!Settings.Global.putString(
                systemContext.contentResolver,
                settingsKey,
                CoverDisplayConfig.encodeOverride(value)
            )
        ) {
            return log("cover display metrics persistence rejected")
        }
        if (transaction.isStableFull && !externalTarget) {
            DisplayChannelModePolicy.onCanvasOverrideChanged()
        } else {
            lastAppliedOverride = null
            applyStoredMetrics()
        }
        publishStatus("metrics-update")
    }

    private fun applyRequestedRefreshMode(intent: Intent) {
        val systemContext = context ?: return
        val raw = intent.getStringExtra(CoverDisplayConfig.EXTRA_REFRESH_MODE) ?: return
        val requested = CoverDisplayConfig.decodeDisplayMode(raw)
        if (requested == null && raw != CoverDisplayConfig.AUTO_VALUE) {
            return log("external display mode rejected: malformed payload")
        }
        val snapshot = (CoverDisplayResolver.resolution()
            as? com.flexunlock.dexlsp.CoverDisplayResolution.Resolved)?.snapshot ?: return
        if (!isExternalDisplayType(snapshot.type)) {
            return log("external display mode rejected: target type=${snapshot.type}")
        }
        val display = systemContext.getSystemService(DisplayManager::class.java)
            ?.getDisplay(snapshot.id) ?: return
        val mode = requested?.let { supportedDisplayMode(display.supportedModes, it) }
        if (requested != null && mode == null) {
            return log("external display mode rejected: unsupported value=$requested")
        }
        runCatching {
            val managerClass = XposedHelpers.findClass(
                "android.hardware.display.DisplayManagerGlobal",
                null
            )
            val manager = XposedHelpers.callStaticMethod(managerClass, "getInstance")
            managerClass.getMethod(
                "setUserPreferredDisplayMode",
                Int::class.javaPrimitiveType,
                Display.Mode::class.java
            ).invoke(manager, snapshot.id, mode)
        }.onSuccess {
            lastAppliedOverride = null
            applyStoredMetrics(mode)
            mainHandler?.postDelayed({
                lastAppliedOverride = null
                applyStoredMetrics()
                publishStatus("external-display-mode-settled")
            }, 800L)
            publishStatus("external-display-mode-update")
            log("external display mode updated display=${snapshot.id} value=${requested ?: "system"}")
        }.onFailure { error ->
            log("external display mode apply failed display=${snapshot.id}: ${error.message}")
        }
    }

    private fun applyRequestedFullDex(intent: Intent) {
        val systemContext = context ?: return
        if (!intent.hasExtra(CoverDisplayConfig.EXTRA_FULL_DEX)) return
        val enabled = intent.getBooleanExtra(CoverDisplayConfig.EXTRA_FULL_DEX, false)
        val previous = CoverDisplayConfig.readFullDex(systemContext)
        if (!persistFullDex(enabled)) return
        if (fullDexRequiresOriginalChannel(
                enabled,
                CoverQsModeConfig.readTransaction(systemContext).isStableOriginal,
                isExternalDisplayType(CoverDisplayResolver.current()?.type ?: 0)
            )
        ) {
            DisplayChannelModePolicy.update(CoverQsMode.ORIGINAL) { success ->
                if (!success) {
                    persistFullDex(previous)
                    publishStatus("full-dex-channel-rejected")
                    return@update log("full cover DeX rejected: ORIGINAL channel restore failed")
                }
                finishFullDexChange(enabled, launcherAlreadyRestarted = true)
            }
            return
        }
        finishFullDexChange(enabled, launcherAlreadyRestarted = false)
    }

    private fun applyRequestedHalfMode(intent: Intent) {
        if (!intent.hasExtra(CoverDisplayConfig.EXTRA_HALF_MODE)) return
        val enabled = intent.getBooleanExtra(CoverDisplayConfig.EXTRA_HALF_MODE, false)
        if (enabled) {
            if (!persistHalfMode(true)) return
            if (!applyHalfModeGeometry()) {
                persistHalfMode(false)
                return
            }
        } else {
            if (!persistHalfMode(false)) return
            if (!restoreHalfModeGeometry()) {
                persistHalfMode(true)
                return
            }
            lastAppliedOverride = null
            applyStoredMetrics()
        }
        publishStatus("half-mode-update")
        log("half cover mode updated enabled=$enabled")
    }

    private fun applyHalfModeGeometry(): Boolean = runCatching {
        val snapshot = (CoverDisplayResolver.resolution()
            as? com.flexunlock.dexlsp.CoverDisplayResolution.Resolved)?.snapshot
            ?: error("cover display unavailable")
        check(!isExternalDisplayType(snapshot.type)) { "external display has no cover cutout" }
        if (halfModeDisplayId == snapshot.id) return@runCatching
        val service = windowManagerService ?: error("WindowManagerService unavailable")
        val root = XposedHelpers.getObjectField(service, "mRoot")
        val displayContent = XposedHelpers.callMethod(root, "getDisplayContent", snapshot.id)
            ?: error("display ${snapshot.id} unavailable")
        val width = XposedHelpers.getIntField(displayContent, "mInitialDisplayWidth")
        val height = XposedHelpers.getIntField(displayContent, "mInitialDisplayHeight")
        val density = XposedHelpers.getIntField(displayContent, "mInitialDisplayDensity")
        val cutout = XposedHelpers.getObjectField(displayContent, "mInitialDisplayCutout")
            as? DisplayCutout ?: error("display cutout unavailable")
        val region = resolveHalfModeRegion(
            width,
            height,
            cutout.boundingRects.map { HalfModeRegion(it.left, it.top, it.right, it.bottom) }
        ) ?: error("no rectangular area excludes the display cutout")
        val displayManagerInternal = XposedHelpers.getObjectField(service, "mDisplayManagerInternal")
        halfModeOriginalRoundedCorners = XposedHelpers.getObjectField(
            displayContent,
            "mBaseRoundedCorners"
        )
        halfModeDisplayId = snapshot.id
        XposedHelpers.callMethod(displayManagerInternal, "setDisplayScalingDisabled", snapshot.id, true)
        applyAtomicMetrics(
            snapshot.id,
            CoverDisplayOverride(region.width, region.height, density)
        )
        applyHalfModeWindowGeometry(displayContent)
        XposedHelpers.callMethod(
            displayManagerInternal,
            "setDisplayOffsets",
            snapshot.id,
            halfModeDisplayOffset(width, region.left, region.width),
            halfModeDisplayOffset(height, region.top, region.height)
        )
        log("half cover geometry applied display=${snapshot.id} full=${width}x$height region=$region")
    }.onFailure { error ->
        log("half cover geometry apply failed: ${error.message}")
        restoreHalfModeGeometry()
    }.isSuccess

    private fun restoreHalfModeGeometry(): Boolean {
        val displayId = halfModeDisplayId.takeIf(::isUsableDisplayId) ?: return true
        return runCatching {
            val service = windowManagerService ?: error("WindowManagerService unavailable")
            val root = XposedHelpers.getObjectField(service, "mRoot")
            val displayContent = XposedHelpers.callMethod(root, "getDisplayContent", displayId)
                ?: error("display $displayId unavailable")
            val displayManagerInternal = XposedHelpers.getObjectField(service, "mDisplayManagerInternal")
            halfModeOriginalRoundedCorners?.let { corners ->
                XposedHelpers.setObjectField(displayContent, "mBaseRoundedCorners", corners)
            }
            halfModeDisplayId = -1
            halfModeOriginalRoundedCorners = null
            halfModeWindowGeometry = null
            halfModeDisplayShapeCache = null
            halfModeWindowDisplayShape = null
            halfModeWindowRoundedCorners = null
            XposedHelpers.callMethod(displayManagerInternal, "setDisplayOffsets", displayId, 0, 0)
            XposedHelpers.callMethod(displayManagerInternal, "setDisplayScalingDisabled", displayId, false)
            XposedHelpers.callMethod(windowManagerInterface(), "clearForcedDisplaySize", displayId)
        }.onFailure { error ->
            log("half cover geometry restore failed display=$displayId: ${error.message}")
        }.isSuccess
    }

    private fun applyHalfModeWindowGeometry(displayContent: Any) {
        val width = XposedHelpers.getIntField(displayContent, "mBaseDisplayWidth")
        val height = XposedHelpers.getIntField(displayContent, "mBaseDisplayHeight")
        val roundedCorners = XposedHelpers.getObjectField(displayContent, "mBaseRoundedCorners")
        val topLeft = XposedHelpers.callMethod(roundedCorners, "getRoundedCorner", 0)
        val radius = (XposedHelpers.callMethod(topLeft, "getRadius") as Number).toInt()
        val geometry = Triple(width, height, radius)
        if (halfModeWindowGeometry != geometry) {
            halfModeWindowDisplayShape = XposedHelpers.callStaticMethod(
                Class.forName("android.view.DisplayShape"),
                "fromSpecString",
                roundedRectDisplayShapeSpec(width, height, radius),
                1f,
                width,
                height
            )
            halfModeWindowRoundedCorners = createRoundedCorners(width, height, radius)
            halfModeWindowGeometry = geometry
        }
        // Native metric refreshes rebuild these fields even when the size is unchanged.
        XposedHelpers.setObjectField(
            displayContent,
            "mBaseDisplayCutout",
            XposedHelpers.getStaticObjectField(DisplayCutout::class.java, "NO_CUTOUT")
        )
        XposedHelpers.setObjectField(
            displayContent,
            "mBaseRoundedCorners",
            halfModeWindowRoundedCorners
        )
        halfModeDisplayShapeCache = XposedHelpers.getObjectField(displayContent, "mDisplayShapeCache")
    }

    private fun createRoundedCorners(width: Int, height: Int, radius: Int): Any {
        val cornerClass = Class.forName("android.view.RoundedCorner")
        fun corner(position: Int, x: Int, y: Int): Any =
            XposedHelpers.newInstance(cornerClass, position, radius, x, y)
        val cornersClass = Class.forName("android.view.RoundedCorners")
        return XposedHelpers.newInstance(
            cornersClass,
            corner(0, radius, radius),
            corner(1, width - radius, radius),
            corner(2, width - radius, height - radius),
            corner(3, radius, height - radius)
        )
    }

    private fun persistHalfMode(enabled: Boolean): Boolean {
        val systemContext = context ?: return false
        return Settings.Global.putInt(
            systemContext.contentResolver,
            CoverDisplayConfig.SETTINGS_HALF_MODE_KEY,
            if (enabled) 1 else 0
        )
    }

    private fun restoreOriginalChannelForFullDex() {
        DisplayChannelModePolicy.update(CoverQsMode.ORIGINAL) { success ->
            if (success) finishFullDexChange(true, launcherAlreadyRestarted = true)
            else log("stored full cover DeX deferred: ORIGINAL channel restore failed")
        }
    }

    private fun persistFullDex(enabled: Boolean): Boolean {
        val systemContext = context ?: return false
        return Settings.Global.putInt(
            systemContext.contentResolver,
            CoverDisplayConfig.SETTINGS_FULL_DEX_KEY,
            if (enabled) 1 else 0
        ).also { success ->
            if (!success) log("full cover DeX persistence rejected")
        }
    }

    private fun finishFullDexChange(enabled: Boolean, launcherAlreadyRestarted: Boolean) {
        val systemContext = context ?: return
        publishStatus("full-dex-update")
        systemContext.sendBroadcast(
            Intent(CoverDisplayConfig.ACTION_FULL_DEX_CHANGED).apply {
                setPackage(SAMSUNG_LAUNCHER_PACKAGE)
                putExtra(CoverDisplayConfig.EXTRA_FULL_DEX, enabled)
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            },
            COVER_SHELL_PERMISSION
        )
        systemContext.sendBroadcast(
            Intent(CoverDisplayConfig.ACTION_FULL_DEX_CHANGED).apply {
                setPackage("com.android.systemui")
                putExtra(CoverDisplayConfig.EXTRA_FULL_DEX, enabled)
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            },
            COVER_SHELL_PERMISSION
        )
        CoverSessionCoordinator.current()?.onFullDexModeChanged(!launcherAlreadyRestarted)
        log("full cover DeX updated enabled=$enabled")
    }

    private fun applyRequestedCameraMode(intent: Intent) {
        val systemContext = context ?: return
        if (!intent.hasExtra(CoverDisplayConfig.EXTRA_CAMERA_ORIGINAL)) return
        val original = intent.getBooleanExtra(CoverDisplayConfig.EXTRA_CAMERA_ORIGINAL, false)
        if (!Settings.Global.putInt(
                systemContext.contentResolver,
                CoverDisplayConfig.SETTINGS_CAMERA_MODE_KEY,
                if (original) 1 else 0
            )
        ) {
            return log("cover camera mode persistence rejected")
        }
        log("cover camera mode updated original=$original")
    }

    private fun applyRequestedLockscreenTimeout(intent: Intent) {
        val systemContext = context ?: return
        if (!intent.hasExtra(CoverDisplayConfig.EXTRA_LOCKSCREEN_TIMEOUT_MILLIS)) return
        val timeoutMillis = intent.getLongExtra(
            CoverDisplayConfig.EXTRA_LOCKSCREEN_TIMEOUT_MILLIS,
            -1L
        )
        if (timeoutMillis !in CoverDisplayConfig.LOCKSCREEN_TIMEOUT_OPTIONS_MILLIS) {
            return log("cover lockscreen timeout rejected value=$timeoutMillis")
        }
        if (!Settings.Global.putLong(
                systemContext.contentResolver,
                CoverDisplayConfig.SETTINGS_LOCKSCREEN_TIMEOUT_KEY,
                timeoutMillis
            )
        ) {
            return log("cover lockscreen timeout persistence rejected")
        }
        log("cover lockscreen timeout updated timeoutMs=$timeoutMillis")
    }

    private fun applyRequestedSystemUpdateBlocked(intent: Intent) {
        val systemContext = context ?: return
        if (!intent.hasExtra(CoverDisplayConfig.EXTRA_SYSTEM_UPDATE_BLOCKED)) return
        val blocked = intent.getBooleanExtra(CoverDisplayConfig.EXTRA_SYSTEM_UPDATE_BLOCKED, false)
        val packageManager = systemContext.packageManager
        val savedRaw = Settings.Global.getString(
            systemContext.contentResolver,
            CoverDisplayConfig.SETTINGS_SYSTEM_UPDATE_ORIGINAL_STATES_KEY
        )
        val savedStates = decodeSystemUpdatePackageStates(savedRaw)
        if (savedRaw != null && savedStates == null) {
            return log("system update state rejected: malformed saved package states")
        }
        val originalStates = savedStates ?: installedUpdatePackageStates(packageManager)
        if (originalStates.isEmpty()) {
            return log("system update state rejected: Samsung OTA packages not installed")
        }
        val currentStates = originalStates.keys.associateWith(
            packageManager::getApplicationEnabledSetting
        )
        val targetStates = if (blocked) {
            originalStates.keys.associateWith {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
            }
        } else {
            originalStates
        }
        if (blocked && savedStates == null && !Settings.Global.putString(
                systemContext.contentResolver,
                CoverDisplayConfig.SETTINGS_SYSTEM_UPDATE_ORIGINAL_STATES_KEY,
                encodeSystemUpdatePackageStates(originalStates)
            )
        ) {
            return log("system update state rejected: original states persistence failed")
        }
        val failure = setUpdatePackageStates(packageManager, targetStates)
        if (failure != null) {
            setUpdatePackageStates(packageManager, currentStates)
            if (savedStates == null) {
                Settings.Global.putString(
                    systemContext.contentResolver,
                    CoverDisplayConfig.SETTINGS_SYSTEM_UPDATE_ORIGINAL_STATES_KEY,
                    null
                )
            }
            return log("system update state failed: ${failure.message}")
        }
        if (!Settings.Global.putInt(
                systemContext.contentResolver,
                CoverDisplayConfig.SETTINGS_SYSTEM_UPDATE_BLOCKED_KEY,
                if (blocked) 1 else 0
            )
        ) {
            setUpdatePackageStates(packageManager, currentStates)
            return log("system update state rejected: setting persistence failed")
        }
        if (!blocked) {
            Settings.Global.putString(
                systemContext.contentResolver,
                CoverDisplayConfig.SETTINGS_SYSTEM_UPDATE_ORIGINAL_STATES_KEY,
                null
            )
        }
        log("Samsung system update blocked=$blocked packages=${targetStates.keys.joinToString()}")
    }

    private fun applyRequestedImeCompact(intent: Intent) {
        val systemContext = context ?: return
        if (!intent.hasExtra(CoverDisplayConfig.EXTRA_IME_COMPACT)) return
        val compact = intent.getBooleanExtra(CoverDisplayConfig.EXTRA_IME_COMPACT, true)
        val percent = com.flexunlock.dexlsp.config.normalizeImeCompactPercent(
            intent.getIntExtra(
                CoverDisplayConfig.EXTRA_IME_COMPACT_PERCENT,
                CoverDisplayConfig.readImeCompactPercent(systemContext)
            )
        )
        Settings.Global.putInt(
            systemContext.contentResolver,
            CoverDisplayConfig.SETTINGS_IME_COMPACT_PERCENT_KEY,
            percent
        )
        if (!Settings.Global.putInt(
                systemContext.contentResolver,
                CoverDisplayConfig.SETTINGS_IME_COMPACT_KEY,
                if (compact) 1 else 0
            )
        ) {
            return log("IME compact mode persistence rejected")
        }
        refreshImeWindowDensity(systemContext, compact, percent)
        log("IME compact mode updated compact=$compact percent=$percent")
    }

    private fun refreshImeWindowDensity(
        systemContext: Context,
        compact: Boolean,
        compactPercent: Int = CoverDisplayConfig.readImeCompactPercent(systemContext)
    ) {
        val service = windowManagerService ?: return
        val controller = XposedHelpers.getObjectField(service, "mWindowContextListenerController")
        val listeners = XposedHelpers.getObjectField(controller, "mListeners")
        val percent = if (compact) compactPercent else 100
        val count = XposedHelpers.callMethod(listeners, "size") as Int
        repeat(count) { index ->
            val listener = XposedHelpers.callMethod(listeners, "valueAt", index) ?: return@repeat
            if (XposedHelpers.getIntField(listener, "mType") != 2011) return@repeat
            val container = XposedHelpers.getObjectField(listener, "mContainer") ?: return@repeat
            val display = XposedHelpers.callMethod(container, "getDisplayContent") ?: return@repeat
            if (XposedHelpers.getIntField(display, "mDisplayId") != 1) return@repeat
            val displayInfo = XposedHelpers.getObjectField(display, "mDisplayInfo")
            val override = Configuration().apply {
                densityDpi = imeDensityForDisplay(displayInfo, percent)
            }
            XposedHelpers.callMethod(container, "onRequestedOverrideConfigurationChanged", override)
        }
    }

    private fun installedUpdatePackageStates(
        packageManager: PackageManager
    ): Map<String, Int> = CoverDisplayConfig.SYSTEM_UPDATE_PACKAGES
        .filter { packageName ->
            runCatching {
                packageManager.getApplicationInfo(
                    packageName,
                    PackageManager.ApplicationInfoFlags.of(0)
                )
            }.isSuccess
        }
        .associateWith(packageManager::getApplicationEnabledSetting)

    private fun setUpdatePackageStates(
        packageManager: PackageManager,
        states: Map<String, Int>
    ): Throwable? = runCatching {
        states.forEach { (packageName, state) ->
            packageManager.setApplicationEnabledSetting(packageName, state, 0)
        }
    }.exceptionOrNull()

    private fun applyStoredMetrics(preferredExternalMode: Display.Mode? = null) {
        val systemContext = context ?: return
        val resolution = CoverDisplayResolver.resolution()
        val snapshot = (resolution as? com.flexunlock.dexlsp.CoverDisplayResolution.Resolved)
            ?.snapshot ?: return
        val externalTarget = isExternalDisplayType(snapshot.type)
        if (!CoverQsModeConfig.readTransaction(systemContext).isStableOriginal && !externalTarget) return
        val displayId = snapshot.id.takeIf(::isUsableDisplayId) ?: return
        val storedDisplayId = Settings.Global.getInt(
            systemContext.contentResolver,
            CoverDisplayConfig.SETTINGS_OVERRIDE_DISPLAY_ID_KEY,
            -1
        )
        val storedUniqueId = Settings.Global.getString(
            systemContext.contentResolver,
            CoverDisplayConfig.SETTINGS_OVERRIDE_UNIQUE_ID_KEY
        )
        val configured = CoverDisplayConfig.readOverride(systemContext)
        val matchesTarget = displayOverrideMatchesTarget(
            storedDisplayId,
            storedUniqueId,
            displayId,
            snapshot.uniqueId
        )
        var value = configured.takeIf { matchesTarget }
        if (configured != null && !matchesTarget) {
            Settings.Global.putString(
                systemContext.contentResolver,
                CoverDisplayConfig.SETTINGS_OVERRIDE_KEY,
                CoverDisplayConfig.encodeOverride(null)
            )
            log("display override cleared after target change $storedDisplayId->$displayId")
        }
        if (value != null && !validMetrics(value)) {
            Settings.Global.putString(
                systemContext.contentResolver,
                CoverDisplayConfig.SETTINGS_OVERRIDE_KEY,
                CoverDisplayConfig.encodeOverride(null)
            )
            log("stored cover display metrics cleared after output mode change value=$value")
            value = null
        }
        val applied = displayId to value
        if (lastAppliedOverride == applied) return
        if (halfModeDisplayId >= 0 && !restoreHalfModeGeometry()) return
        val previousDisplayId = storedDisplayId.takeIf { isUsableDisplayId(it) && it != displayId }
        lastAppliedOverride = applied
        runCatching {
            val windowManager = windowManagerInterface()
            previousDisplayId?.let { oldDisplayId ->
                runCatching {
                    XposedHelpers.callMethod(windowManager, "clearForcedDisplaySize", oldDisplayId)
                    XposedHelpers.callMethod(
                        windowManager,
                        "clearForcedDisplayDensityForUser",
                        oldDisplayId,
                        0
                    )
                }.onFailure { error ->
                    log("stale display metrics cleanup failed displayId=$oldDisplayId: ${error.message}")
                }
            }
            val nativeTarget = if (value == null) {
                if (isExternalDisplayType(snapshot.type)) {
                    externalNativeMetrics(displayId, preferredExternalMode)
                } else {
                    nativeMetrics(displayId)
                }
            } else {
                null
            }
            val nativeAlreadyApplied = nativeTarget != null &&
                currentBaseMetrics(displayId)?.let { current ->
                    displayMetricsMatch(
                        current.width,
                        current.height,
                        current.densityDpi,
                        nativeTarget
                    )
                } == true
            if (nativeAlreadyApplied) {
                log("cover display metrics already native displayId=$displayId; apply skipped")
            } else if (value == null) {
                XposedHelpers.callMethod(windowManager, "clearForcedDisplaySize", displayId)
                XposedHelpers.callMethod(
                    windowManager,
                    "clearForcedDisplayDensityForUser",
                    displayId,
                    0
                )
                applyAtomicMetrics(displayId, nativeTarget!!)
                runCatching {
                    XposedHelpers.callMethod(windowManager, "setForcedDisplayScalingMode", displayId, 0)
                }
            } else {
                runCatching {
                    XposedHelpers.callMethod(windowManager, "setForcedDisplayScalingMode", displayId, 0)
                }
                applyAtomicMetrics(displayId, value)
                runCatching {
                    XposedHelpers.callMethod(windowManager, "setForcedDisplayScalingMode", displayId, 0)
                }
            }
            Settings.Global.putInt(
                systemContext.contentResolver,
                CoverDisplayConfig.SETTINGS_OVERRIDE_DISPLAY_ID_KEY,
                if (value == null) -1 else displayId
            )
            Settings.Global.putString(
                systemContext.contentResolver,
                CoverDisplayConfig.SETTINGS_OVERRIDE_UNIQUE_ID_KEY,
                if (value == null) null else snapshot.uniqueId
            )
            log("cover display metrics applied displayId=$displayId value=${value ?: "native"}")
            if (!externalTarget && CoverDisplayConfig.readHalfMode(systemContext) &&
                !applyHalfModeGeometry()
            ) {
                persistHalfMode(false)
            }
        }.onFailure { error ->
            lastAppliedOverride = null
            log("cover display metrics apply failed displayId=$displayId: ${error.message}")
        }
    }

    private fun currentBaseMetrics(displayId: Int): CoverDisplayOverride? = runCatching {
        val service = windowManagerService ?: return@runCatching null
        val root = XposedHelpers.getObjectField(service, "mRoot")
        val displayContent = XposedHelpers.callMethod(root, "getDisplayContent", displayId)
        CoverDisplayOverride(
            XposedHelpers.getIntField(displayContent, "mBaseDisplayWidth"),
            XposedHelpers.getIntField(displayContent, "mBaseDisplayHeight"),
            XposedHelpers.getIntField(displayContent, "mBaseDisplayDensity")
        )
    }.getOrNull()

    private fun windowManagerInterface(): Any {
        val serviceManager = XposedHelpers.findClass("android.os.ServiceManager", null)
        val binder = XposedHelpers.callStaticMethod(serviceManager, "getService", "window")
        val stub = XposedHelpers.findClass("android.view.IWindowManager\$Stub", null)
        return XposedHelpers.callStaticMethod(stub, "asInterface", binder)
    }

    private fun nativeMetrics(displayId: Int): CoverDisplayOverride {
        val service = windowManagerService ?: error("WindowManagerService unavailable")
        val root = XposedHelpers.getObjectField(service, "mRoot")
        val displayContent = XposedHelpers.callMethod(root, "getDisplayContent", displayId)
        return CoverDisplayOverride(
            XposedHelpers.getIntField(displayContent, "mInitialDisplayWidth"),
            XposedHelpers.getIntField(displayContent, "mInitialDisplayHeight"),
            XposedHelpers.getIntField(displayContent, "mInitialDisplayDensity")
        )
    }

    private fun externalNativeMetrics(
        displayId: Int,
        preferredMode: Display.Mode? = null
    ): CoverDisplayOverride {
        val display = context?.getSystemService(DisplayManager::class.java)
            ?.getDisplay(displayId)
            ?: return nativeMetrics(displayId)
        val mode = preferredMode ?: display.mode
        val metrics = DisplayMetrics()
        display.getRealMetrics(metrics)
        if (mode.physicalWidth <= 0 || mode.physicalHeight <= 0) return nativeMetrics(displayId)
        return externalDisplayMetrics(mode.physicalWidth, mode.physicalHeight, metrics.densityDpi)
    }

    private fun applyAtomicMetrics(displayId: Int, value: CoverDisplayOverride) {
        val service = windowManagerService ?: error("WindowManagerService unavailable")
        val builderClass = XposedHelpers.findClass(
            "com.samsung.android.view.MultiResolutionChangeRequestInfo\$Builder",
            service.javaClass.classLoader
        )
        val builder = XposedHelpers.newInstance(builderClass, displayId)
        XposedHelpers.callMethod(builder, "setWidth", value.width)
        XposedHelpers.callMethod(builder, "setHeight", value.height)
        XposedHelpers.callMethod(builder, "setDensity", value.densityDpi)
        XposedHelpers.callMethod(builder, "setSaveToSettings", false)
        val request = XposedHelpers.callMethod(builder, "build")
        val extension = XposedHelpers.getObjectField(service, "mExt")
        val controller = XposedHelpers.getObjectField(extension, "mMultiResolutionController")
        XposedHelpers.callMethod(controller, "setForcedDisplaySizeDensityInner", request)
    }

    internal fun captureMetrics(
        displayId: Int,
        uniqueId: String
    ): DisplayMetricsSnapshot? = runCatching {
        val service = windowManagerService ?: error("WindowManagerService unavailable")
        val root = XposedHelpers.getObjectField(service, "mRoot")
        val displayContent = XposedHelpers.callMethod(root, "getDisplayContent", displayId)
        DisplayMetricsSnapshot(
            displayId = displayId,
            uniqueId = uniqueId,
            initialWidth = XposedHelpers.getIntField(displayContent, "mInitialDisplayWidth"),
            initialHeight = XposedHelpers.getIntField(displayContent, "mInitialDisplayHeight"),
            initialDensity = XposedHelpers.getIntField(displayContent, "mInitialDisplayDensity"),
            baseWidth = XposedHelpers.getIntField(displayContent, "mBaseDisplayWidth"),
            baseHeight = XposedHelpers.getIntField(displayContent, "mBaseDisplayHeight"),
            baseDensity = XposedHelpers.getIntField(displayContent, "mBaseDisplayDensity")
        )
    }.onFailure { error ->
        log("display metrics snapshot failed displayId=$displayId: ${error.message}")
    }.getOrNull()

    internal fun applyTemporaryMetrics(displayId: Int, value: CoverDisplayOverride): Boolean =
        runCatching {
            applyAtomicMetrics(displayId, value)
            true
        }.onFailure { error ->
            log("temporary display metrics failed displayId=$displayId: ${error.message}")
        }.getOrDefault(false)

    internal fun restoreNativeMetrics(displayId: Int): Boolean = runCatching {
        val windowManager = windowManagerInterface()
        XposedHelpers.callMethod(windowManager, "clearForcedDisplaySize", displayId)
        XposedHelpers.callMethod(windowManager, "clearForcedDisplayDensityForUser", displayId, 0)
        applyAtomicMetrics(displayId, nativeMetrics(displayId))
        true
    }.onFailure { error ->
        log("native display metrics restore failed displayId=$displayId: ${error.message}")
    }.getOrDefault(false)

    internal fun invalidateStoredMetrics() {
        lastAppliedOverride = null
    }

    internal fun restoreMetrics(
        snapshot: DisplayMetricsSnapshot,
        restoreInitial: Boolean = false
    ): Boolean = runCatching {
        val display = context?.getSystemService(DisplayManager::class.java)
            ?.getDisplay(snapshot.displayId)
            ?: error("display ${snapshot.displayId} unavailable")
        val actualIdentity = display.javaClass.getMethod("getUniqueId").invoke(display) as String
        check(actualIdentity == snapshot.uniqueId) {
            "display identity changed expected=${snapshot.uniqueId} actual=$actualIdentity"
        }
        if (restoreInitial) {
            check(restoreNativeMetrics(snapshot.displayId))
        }
        val target = restoredDisplayMetrics(snapshot)
        if (!restoreInitial) applyAtomicMetrics(snapshot.displayId, target)
        val restored = captureMetrics(snapshot.displayId, snapshot.uniqueId)
            ?: error("restored display metrics unavailable")
        check(restored.baseWidth == target.width)
        check(restored.baseHeight == target.height)
        check(restored.baseDensity == target.densityDpi)
        true
    }.onFailure { error ->
        log("display metrics restore failed displayId=${snapshot.displayId}: ${error.message}")
    }.getOrDefault(false)

    private fun syncDisplayGeometry(displayContent: Any) {
        val systemContext = context ?: return
        if (!CoverQsModeConfig.readTransaction(systemContext).isStableOriginal) return
        val displayId = XposedHelpers.getIntField(displayContent, "mDisplayId")
        val coverId = (CoverDisplayResolver.resolution()
            as? com.flexunlock.dexlsp.CoverDisplayResolution.Resolved)?.snapshot?.id ?: return
        if (displayId != coverId) return
        val target = (CoverDisplayResolver.resolution()
            as? com.flexunlock.dexlsp.CoverDisplayResolution.Resolved)?.snapshot ?: return
        if (isExternalDisplayType(target.type)) return
        val initialWidth = XposedHelpers.getIntField(displayContent, "mInitialDisplayWidth")
        val initialHeight = XposedHelpers.getIntField(displayContent, "mInitialDisplayHeight")
        val baseWidth = XposedHelpers.getIntField(displayContent, "mBaseDisplayWidth")
        val baseHeight = XposedHelpers.getIntField(displayContent, "mBaseDisplayHeight")
        if (displayId == halfModeDisplayId) {
            applyHalfModeWindowGeometry(displayContent)
            return
        }
        val initialShape = XposedHelpers.getObjectField(displayContent, "mInitialDisplayShape")
        XposedHelpers.setObjectField(
            displayContent,
            "mInitialDisplayShape",
            XposedHelpers.callMethod(initialShape, "setScale", baseWidth.toFloat() / initialWidth)
        )
        if (baseWidth != initialWidth || baseHeight != initialHeight) return
        XposedHelpers.setObjectField(
            displayContent,
            "mBaseRoundedCorners",
            XposedHelpers.getObjectField(displayContent, "mInitialRoundedCorners")
        )
        XposedHelpers.setObjectField(
            displayContent,
            "mBaseDisplayCutout",
            XposedHelpers.getObjectField(displayContent, "mInitialDisplayCutout")
        )
    }

    private fun validMetrics(value: CoverDisplayOverride, fullQs: Boolean = false): Boolean {
        if (value.width !in 480..7680 ||
            value.height !in 320..4320 ||
            value.densityDpi !in 120..960
        ) return false
        if (fullQs) return displayMetricsAspectMatches(value.width, value.height, 1, 1)
        val displayId = (CoverDisplayResolver.resolution()
            as? com.flexunlock.dexlsp.CoverDisplayResolution.Resolved)?.snapshot?.id ?: return true
        val mode = context?.getSystemService(DisplayManager::class.java)
            ?.getDisplay(displayId)?.mode ?: return true
        return displayMetricsAspectMatches(
            value.width,
            value.height,
            mode.physicalWidth,
            mode.physicalHeight
        )
    }

    private fun applyRequestedIdentity(intent: Intent) {
        val systemContext = context ?: return
        val raw = intent.getStringExtra(CoverDisplayConfig.EXTRA_IDENTITY) ?: return
        val identity = CoverDisplayConfig.decodeIdentity(raw)
        if (identity == null && raw != CoverDisplayConfig.AUTO_VALUE) {
            log("cover display identity update rejected: malformed stable identity")
            return
        }
        if (!CoverQsModeConfig.readTransaction(systemContext).isStableOriginal) {
            DisplayChannelModePolicy.update(CoverQsMode.ORIGINAL) { success ->
                if (success) persistRequestedIdentity(systemContext, identity)
                else log("cover display identity rejected: ORIGINAL channel restore failed")
            }
            return
        }
        persistRequestedIdentity(systemContext, identity)
    }

    private fun persistRequestedIdentity(
        systemContext: Context,
        identity: com.flexunlock.dexlsp.DisplayStableIdentity?
    ) {
        val encoded = CoverDisplayConfig.encodeIdentity(identity)
        if (!Settings.Global.putString(
                systemContext.contentResolver,
                CoverDisplayConfig.SETTINGS_IDENTITY_KEY,
                encoded
            )
        ) {
            log("cover display identity persistence rejected")
            return
        }
        CoverDisplayResolver.refreshConfiguration("protected-update")
        publishStatus("protected-update")
        log("cover display mode updated mode=${if (identity == null) "auto" else "manual"}")
    }

    private fun applyRequestedQsMode(intent: Intent) {
        if (!intent.hasExtra(CoverQsModeConfig.EXTRA_MODE)) return
        val mode = CoverQsMode.fromOrNull(
            intent.getIntExtra(CoverQsModeConfig.EXTRA_MODE, -1)
        ) ?: return log("cover QS mode rejected: invalid value")
        val systemContext = context ?: return
        val previousFullDex = CoverDisplayConfig.readFullDex(systemContext)
        val disableFullDex = fullQsRequiresFullDexDisabled(mode, previousFullDex)
        if (disableFullDex && !persistFullDex(false)) return
        DisplayChannelModePolicy.update(mode) { success ->
            if (success) {
                lastAppliedOverride = null
                if (mode == CoverQsMode.ORIGINAL) {
                    applyStoredMetrics()
                }
                if (disableFullDex) {
                    finishFullDexChange(false, launcherAlreadyRestarted = true)
                }
            } else if (disableFullDex) {
                persistFullDex(previousFullDex)
            }
            publishStatus("qs-mode-${if (success) "applied" else "rejected"}")
            log("cover QS mode request mode=$mode success=$success")
        }
    }

    private fun CoverQsTransition.isStable(): Boolean =
        this == CoverQsTransition.ORIGINAL || this == CoverQsTransition.FULL

    private fun publishStatus(reason: String) {
        val systemContext = context ?: return
        val candidates = CoverDisplayResolver.selectableCandidates().mapNotNull { candidate ->
            val identity = candidate.stableIdentity ?: return@mapNotNull null
            val densityDpi = initialDisplayDensity(candidate.id).takeIf { it > 0 }
                ?: systemContext.getSystemService(DisplayManager::class.java)
                    ?.getDisplay(candidate.id)
                    ?.let { display -> DisplayMetrics().also(display::getRealMetrics).densityDpi }
                ?: 0
            CoverDisplayCandidateStatus(
                identity = identity,
                displayId = candidate.id,
                width = candidate.width,
                height = candidate.height,
                densityDpi = densityDpi,
                name = candidate.name
            )
        }
        val snapshot = (CoverDisplayResolver.resolution()
            as? com.flexunlock.dexlsp.CoverDisplayResolution.Resolved)?.snapshot
        val externalDisplay = snapshot
            ?.takeIf { isExternalDisplayType(it.type) }
            ?.let { target ->
                systemContext.getSystemService(DisplayManager::class.java)?.getDisplay(target.id)
            }
        val encoded = CoverDisplayConfig.encodeStatus(
            CoverDisplayStatus(
                manualIdentity = CoverDisplayResolver.configuredIdentity(),
                resolution = CoverDisplayResolver.resolution(),
                candidates = candidates,
                displayOverride = CoverDisplayConfig.readOverride(systemContext),
                preferredDisplayMode = externalDisplay?.let { userPreferredDisplayMode(it.displayId) },
                supportedDisplayModes = externalDisplay
                    ?.let { supportedDisplayModeValues(it.supportedModes) }
                    .orEmpty(),
                fullDexEnabled = CoverDisplayConfig.readFullDex(systemContext),
                halfModeEnabled = CoverDisplayConfig.readHalfMode(systemContext),
                revision = statusRevision.incrementAndGet()
            )
        )
        if (!Settings.Global.putString(
                systemContext.contentResolver,
                CoverDisplayConfig.SETTINGS_STATUS_KEY,
                encoded
            )
        ) {
            log("cover display status persistence rejected reason=$reason")
        }
    }

    private fun userPreferredDisplayMode(displayId: Int): CoverDisplayMode? = runCatching {
        val managerClass = XposedHelpers.findClass(
            "android.hardware.display.DisplayManagerGlobal",
            null
        )
        val manager = XposedHelpers.callStaticMethod(managerClass, "getInstance")
        val mode = XposedHelpers.callMethod(manager, "getUserPreferredDisplayMode", displayId)
            as? Display.Mode ?: return@runCatching null
        preferredDisplayModeOrNull(mode.physicalWidth, mode.physicalHeight, mode.refreshRate)
    }.getOrNull()

    private fun initialDisplayDensity(displayId: Int): Int = runCatching {
        val windowManager = windowManagerInterface()
        (XposedHelpers.callMethod(windowManager, "getInitialDisplayDensity", displayId) as Number)
            .toInt()
    }.getOrDefault(0)

    private fun log(message: String) {
        Log.i(TAG, message)
        XposedBridge.log("$TAG: $message")
    }
}
