package com.flexunlock.dexlsp

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.KeyguardManager
import android.app.Notification
import android.app.PendingIntent
import android.app.ActivityOptions
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.IdentityHashMap
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.max

internal object NativeCoverNotificationPageHooks {
    private const val SCOPE = "CoverNotificationPage"
    private const val CONTROLLER_CLASS =
        "com.android.systemui.subscreen.SubScreenQuickPanelWindowController"
    private const val PIPELINE_CLASS =
        "com.android.systemui.statusbar.notification.collection.NotifPipeline"
    private const val LISTENER_CLASS =
        "com.android.systemui.statusbar.notification.collection.notifcollection.NotifCollectionListener"
    private const val ACTIVITY_STARTER_CLASS =
        "com.android.systemui.statusbar.phone.StatusBarNotificationActivityStarter"
    private const val USB_STATUS_KEY = "flexunlock:usb-status"
    private const val USB_STATE_ACTION = "android.hardware.usb.action.USB_STATE"

    private val pages: MutableSet<CoverNotificationPage> = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap())
    )
    private val panelControllers: MutableSet<Any> = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap())
    )

    @Volatile
    private var pipeline: Any? = null
    @Volatile
    private var activityStarter: Any? = null
    @Volatile
    private var entriesByKey: Map<String, Any> = emptyMap()
    @Volatile
    private var pendingPipeline: Any? = null
    @Volatile
    private var collectionListener: Any? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile
    private var usbConnected = false
    @Volatile
    private var usbMtp = false
    @Volatile
    private var usbPtp = false
    @Volatile
    private var usbRndis = false
    @Volatile
    private var usbMidi = false
    @Volatile
    private var usbAccessory = false
    private var usbStateReceiverRegistered = false
    private val coverLaunchIntents = Collections.synchronizedMap(
        WeakHashMap<PendingIntent, Long>()
    )

    fun install(classLoader: ClassLoader) {
        runCatching {
            CoverRuntime.log("CoverNotificationPage", "notification page hooks installing")
            installPendingIntentDisplayRouting()
            installPipelineCapture(classLoader)
            installActivityStarterCapture(classLoader)
            installPanelHooks(classLoader)
        }.onFailure {
            CoverRuntime.log("CoverNotificationPage", "install failed: ${it.message}")
        }
    }

    private fun installPendingIntentDisplayRouting() {
        runCatching {
            XposedBridge.hookAllMethods(
                PendingIntent::class.java,
                "sendAndReturnResult",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val pendingIntent = param.thisObject as? PendingIntent ?: return
                        val expiresAt = coverLaunchIntents.remove(pendingIntent) ?: return
                        if (expiresAt < SystemClock.elapsedRealtime()) return
                        if (pendingIntent.isActivity) {
                            val coverDisplayId = CoverDisplayResolver.currentId()
                            val bundleIndex = param.args.indexOfLast { it == null || it is Bundle }
                            if (coverDisplayId != null && bundleIndex >= 0) {
                                val options = (param.args[bundleIndex] as? Bundle) ?: Bundle()
                                options.putInt(
                                    "android.activity.launchDisplayId",
                                    coverDisplayId
                                )
                                param.args[bundleIndex] = options
                                CoverRuntime.log(
                                    SCOPE,
                                    "native notification launch routed " +
                                        "display=$coverDisplayId"
                                )
                            }
                        }
                        collapseCoverPanelAfterLaunch()
                    }
                }
            )
        }.onFailure { unavailable("notification display routing", it.message) }
    }

    private fun collapseCoverPanelAfterLaunch() {
        Handler(Looper.getMainLooper()).postDelayed({
            panelControllers.toList().forEach { controller ->
                runCatching { XposedHelpers.callMethod(controller, "collapsePanel") }
                    .onFailure { unavailable("cover panel collapse after launch", it.message) }
            }
        }, 150L)
    }

    private fun installActivityStarterCapture(classLoader: ClassLoader) {
        val starterClass = XposedHelpers.findClassIfExists(ACTIVITY_STARTER_CLASS, classLoader)
            ?: return unavailable("notification click", "activity starter missing")
        runCatching {
            XposedBridge.hookAllConstructors(starterClass, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    activityStarter = param.thisObject
                    CoverRuntime.log(SCOPE, "notification activity starter connected")
                }
            })
        }.onFailure { unavailable("notification click", it.message) }
    }

    private fun installPipelineCapture(classLoader: ClassLoader) {
        val pipelineClass = XposedHelpers.findClassIfExists(PIPELINE_CLASS, classLoader)
            ?: return unavailable("notification pipeline", "NotifPipeline missing")
        val listenerClass = XposedHelpers.findClassIfExists(LISTENER_CLASS, classLoader)
            ?: return unavailable("notification listener", "NotifCollectionListener missing")

        runCatching {
            XposedBridge.hookAllConstructors(pipelineClass, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    capturePipeline(param.thisObject, listenerClass)
                }
            })
            CoverRuntime.log(SCOPE, "NotifPipeline capture installed")
        }.onFailure { unavailable("notification pipeline", it.message) }
    }

    private fun capturePipeline(candidate: Any, listenerClass: Class<*>) {
        if (pipeline === candidate && collectionListener != null || pendingPipeline === candidate) return
        val listener = Proxy.newProxyInstance(
            listenerClass.classLoader,
            arrayOf(listenerClass)
        ) { proxy, method, args ->
            when (method.name) {
                "equals" -> proxy === args?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "FlexUnlockCoverNotifCollectionListener"
                else -> {
                    if (method.name.startsWith("onEntry") || method.name.startsWith("onRanking")) {
                        refreshPages()
                    }
                    null
                }
            }
        }

        pendingPipeline = candidate
        mainHandler.post {
            if (pipeline === candidate && collectionListener != null) {
                pendingPipeline = null
                return@post
            }
            runCatching {
                XposedHelpers.callMethod(candidate, "addCollectionListener", listener)
                pipeline = candidate
                collectionListener = listener
                refreshPages()
                CoverRuntime.log(SCOPE, "NotifPipeline connected on main thread")
            }.onFailure { unavailable("notification listener registration", it.message) }
            pendingPipeline = null
        }
    }

    private fun installPanelHooks(classLoader: ClassLoader) {
        val controllerClass = XposedHelpers.findClassIfExists(CONTROLLER_CLASS, classLoader)
            ?: return unavailable("cover quick panel", "controller missing")

        runCatching {
            XposedHelpers.findAndHookMethod(
                controllerClass,
                "updatePanelExpansion",
                Float::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val page = pageFor(param.thisObject) ?: return
                        val expandedFraction = getFloatFieldOrNull(
                            param.thisObject,
                            "mExpandedFraction"
                        ) ?: return
                        page.onPanelExpansion(expandedFraction)
                    }
                }
            )
            installTouchHook(classLoader, "onInterceptTouchEvent", intercept = true)
            installTouchHook(classLoader, "onTouchEvent", intercept = false)
            XposedBridge.hookAllMethods(
                controllerClass,
                "collapsePanel",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        existingPageFor(param.thisObject)?.onPanelCollapseRequested()
                    }
                }
            )
            runCatching { installBackHook(classLoader) }
                .onFailure { unavailable("cover BACK reset", it.message) }
            CoverRuntime.log(SCOPE, "native cover pager hooks installed")
        }.onFailure { unavailable("cover quick panel hooks", it.message) }
    }

    private fun installTouchHook(classLoader: ClassLoader, methodName: String, intercept: Boolean) {
        val windowClass = XposedHelpers.findClassIfExists(
            "com.android.systemui.subscreen.SubScreenQuickPanelWindowView",
            classLoader
        ) ?: error("SubScreenQuickPanelWindowView missing")

        XposedHelpers.findAndHookMethod(
            windowClass,
            methodName,
            MotionEvent::class.java,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val root = param.thisObject as? ViewGroup ?: return
                    if (!CoverRuntime.isCoverUiSessionEligible()) return
                    if (!CoverRuntime.isCoverDisplay(CoverRuntime.displayIdOf(root.context))) return
                    val page = synchronized(pages) { pages.firstOrNull { it.root === root } } ?: return
                    val event = param.args[0] as? MotionEvent ?: return
                    val consumed = if (intercept) {
                        page.onInterceptTouchEvent(event)
                    } else {
                        page.onTouchEvent(event)
                    }
                    if (consumed) {
                        param.result = true
                    } else if (intercept && page.blocksNativeIntercept(event)) {
                        param.result = false
                    }
                }
            }
        )
    }

    private fun installBackHook(classLoader: ClassLoader) {
        val windowClass = XposedHelpers.findClassIfExists(
            "com.android.systemui.subscreen.SubScreenQuickPanelWindowView",
            classLoader
        ) ?: error("SubScreenQuickPanelWindowView missing")
        XposedHelpers.findAndHookMethod(
            windowClass,
            "dispatchKeyEvent",
            KeyEvent::class.java,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val root = param.thisObject as? ViewGroup ?: return
                    val event = param.args[0] as? KeyEvent ?: return
                    if (event.keyCode != KeyEvent.KEYCODE_BACK || event.action != KeyEvent.ACTION_DOWN) {
                        return
                    }
                    synchronized(pages) { pages.firstOrNull { it.root === root } }
                        ?.onBackPressed()
                }
            }
        )
    }

    private fun existingPageFor(controller: Any): CoverNotificationPage? {
        val root = getObjectFieldOrNull(controller, "mSubScreenQsWindowView") as? ViewGroup
            ?: return null
        return synchronized(pages) { pages.firstOrNull { it.root === root } }
    }

    private fun pageFor(controller: Any): CoverNotificationPage? {
        if (!CoverRuntime.isCoverUiSessionEligible()) return null
        val root = getObjectFieldOrNull(controller, "mSubScreenQsWindowView") as? ViewGroup
            ?: return null
        if (!CoverRuntime.isCoverDisplay(CoverRuntime.displayIdOf(root.context))) return null
        panelControllers.add(controller)
        synchronized(pages) {
            pages.firstOrNull { it.root === root }?.let { return it }
        }
        val qsPanel = getObjectFieldOrNull(controller, "mQSPanel") as? View ?: return null
        registerUsbStateReceiver(root.context)
        if (qsPanel.parent !== root) {
            return unavailable("page attachment", "mQSPanel is not a direct Window root child").let { null }
        }
        val headerController = getObjectFieldOrNull(
            controller,
            "mSubScreenQuickPanelHeaderController"
        )
        val nativeHeader = headerController?.let { getObjectFieldOrNull(it, "mView") } as? View
        NativeCoverQuickSettingsEditorHooks.bindQsHeader(qsPanel, nativeHeader)

        return CoverNotificationPage(
            root = root,
            qsPanel = qsPanel,
            nativeHeader = nativeHeader,
            onOpen = ::openNotification,
            onDismiss = ::dismissNotification,
            onDismissAll = { dismissAllNotifications(root.context) },
            onCollapsePanel = {
                root.post {
                    runCatching { XposedHelpers.callMethod(controller, "collapsePanel") }
                        .onFailure { unavailable("QS content collapse handoff", it.message) }
                }
            },
            onDisposed = { disposedPage -> pages.remove(disposedPage) },
            snapshotProvider = { notificationSnapshot(root.context) }
        ).also { page ->
            pages.add(page)
            page.attach()
            page.refresh(notificationSnapshot(root.context))
            CoverRuntime.log(
                SCOPE,
                "notification page attached display=${root.display?.displayId} " +
                    "header=${nativeHeader?.javaClass?.name ?: "missing"}"
            )
        }
    }

    private fun openNotification(key: String) {
        if (key == USB_STATUS_KEY) {
            openUsbSettings()
            return
        }
        val entry = entriesByKey[key] ?: return
        val sbn = getObjectFieldOrNull(entry, "mSbn") as? StatusBarNotification
            ?: return unavailable("notification click", "StatusBarNotification unavailable key=$key")
        val row = getObjectFieldOrNull(entry, "row") as? View
            ?: return unavailable("notification click", "inflated row unavailable key=$key")
        val notification = sbn.notification
        val launchIntent = notification.contentIntent ?: notification.fullScreenIntent
        if (launchIntent != null) {
            coverLaunchIntents[launchIntent] = SystemClock.elapsedRealtime() + 120_000L
        }

        val starter = activityStarter
            ?: return unavailable("notification click", "activity starter unavailable")
        NativeCoverStatusBarHooks.suppressHomeDuringExternalLaunch()
        runCatching {
            XposedHelpers.callMethod(starter, "onNotificationClicked", entry, row)
            CoverRuntime.log(
                SCOPE,
                "notification click dispatched through SystemUI starter " +
                    "intentType=${when {
                        launchIntent == null -> "none"
                        launchIntent.isActivity -> "activity"
                        launchIntent.isBroadcast -> "broadcast"
                        launchIntent.isService -> "service"
                        else -> "unknown"
                    }} key=$key"
            )
        }.onFailure {
            if (launchIntent != null) coverLaunchIntents.remove(launchIntent)
            unavailable("notification click", it.message)
        }
    }

    private fun dismissNotification(key: String) {
        val entry = entriesByKey[key] ?: return
        val sbn = getObjectFieldOrNull(entry, "mSbn") as? StatusBarNotification ?: return
        if (!isDismissible(entry, sbn)) {
            return unavailable("notification dismissal", "entry is not dismissible key=$key")
        }
        val collection = pipeline?.let { getObjectFieldOrNull(it, "mNotifCollection") }
            ?: return unavailable("notification dismissal", "NotifCollection unavailable")
        runCatching {
            XposedHelpers.callMethod(collection, "dismissOngoingActivityNotification", key)
        }.onFailure { unavailable("notification dismissal", it.message) }
    }

    private fun dismissAllNotifications(context: Context) {
        // Samsung's bulk API can dismiss ongoing rows when its second argument is true.
        notificationSnapshot(context).filter(CoverNotification::clearable)
            .forEach { dismissNotification(it.key) }
    }

    private fun refreshPages() {
        val snapshot = synchronized(pages) { pages.toList() }
        snapshot.forEach { page ->
            page.root.post { page.refresh(notificationSnapshot(page.root.context)) }
        }
    }

    private fun notificationSnapshot(context: Context): List<CoverNotification> {
        val entries = pipeline?.let(::notificationEntries).orEmpty()
        val resolved = entries.mapNotNull { entry ->
            val sbn = entry?.let { getObjectFieldOrNull(it, "mSbn") } as? StatusBarNotification
                ?: return@mapNotNull null
            entry to sbn
        }
        entriesByKey = resolved.associate { (entry, sbn) -> sbn.key to entry }
        val groupsWithChildren = resolved.asSequence()
            .filter { (_, sbn) -> sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 }
            .map { (_, sbn) -> sbn.groupKey }
            .toSet()

        val keyguardLocked = context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
        val notifications = resolved.mapNotNull { (entry, sbn) ->
            val notification = sbn.notification
            if (
                notification.flags and Notification.FLAG_GROUP_SUMMARY != 0 &&
                sbn.groupKey in groupsWithChildren
            ) return@mapNotNull null
            val appName = applicationLabel(context, sbn.packageName)
            val hideContent = keyguardLocked && (
                notification.visibility != Notification.VISIBILITY_PUBLIC || isSensitive(entry)
            )
            val extras = notification.extras
            val title = if (hideContent) {
                appName
            } else {
                extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().ifBlank { appName }
            }
            val text = if (hideContent) {
                "内容已隐藏"
            } else {
                extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
            }
            CoverNotification(
                key = sbn.key,
                packageName = sbn.packageName,
                appName = appName,
                title = title,
                text = text,
                postTime = sbn.postTime,
                clearable = isDismissible(entry, sbn)
            )
        }.toMutableList()
        if (usbConnected && resolved.none { (_, sbn) -> isUsbNotification(sbn) }) {
            notifications += usbStatusNotification(context)
        }
        return notifications.sortedByDescending(CoverNotification::postTime)
    }

    private fun registerUsbStateReceiver(context: Context) {
        if (usbStateReceiverRegistered) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != USB_STATE_ACTION) return
                usbConnected = intent.getBooleanExtra("connected", false)
                usbMtp = intent.getBooleanExtra("mtp", false)
                usbPtp = intent.getBooleanExtra("ptp", false)
                usbRndis = intent.getBooleanExtra("rndis", false)
                usbMidi = intent.getBooleanExtra("midi", false)
                usbAccessory = intent.getBooleanExtra("accessory", false)
                refreshPages()
            }
        }
        runCatching {
            context.registerReceiver(receiver, IntentFilter(USB_STATE_ACTION))
            usbStateReceiverRegistered = true
        }.onFailure { unavailable("USB state receiver", it.message) }
    }

    private fun isUsbNotification(sbn: StatusBarNotification): Boolean {
        val notification = sbn.notification
        if (sbn.packageName !in setOf("android", "com.android.systemui", "com.android.mtp", "com.sec.usbsettings")) {
            return false
        }
        val channel = notification.channelId.orEmpty()
        if (channel == "DEVELOPER") return false
        if (channel == "USB" || channel == "UsbDevNoti") return true
        val text = buildString {
            append(notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty())
            append(' ')
            append(notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty())
        }.lowercase()
        if ("debug" in text || "调试" in text) return false
        return "usb" in text || "mtp" in text || "传输文件" in text || "usb 连接" in text
    }

    private fun usbStatusNotification(context: Context): CoverNotification {
        val english = context.resources.configuration.locales[0].language == "en"
        val function = when {
            usbMtp -> "mtp"
            usbPtp -> "ptp"
            usbRndis -> "rndis"
            usbMidi -> "midi"
            usbAccessory -> "accessory"
            else -> "charging"
        }
        return CoverNotification(
            key = USB_STATUS_KEY,
            packageName = "android",
            appName = if (english) "Android System" else "Android 系统",
            title = usbStatusTitle(english, function),
            text = if (english) "Tap to change USB options" else "点按可更改 USB 用途",
            postTime = Long.MAX_VALUE,
            clearable = false
        )
    }

    private fun openUsbSettings() {
        val root = synchronized(pages) { pages.firstOrNull()?.root } ?: return
        val starter = activityStarter?.let { getObjectFieldOrNull(it, "mActivityStarter") }
            ?: return unavailable("USB settings launch", "SystemUI activity starter unavailable")
        runCatching {
            val intent = Intent().setComponent(
                ComponentName("com.android.settings", "com.android.settings.Settings\$UsbDetailsActivity")
            ).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            )
            val options = ActivityOptions.makeBasic().apply {
                (root.display?.displayId ?: CoverDisplayResolver.currentId())?.let { launchDisplayId = it }
            }
            NativeCoverStatusBarHooks.suppressHomeDuringExternalLaunch()
            XposedHelpers.callMethod(
                starter, "startActivityDismissingKeyguard", intent, true, true, null, options, null
            )
            collapseCoverPanelAfterLaunch()
            CoverRuntime.log(SCOPE, "USB settings dispatched through SystemUI activity starter")
        }.onFailure { unavailable("USB settings launch", it.message) }
    }

    private fun notificationEntries(activePipeline: Any): Collection<*>? {
        val collection = getObjectFieldOrNull(activePipeline, "mNotifCollection")
        val candidates = listOfNotNull(activePipeline, collection).flatMap { source ->
            listOfNotNull(
                runCatching {
                    XposedHelpers.callMethod(source, "getAllNotifs") as? Collection<*>
                }.getOrNull(),
                (getObjectFieldOrNull(source, "mNotificationSet") as? Map<*, *>)?.values
            )
        }.toMutableList<Collection<*>>()
        return chooseNotificationEntries(candidates)
    }

    private fun isDismissible(entry: Any, sbn: StatusBarNotification): Boolean {
        if (!notificationFlagsAllowDismissal(sbn.isClearable, sbn.notification.flags)) return false
        if (runCatching { XposedHelpers.callMethod(entry, "isClearable") }.getOrNull() == false) {
            return false
        }

        val row = getObjectFieldOrNull(entry, "row") ?: return true
        return runCatching {
            XposedHelpers.callMethod(row, "canViewBeDismissed\$1") as? Boolean
        }.getOrNull() ?: true
    }

    private fun isSensitive(entry: Any): Boolean {
        if (getObjectFieldOrNull(entry, "mIsLockscreenSecret") == true) return true
        val stateFlow = getObjectFieldOrNull(entry, "mSensitive") ?: return false
        return runCatching {
            XposedHelpers.callMethod(stateFlow, "getValue") as? Boolean
        }.getOrNull() == true
    }

    private fun applicationLabel(context: Context, packageName: String): String = runCatching {
        val info = context.packageManager.getApplicationInfo(packageName, 0)
        context.packageManager.getApplicationLabel(info).toString()
    }.getOrDefault(packageName)

    private fun getObjectFieldOrNull(instance: Any, field: String): Any? = runCatching {
        XposedHelpers.getObjectField(instance, field)
    }.getOrNull()

    private fun getFloatFieldOrNull(instance: Any, field: String): Float? = runCatching {
        XposedHelpers.getFloatField(instance, field)
    }.getOrNull()

    private fun unavailable(feature: String, reason: String?) {
        CoverRuntime.log(SCOPE, "$feature unavailable: ${reason ?: "unknown"}")
    }
}

internal fun chooseNotificationEntries(candidates: List<Collection<*>>): Collection<*> =
    candidates.maxByOrNull(Collection<*>::size) ?: emptyList<Any>()

internal fun notificationSnapshotChanged(
    previous: List<CoverNotificationSnapshot>,
    current: List<CoverNotificationSnapshot>
): Boolean = previous != current

internal data class CoverNotificationSnapshot(
    val key: String,
    val title: String,
    val text: String,
    val postTime: Long
)

private data class CoverNotification(
    val key: String,
    val packageName: String,
    val appName: String,
    val title: String,
    val text: String,
    val postTime: Long,
    val clearable: Boolean
)

private class CoverNotificationPage(
    val root: ViewGroup,
    private val qsPanel: View,
    private val nativeHeader: View?,
    private val onOpen: (String) -> Unit,
    private val onDismiss: (String) -> Unit,
    private val onDismissAll: () -> Unit,
    private val onCollapsePanel: () -> Unit,
    private val onDisposed: (CoverNotificationPage) -> Unit,
    private val snapshotProvider: () -> List<CoverNotification>
) {
    private val context = root.context
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val minimumFlingVelocity = ViewConfiguration.get(context).scaledMinimumFlingVelocity
    private val page = FrameLayout(context)
    private val list = LinearLayout(context)

    // Follow the system light/dark theme instead of hardcoding dark cards.
    private val nightMode = (context.resources.configuration.uiMode and
        Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    private val colorCardBg = if (nightMode) Color.rgb(28, 31, 38) else Color.argb(240, 250, 251, 253)
    private val colorChipBg = Color.rgb(35, 39, 47)
    // Header chrome sits on the QS window frost (always dark), so it follows
    // the count badge: light text on a dark chip, not the system light-theme
    // black that becomes unreadable on the blur.
    private val colorHeaderText = Color.WHITE
    private val colorHeaderMuted = Color.rgb(211, 215, 222)
    private val colorTextPrimary = if (nightMode) Color.WHITE else Color.rgb(23, 26, 31)
    private val colorTextSecondary = if (nightMode) Color.rgb(211, 215, 222) else Color.rgb(68, 74, 84)
    private val colorTextTertiary = if (nightMode) Color.rgb(166, 173, 184) else Color.rgb(112, 119, 130)
    private val colorPageDim = if (nightMode) Color.argb(60, 8, 10, 14) else Color.argb(46, 244, 246, 250)
    private val scroll = ScrollView(context)
    private val count = TextView(context)
    private val clearAll = TextView(context)
    private val notificationCards = mutableSetOf<View>()
    private val autoRefreshHandler = Handler(Looper.getMainLooper())
    private val autoRefreshIntervalMs = 2000L
    private var lastNotifications: List<CoverNotification> = emptyList()
    private var lastKeyguardLocked = isKeyguardLocked()
    private var disposed = false
    private val rootAttachListener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) = Unit

        override fun onViewDetachedFromWindow(view: View) {
            dispose("root detached")
        }
    }
    private val autoRefreshRunnable = object : Runnable {
        override fun run() {
            if (disposed) return
            runCatching {
                val keyguardLocked = isKeyguardLocked()
                if (keyguardLocked != lastKeyguardLocked) {
                    lastKeyguardLocked = keyguardLocked
                    val notifications = snapshotProvider()
                    resetToPrimaryPage("keyguard changed")
                    refresh(notifications)
                } else if (notificationPageSelected || horizontalDrag || offset != 0f) {
                    refreshIfChanged(snapshotProvider())
                }
            }
            if (!disposed) autoRefreshHandler.postDelayed(this, autoRefreshIntervalMs)
        }
    }

    private var downX = 0f
    private var downY = 0f
    private var startOffset = 0f
    private var offset = 0f
    private val qsChildBaseTranslationX = IdentityHashMap<View, Float>()
    private var pagerNativeBlurRestored = false
    private var loggedQsContentOffset = false
    private var pagerPreDrawInstalled = false
    private var pagerWidth = 0
    private var pagerRotation = -1
    private var headerMirror: NativeHeaderMirrorView? = null
    private val pagerPreDrawListener = ViewTreeObserver.OnPreDrawListener {
        if (!disposed) {
            syncPagerGeometry()
            headerMirror?.syncSourceGeometry()
            if (kotlin.math.abs(offset) >= 0.5f) applyQsContentOffset(offset)
        }
        true
    }
    private var panelTranslationY = 0f
    private var panelExpansionFraction = 0f
    private var panelExpansionAlpha = 1f
    private var lastPanelExpansionBucket = -1
    private var horizontalDrag = false
    private var verticalDrag = false
    private var notificationContentOwnsGesture = false
    private var nativeHorizontalControlOwnsGesture = false
    private var nativeVerticalControlOwnsGesture = false
    private var notificationPageSelected = false
    private var velocityTracker: VelocityTracker? = null
    private var settleAnimator: ValueAnimator? = null

    fun attach() {
        if (disposed) return
        root.addOnAttachStateChangeListener(rootAttachListener)
        autoRefreshHandler.removeCallbacks(autoRefreshRunnable)
        autoRefreshHandler.postDelayed(autoRefreshRunnable, autoRefreshIntervalMs)
        page.setBackgroundColor(Color.TRANSPARENT)
        page.visibility = View.INVISIBLE
        page.alpha = 0f
        page.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        page.addView(buildContent(), FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        root.addView(page, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        installPagerPreDraw()
        root.post { syncPagerGeometry() }
    }

    fun onPanelExpansion(fraction: Float) {
        if (disposed) return
        val normalizedFraction = fraction.coerceIn(0f, 1f)
        // Native SemBlurInfo lives on qsPanel. Keep that host still and only
        // slide QS children / the notification overlay like a pager. A shared
        // frost layer behind the panel stays screen-anchored so the gaussian
        // blur does not leave with the QS tiles.
        if (normalizedFraction <= 0f) {
            panelExpansionFraction = 0f
            dismissCollapsedOverlay("panel collapsed")
            return
        }
        if (panelExpansionFraction <= 0f && !notificationPageSelected) {
            // Fresh pull-down: run the full offset reset so pager translation,
            // blur anchor and the QS editor-entry visibility all return to the
            // primary-page state together.
            applyOffset(0f)
        }
        panelExpansionFraction = normalizedFraction
        panelTranslationY = qsPanel.translationY
        panelExpansionAlpha = 1f
        applyPageVisualState()

        val expansionBucket = (normalizedFraction * 4f).toInt().coerceIn(0, 4)
        if (expansionBucket != lastPanelExpansionBucket) {
            lastPanelExpansionBucket = expansionBucket
            CoverRuntime.log(
                "CoverNotificationPage",
                "native vertical transform fraction=$normalizedFraction " +
                    "translationY=$panelTranslationY alpha=$panelExpansionAlpha"
            )
        }
    }

    fun onPanelCollapseRequested() {
        // Keep the visible page and QS scroll offset while the native panel
        // animates closed. Resetting here jumps to the QS page/top first,
        // then collapses — the user sees a page snap before the shade leaves.
        if (disposed) return
        cancelSettleAnimation()
        horizontalDrag = false
        verticalDrag = false
        recycleVelocityTracker()
    }

    fun onBackPressed() {
        // BACK collapses the native panel. Behave exactly like the swipe-up
        // collapse: keep the current page visible while the shade animates
        // away instead of snapping back to the QS page first.
        onPanelCollapseRequested()
    }

    fun refresh(notifications: List<CoverNotification>) {
        if (disposed) return
        lastNotifications = notifications
        val hasClearableNotifications = notifications.any(CoverNotification::clearable)
        count.text = notifications.size.toString()
        clearAll.visibility = if (hasClearableNotifications) View.VISIBLE else View.GONE
        clearAll.isEnabled = hasClearableNotifications
        notificationCards.clear()
        list.removeAllViews()
        if (notifications.isEmpty()) {
            list.addView(textView("没有通知", 15f, colorTextTertiary).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(64), 0, dp(64))
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            return
        }
        notifications.forEach { notification ->
            list.addView(notificationCard(notification), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(12), dp(4), dp(12), dp(4)) })
        }
    }

    private fun refreshIfChanged(notifications: List<CoverNotification>) {
        if (notifications != lastNotifications) refresh(notifications)
    }

    fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (panelExpansionFraction < 0.98f) {
            if (
                event.actionMasked == MotionEvent.ACTION_UP ||
                event.actionMasked == MotionEvent.ACTION_CANCEL
            ) {
                recycleVelocityTracker()
            }
            return false
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                beginGesture(event)
                NativeCoverQuickSettingsEditorHooks.beginQsVerticalGesture(qsPanel, event)
            }
            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(event)
                if (!horizontalDrag && !verticalDrag) {
                    val dx = event.x - downX
                    val dy = event.y - downY
                    if (max(abs(dx), abs(dy)) > touchSlop) {
                        if (coverNotificationOwnsHorizontalGesture(dx, dy, touchSlop, root.width)) {
                            if (nativeHorizontalControlOwnsGesture) return false
                            horizontalDrag = true
                            cancelSettleAnimation()
                            ensurePageVisible()
                        } else {
                            if (nativeVerticalControlOwnsGesture) return false
                            verticalDrag = true
                        }
                    }
                }
                if (horizontalDrag) {
                    updateDrag(event.x)
                    return true
                }
                if (verticalDrag && !notificationPageSelected) {
                    return updatePrimaryPageVerticalGesture(event)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (horizontalDrag) {
                    finishGesture(event)
                    return true
                }
                if (verticalDrag && !notificationPageSelected) {
                    val consumed = finishPrimaryPageVerticalGesture(
                        event.actionMasked == MotionEvent.ACTION_UP
                    )
                    recycleVelocityTracker()
                    return consumed
                }
                recycleVelocityTracker()
            }
        }
        return false
    }

    fun onTouchEvent(event: MotionEvent): Boolean {
        if (panelExpansionFraction < 0.98f || nativeVerticalControlOwnsGesture) return false
        if (verticalDrag && !notificationPageSelected) {
            return when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> updatePrimaryPageVerticalGesture(event)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    finishPrimaryPageVerticalGesture(
                        event.actionMasked == MotionEvent.ACTION_UP
                    )
                else -> false
            }
        }
        if (!horizontalDrag) {
            return notificationPageSelected && notificationContentOwnsGesture
        }
        velocityTracker?.addMovement(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> updateDrag(event.x)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> finishGesture(event)
        }
        return true
    }

    private fun updatePrimaryPageVerticalGesture(event: MotionEvent): Boolean =
        NativeCoverQuickSettingsEditorHooks.updateQsVerticalGesture(qsPanel, event)

    private fun finishPrimaryPageVerticalGesture(allowCollapse: Boolean): Boolean {
        val consumed = NativeCoverQuickSettingsEditorHooks.finishQsVerticalGesture(qsPanel)
        val collapseRequested =
            NativeCoverQuickSettingsEditorHooks.consumeQsCollapseRequest(qsPanel)
        if (allowCollapse && collapseRequested) {
            onCollapsePanel()
            CoverRuntime.log(
                "CoverNotificationPage",
                "QS content reached upper limit; completed gesture handed to native collapse"
            )
        }
        return consumed
    }

    fun blocksNativeIntercept(event: MotionEvent): Boolean =
        notificationPageSelected &&
            page.visibility == View.VISIBLE &&
            notificationContentOwnsGesture

    private fun beginGesture(event: MotionEvent) {
        downX = event.x
        downY = event.y
        startOffset = if (notificationPageSelected) -pageWidth().toFloat() else 0f
        horizontalDrag = false
        verticalDrag = false
        notificationContentOwnsGesture = notificationPageSelected &&
            isNotificationCardAt(event.rawX.toInt(), event.rawY.toInt())
        nativeHorizontalControlOwnsGesture = !notificationPageSelected &&
            (NativeCoverQuickSettingsEditorHooks.isQsBrightnessGesture(qsPanel, event) ||
                isNativeHorizontalControlAt(event.rawX.toInt(), event.rawY.toInt()))
        nativeVerticalControlOwnsGesture = !notificationPageSelected &&
            NativeCoverQuickSettingsEditorHooks.isQsBottomGestureRegion(qsPanel, event)
        recycleVelocityTracker()
        velocityTracker = VelocityTracker.obtain().also { it.addMovement(event) }
    }

    private fun isNotificationCardAt(rawX: Int, rawY: Int): Boolean {
        val bounds = Rect()
        return notificationCards.any { card ->
            card.visibility == View.VISIBLE &&
                card.getGlobalVisibleRect(bounds) &&
                bounds.contains(rawX, rawY)
        }
    }

    private fun isNativeHorizontalControlAt(rawX: Int, rawY: Int): Boolean {
        val bounds = Rect()
        fun containsProtectedControl(view: View): Boolean {
            if (view.visibility != View.VISIBLE || !view.getGlobalVisibleRect(bounds)) return false
            if (!bounds.contains(rawX, rawY)) return false
            val type = view.javaClass.name
            val protected = view is SeekBar ||
                view is HorizontalScrollView ||
                type.contains("Brightness", ignoreCase = true) ||
                type.contains("SeekBar", ignoreCase = true) ||
                type.contains("ViewPager", ignoreCase = true) ||
                type.contains("Slider", ignoreCase = true)
            if (protected) return true
            if (view !is ViewGroup) return false
            for (index in view.childCount - 1 downTo 0) {
                if (containsProtectedControl(view.getChildAt(index))) return true
            }
            return false
        }
        return containsProtectedControl(qsPanel)
    }

    private fun updateDrag(x: Float) {
        val width = pageWidth().toFloat()
        applyOffset((startOffset + x - downX).coerceIn(-width, 0f))
    }

    private fun finishGesture(event: MotionEvent) {
        velocityTracker?.addMovement(event)
        velocityTracker?.computeCurrentVelocity(1000)
        val velocityX = velocityTracker?.xVelocity ?: 0f
        val width = pageWidth().toFloat()
        val progress = if (width == 0f) 0f else -offset / width
        val targetNotifications = if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
            startOffset < 0f
        } else {
            when {
                abs(velocityX) >= minimumFlingVelocity -> velocityX < 0f
                else -> progress >= 0.35f
            }
        }
        settle(targetNotifications)
        horizontalDrag = false
        verticalDrag = false
        recycleVelocityTracker()
    }

    private fun settle(showNotifications: Boolean) {
        val target = if (showNotifications) -pageWidth().toFloat() else 0f
        notificationPageSelected = showNotifications
        if (showNotifications) {
            refresh(snapshotProvider())
            page.postDelayed({
                if (!disposed && notificationPageSelected) refresh(snapshotProvider())
            }, 360L)
        }
        cancelSettleAnimation()
        settleAnimator = ValueAnimator.ofFloat(offset, target).apply {
            duration = 280L
            interpolator = OvershootInterpolator(0.55f)
            addUpdateListener { applyOffset(it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (animation !== settleAnimator) return
                    settleAnimator = null
                    if (showNotifications) {
                        page.visibility = View.VISIBLE
                        page.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
                    } else {
                        resetToPrimaryPage(
                            reason = "horizontal return to QS",
                            restoreQsBlur = true,
                            preserveQsScroll = true
                        )
                    }
                }
            })
            start()
        }
    }

    private fun resetToPrimaryPage(
        reason: String,
        restoreQsBlur: Boolean = false,
        preserveQsScroll: Boolean = false
    ) {
        val collapsing = !disposed &&
            panelExpansionFraction > 0f &&
            !restoreQsBlur
        if (collapsing) {
            CoverRuntime.log(
                "CoverNotificationPage",
                "defer page reset during collapse: $reason fraction=$panelExpansionFraction"
            )
            return
        }
        val hadInteractionResidue =
            offset != 0f || notificationPageSelected ||
                horizontalDrag || verticalDrag || notificationContentOwnsGesture ||
                nativeHorizontalControlOwnsGesture || nativeVerticalControlOwnsGesture ||
                velocityTracker != null || settleAnimator != null
        cancelSettleAnimation()
        pagerNativeBlurRestored = false
        if (restoreQsBlur) NativeCoverStatusBarHooks.restoreCoverPanelBlur(qsPanel)
        notificationPageSelected = false
        horizontalDrag = false
        verticalDrag = false
        notificationContentOwnsGesture = false
        nativeHorizontalControlOwnsGesture = false
        nativeVerticalControlOwnsGesture = false
        recycleVelocityTracker()
        // Returning horizontally from the notification page must not scroll
        // the QS content back to the top; keep the user's scroll offset.
        if (!preserveQsScroll) {
            NativeCoverQuickSettingsEditorHooks.resetQsVerticalScroll(qsPanel)
        }
        applyOffset(0f)
        page.visibility = View.INVISIBLE
        page.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        if (hadInteractionResidue) {
            CoverRuntime.log("CoverNotificationPage", "notification page reset: $reason")
        }
    }

    private fun dismissCollapsedOverlay(reason: String) {
        // QS collapse leaves the native window in its last animated position and
        // just goes away. Snapping back to the QS page / window blur here would
        // flash a gaussian layer after the notification content has already left.
        cancelSettleAnimation()
        notificationPageSelected = false
        horizontalDrag = false
        verticalDrag = false
        notificationContentOwnsGesture = false
        nativeHorizontalControlOwnsGesture = false
        nativeVerticalControlOwnsGesture = false
        recycleVelocityTracker()
        offset = 0f
        resetQsContentOffset()
        pagerNativeBlurRestored = false
        // Bypassing applyOffset above means the QS editor entry must be told
        // explicitly that the primary page owns the panel again; otherwise
        // primaryPageVisible stays false and the 编辑 button never returns.
        NativeCoverQuickSettingsEditorHooks.setPrimaryPageVisible(root, true)
        page.alpha = 0f
        page.visibility = View.INVISIBLE
        page.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        // Panel is fully gone: restoring the native layout here is invisible
        // and gives the next pull-down a clean top-anchored QS.
        NativeCoverQuickSettingsEditorHooks.resetQsVerticalScroll(qsPanel)
        CoverRuntime.log("CoverNotificationPage", "collapsed overlay dismissed: $reason")
    }

    private fun dispose(reason: String) {
        if (disposed) return
        disposed = true
        autoRefreshHandler.removeCallbacksAndMessages(null)
        removePagerPreDraw()
        resetToPrimaryPage(reason)
        root.removeOnAttachStateChangeListener(rootAttachListener)
        (page.parent as? ViewGroup)?.removeView(page)
        notificationCards.clear()
        list.removeAllViews()
        page.removeAllViews()
        XposedHelpers.removeAdditionalInstanceField(
            root,
            COVER_NOTIFICATION_PAGER_OFFSET_MARK
        )
        onDisposed(this)
        CoverRuntime.log("CoverNotificationPage", "notification page disposed: $reason")
    }

    private fun isKeyguardLocked(): Boolean =
        context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true

    private fun ensurePageVisible() {
        page.visibility = View.VISIBLE
        page.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
    }

    private fun syncPagerGeometry() {
        val width = pageWidth()
        val rotation = root.display?.rotation ?: 0
        if (width == pagerWidth && rotation == pagerRotation) return
        val previousWidth = pagerWidth
        val previousRotation = pagerRotation
        pagerWidth = width
        pagerRotation = rotation
        cancelSettleAnimation()
        horizontalDrag = false
        verticalDrag = false
        notificationContentOwnsGesture = false
        nativeHorizontalControlOwnsGesture = false
        nativeVerticalControlOwnsGesture = false
        recycleVelocityTracker()
        resetQsContentOffset()
        offset = coverNotificationPagerOffsetPx(notificationPageSelected, width)
        applyOffset(offset)
        headerMirror?.syncSourceGeometry()
        CoverRuntime.log(
            "CoverNotificationPage",
            "pager geometry ${previousWidth}x@$previousRotation -> ${width}x@$rotation " +
                "selected=$notificationPageSelected offset=$offset"
        )
    }

    private fun installPagerPreDraw() {
        if (pagerPreDrawInstalled) return
        val observer = qsPanel.viewTreeObserver.takeIf { it.isAlive } ?: return
        observer.addOnPreDrawListener(pagerPreDrawListener)
        pagerPreDrawInstalled = true
    }

    private fun removePagerPreDraw() {
        if (!pagerPreDrawInstalled) return
        val observer = qsPanel.viewTreeObserver
        if (observer.isAlive) observer.removeOnPreDrawListener(pagerPreDrawListener)
        pagerPreDrawInstalled = false
    }

    /**
     * Native SemBlurInfo lives on qsPanel. Keep that host still and slide only
     * its children so QS and the notification page move like a pager. A
     * screen-anchored frost layer behind the panel carries the window blur
     * while pages are offset. Pre-draw re-applies the offset because QS layout
     * resets child translationX every frame.
     */
    private fun applyQsContentOffset(contentOffset: Float) {
        val panelGroup = qsPanel as? ViewGroup ?: return
        // Pull-down must not write translationX: Samsung positions the media
        // panel with translationX, and forcing 0 during expansion stacks it
        // on the brightness slider.
        if (kotlin.math.abs(contentOffset) < 0.5f) {
            resetQsContentOffset()
            return
        }
        XposedHelpers.setAdditionalInstanceField(
            root,
            COVER_NOTIFICATION_PAGER_OFFSET_MARK,
            contentOffset
        )
        val nativeBlurHost = NativeCoverStatusBarHooks.nativeBlurHostWithin(qsPanel)
        for (index in 0 until panelGroup.childCount) {
            val child = panelGroup.getChildAt(index)
            if (child === nativeBlurHost) continue
            val base = qsChildBaseTranslationX.getOrPut(child) { child.translationX }
            val target = base + contentOffset
            if (child.translationX != target) child.translationX = target
        }
        if (root !== panelGroup) {
            for (index in 0 until root.childCount) {
                val child = root.getChildAt(index)
                if (
                    child === qsPanel ||
                    child === page
                ) continue
                if (child.contentDescription?.toString() != "编辑快捷设置磁贴") continue
                val base = qsChildBaseTranslationX.getOrPut(child) { 0f }
                val target = base + contentOffset
                if (child.translationX != target) child.translationX = target
            }
        }
        if (contentOffset != 0f && !loggedQsContentOffset) {
            loggedQsContentOffset = true
            CoverRuntime.log(
                "CoverNotificationPage",
                "qs pager offset=$contentOffset children=${panelGroup.childCount} " +
                    "panelTx=${qsPanel.translationX}"
            )
        }
        if (contentOffset == 0f && !horizontalDrag && !notificationPageSelected) {
            resetQsContentOffset()
        }
    }

    private fun resetQsContentOffset() {
        XposedHelpers.setAdditionalInstanceField(
            root,
            COVER_NOTIFICATION_PAGER_OFFSET_MARK,
            0f
        )
        qsChildBaseTranslationX.forEach { (child, base) ->
            if (child.isAttachedToWindow && child.translationX != base) {
                child.translationX = base
            }
        }
        qsChildBaseTranslationX.clear()
        loggedQsContentOffset = false
    }

    private fun applyOffset(value: Float) {
        val width = pageWidth().toFloat()
        offset = value.coerceIn(-width, 0f)
        if (qsPanel.translationX != 0f) qsPanel.translationX = 0f
        applyQsContentOffset(offset)
        if (offset < 0f) {
            if (!pagerNativeBlurRestored) {
                pagerNativeBlurRestored = true
                NativeCoverStatusBarHooks.restoreCoverPanelBlur(qsPanel)
            }
        } else if (!horizontalDrag && !notificationPageSelected) {
            pagerNativeBlurRestored = false
        }
        page.translationX = width + offset
        NativeCoverQuickSettingsEditorHooks.setPrimaryPageVisible(
            root,
            offset > -width + 8f
        )
        applyPageVisualState()
        if (offset < 0f) ensurePageVisible()
        if (offset == 0f && !horizontalDrag && !notificationPageSelected) {
            page.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
    }

    private fun applyPageVisualState() {
        // Pages sit side by side and slide like a pager: no alpha crossfade.
        if (offset < 0f || notificationPageSelected) page.alpha = 1f
        page.translationY = qsPanel.translationY
    }

    private fun buildContent(): View {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(12))
        }
        nativeHeader?.let { source ->
            val sourceHeight = source.height.takeIf { it > 0 }
                ?: source.measuredHeight.takeIf { it > 0 }
                ?: dp(48)
            val mirror = NativeHeaderMirrorView(context, source, root, page).also {
                headerMirror = it
            }
            container.addView(
                mirror,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, sourceHeight)
            )
        }
        val header = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(2), dp(18), dp(4))
        }
        header.addView(textView("通知", 17f, colorHeaderText).apply {
            setTypeface(typeface, Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        clearAll.text = "全部清除"
        clearAll.textSize = 11f
        clearAll.setTextColor(colorHeaderMuted)
        clearAll.gravity = Gravity.CENTER
        clearAll.background = roundedBackground(colorChipBg, 20f)
        clearAll.setPadding(dp(8), dp(2), dp(8), dp(2))
        clearAll.setOnClickListener { onDismissAll() }
        header.addView(clearAll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginEnd = dp(8) })
        count.setTextColor(colorHeaderMuted)
        count.textSize = 12f
        count.gravity = Gravity.CENTER
        count.background = roundedBackground(colorChipBg, 20f)
        count.setPadding(dp(8), dp(2), dp(8), dp(2))
        header.addView(count)
        container.addView(header, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        scroll.isFillViewport = true
        scroll.overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        list.orientation = LinearLayout.VERTICAL
        list.setPadding(0, dp(3), 0, dp(14))
        scroll.addView(list, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        container.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))
        return container
    }

    private fun notificationCard(notification: CoverNotification): View {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedBackground(colorCardBg, 18f)
            contentDescription = listOf(notification.appName, notification.title, notification.text)
                .filter(String::isNotBlank)
                .joinToString(", ")
            accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = "android.app.Notification"
                }
            }
            setOnClickListener { onOpen(notification.key) }
        }
        val icon = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setImageDrawable(runCatching {
                context.packageManager.getApplicationIcon(notification.packageName)
            }.getOrNull())
        }
        card.addView(icon, LinearLayout.LayoutParams(dp(30), dp(30)).apply {
            marginEnd = dp(10)
        })
        val textColumn = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        textColumn.addView(textView(notification.appName, 11f, colorTextTertiary))
        textColumn.addView(textView(notification.title, 14f, colorTextPrimary).apply {
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 2
        })
        if (notification.text.isNotBlank()) {
            textColumn.addView(textView(notification.text, 12.5f, colorTextSecondary).apply {
                maxLines = 2
            })
        }
        card.addView(textColumn, LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            1f
        ))
        if (notification.clearable) {
            card.addView(textView("清除", 11f, colorTextTertiary).apply {
                gravity = Gravity.CENTER
                setPadding(dp(10), dp(8), dp(2), dp(8))
                setOnClickListener { onDismiss(notification.key) }
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        notificationCards += card
        return card
    }

    private fun textView(text: String, size: Float, color: Int) = TextView(context).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        includeFontPadding = false
    }

    private fun roundedBackground(color: Int, radiusDp: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp.toInt()).toFloat()
    }

    private fun pageWidth(): Int = root.width.takeIf { it > 0 }
        ?: context.resources.displayMetrics.widthPixels

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density + 0.5f).toInt()

    private fun recycleVelocityTracker() {
        velocityTracker?.recycle()
        velocityTracker = null
    }

    private fun cancelSettleAnimation() {
        val animator = settleAnimator
        settleAnimator = null
        animator?.cancel()
    }
}

private class NativeHeaderMirrorView(
    context: Context,
    private val source: View,
    private val root: View,
    private val page: View
) : View(context) {
    private val refresh = object : Runnable {
        override fun run() {
            invalidate()
            if (isAttachedToWindow) postDelayed(this, 1_000L)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        removeCallbacks(refresh)
        post(refresh)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(refresh)
        super.onDetachedFromWindow()
    }

    fun syncSourceGeometry() {
        val targetHeight = source.height.takeIf { it > 0 }
            ?: source.measuredHeight.takeIf { it > 0 }
            ?: return
        val params = layoutParams ?: return
        if (params.height != targetHeight) {
            params.height = targetHeight
            layoutParams = params
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (source.width <= 0 || source.height <= 0) return
        val sourceLocation = IntArray(2)
        val rootLocation = IntArray(2)
        source.getLocationOnScreen(sourceLocation)
        root.getLocationOnScreen(rootLocation)
        val pagerOffset = (
            XposedHelpers.getAdditionalInstanceField(
                root,
                COVER_NOTIFICATION_PAGER_OFFSET_MARK
            ) as? Number
            )?.toFloat() ?: 0f
        val dx = sourceLocation[0] - rootLocation[0] - pagerOffset
        val dy = sourceLocation[1] - rootLocation[1] - page.translationY
        val checkpoint = canvas.save()
        canvas.translate(dx, dy)
        source.draw(canvas)
        canvas.restoreToCount(checkpoint)
    }
}
