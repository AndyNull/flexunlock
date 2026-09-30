package com.flexunlock.dexlsp

import android.app.AndroidAppHelper
import android.app.Application
import android.content.Context
import android.database.ContentObserver
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Display
import com.flexunlock.dexlsp.config.CoverDisplayConfig
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean

internal data class CoverDisplaySnapshot(
    val id: Int,
    val width: Int,
    val height: Int,
    val rotation: Int,
    val uniqueId: String?,
    val name: String? = null,
    val type: Int = 0
)

internal sealed interface CoverDisplayResolution {
    data class Resolved(
        val snapshot: CoverDisplaySnapshot,
        val source: CoverDisplaySelectionSource = CoverDisplaySelectionSource.AUTO
    ) : CoverDisplayResolution
    data class Unavailable(val reason: String) : CoverDisplayResolution
    data class Ambiguous(val candidateIds: List<Int>) : CoverDisplayResolution
}

internal fun transactionalCoverDisplay(
    displays: List<DisplayCandidate>,
    transaction: CoverQsTransaction
): DisplayCandidate? {
    val uniqueId = transaction.snapshots.firstOrNull { it.displayId == 1 }?.uniqueId
        ?: return null
    return displays.singleOrNull { it.uniqueId == uniqueId }
}

internal fun selectedDisplayDuringQsTransition(
    displays: List<DisplayCandidate>,
    transaction: CoverQsTransaction,
    manualIdentity: DisplayStableIdentity?
): DisplayCandidate? {
    if (manualIdentity != null) {
        val manual = CoverDisplaySelector.select(
            displays = displays,
            manualIdentity = manualIdentity
        )
        if (manual is CoverDisplaySelection.Resolved &&
            manual.source == CoverDisplaySelectionSource.MANUAL
        ) return manual.candidate
    }
    return transactionalCoverDisplay(displays, transaction)
}

internal object CoverDisplayResolver {
    private const val TAG = "FlexUnlock-DisplayResolver"
    private const val INITIALIZE_RETRY_DELAY_MS = 1_000L
    private const val MAX_INITIALIZE_RETRIES = 30

    private val processHookInstalled = AtomicBoolean(false)
    private val listenerRegistered = AtomicBoolean(false)
    private val settingsObserverRegistered = AtomicBoolean(false)
    private val initializeRetryScheduled = AtomicBoolean(false)
    private val listeners = CopyOnWriteArraySet<(CoverDisplaySnapshot?, CoverDisplaySnapshot?) -> Unit>()
    private val stateLock = Any()

    @Volatile
    private var resolution: CoverDisplayResolution =
        CoverDisplayResolution.Unavailable("not-scanned")

    @Volatile
    private var displayManager: DisplayManager? = null

    @Volatile
    private var settingsContext: Context? = null

    @Volatile
    private var manualIdentity: DisplayStableIdentity? = null

    @Volatile
    private var lastDisplays: List<DisplayCandidate> = emptyList()

    @Volatile
    private var initializeRetryAttempts: Int = 0

    fun installProcess() {
        refresh("package-load", collectDisplaysFromGlobal())
        AndroidAppHelper.currentApplication()?.let(::initialize)
        if (!processHookInstalled.compareAndSet(false, true)) return
        runCatching {
            XposedHelpers.findAndHookMethod(
                Application::class.java,
                "attach",
                Context::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        (param.args.firstOrNull() as? Context)?.let(::initialize)
                    }
                }
            )
        }.onFailure { error ->
            log("application attach observation unavailable: ${error.message}")
        }
    }

    fun initialize(context: Context) {
        runCatching {
            val serviceContext = context.applicationContext ?: context
            val manager = serviceContext.getSystemService(DisplayManager::class.java)
                ?: context.getSystemService(DisplayManager::class.java)
                ?: error("DisplayManager unavailable")
            val displays = manager.displays.mapNotNull(::candidateOf)
            displayManager = manager
            settingsContext = serviceContext
            manualIdentity = CoverDisplayConfig.readIdentity(serviceContext)
            registerSettingsObserver(serviceContext)
            refresh("context-ready", displays)
            if (registerListener(manager, context)) {
                initializeRetryAttempts = 0
            }
        }.onFailure { error ->
            scheduleInitializeRetry(context, error)
        }
    }

    private fun registerSettingsObserver(context: Context) {
        if (!settingsObserverRegistered.compareAndSet(false, true)) return
        runCatching {
            context.contentResolver.registerContentObserver(
                Settings.Global.getUriFor(CoverDisplayConfig.SETTINGS_IDENTITY_KEY),
                false,
                object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean) {
                        refreshConfiguration("settings-observer")
                    }
                }
            )
            context.contentResolver.registerContentObserver(
                Settings.Global.getUriFor(CoverQsModeConfig.SETTINGS_TRANSACTION_KEY),
                false,
                object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean) {
                        refreshConfiguration("qs-transaction-observer")
                    }
                }
            )
        }.onFailure { error ->
            settingsObserverRegistered.set(false)
            log("display identity observer unavailable: ${error.message}")
        }
    }

    fun refreshConfiguration(reason: String = "configuration") {
        val context = settingsContext ?: return
        manualIdentity = CoverDisplayConfig.readIdentity(context)
        refreshFromManager(reason)
    }

    private fun registerListener(manager: DisplayManager, context: Context): Boolean {
        if (!listenerRegistered.compareAndSet(false, true)) {
            return listenerRegistered.get()
        }
        return runCatching {
            manager.registerDisplayListener(
                object : DisplayManager.DisplayListener {
                    override fun onDisplayAdded(displayId: Int) {
                        refreshFromManager("display-added:$displayId")
                    }

                    override fun onDisplayChanged(displayId: Int) {
                        refreshFromManager("display-changed:$displayId")
                    }

                    override fun onDisplayRemoved(displayId: Int) {
                        refreshFromManager("display-removed:$displayId")
                    }
                },
                Handler(Looper.getMainLooper())
            )
            true
        }.getOrElse { error ->
            listenerRegistered.set(false)
            scheduleInitializeRetry(context, error)
            false
        }
    }

    private fun scheduleInitializeRetry(context: Context, error: Throwable) {
        if (listenerRegistered.get()) return
        val attempt = initializeRetryAttempts + 1
        initializeRetryAttempts = attempt
        if (attempt > MAX_INITIALIZE_RETRIES) {
            log(
                "display initialization unavailable after $MAX_INITIALIZE_RETRIES retries: " +
                    "${error.javaClass.simpleName}: ${error.message}"
            )
            return
        }
        if (!initializeRetryScheduled.compareAndSet(false, true)) return
        if (attempt == 1) {
            log(
                "display initialization deferred: ${error.javaClass.simpleName}: " +
                    "${error.message}"
            )
        }
        Handler(Looper.getMainLooper()).postDelayed(
            {
                initializeRetryScheduled.set(false)
                initialize(context)
            },
            INITIALIZE_RETRY_DELAY_MS
        )
    }

    fun current(): CoverDisplaySnapshot? =
        (resolution as? CoverDisplayResolution.Resolved)?.snapshot

    fun currentId(): Int? = current()?.id

    fun interactionDisplayId(sourceDisplayId: Int? = null): Int? {
        val source = sourceDisplayId?.takeIf { it >= 0 }
        source?.takeIf(::isScrcpyDisplayId)?.let { return it }
        if (source != null && source == currentId()) return source
        if (source != null && source != 0) return source
        lastDisplays.firstOrNull(::isScrcpyDisplay)?.id?.let { return it }
        return currentId()
    }

    fun isScrcpyDisplayId(displayId: Int?): Boolean =
        displayId != null && lastDisplays.any { it.id == displayId && isScrcpyDisplay(it) }

    fun matches(displayId: Int?): Boolean {
        if (displayId == null) return false
        return currentId() == displayId
    }

    fun matchesBuiltIn(displayId: Int?): Boolean =
        matches(displayId) && current()?.type == 1

    fun isBuiltInTarget(): Boolean = current()?.type == 1

    fun resolution(): CoverDisplayResolution = resolution

    fun configuredIdentity(): DisplayStableIdentity? = manualIdentity

    fun selectableCandidates(): List<DisplayCandidate> =
        CoverDisplaySelector.selectableCandidates(lastDisplays)

    fun addListener(
        listener: (CoverDisplaySnapshot?, CoverDisplaySnapshot?) -> Unit
    ) {
        listeners.add(listener)
    }

    private fun refreshFromManager(reason: String) {
        val manager = displayManager ?: return
        runCatching {
            manager.displays.mapNotNull(::candidateOf)
        }.onSuccess { displays ->
            refresh(reason, displays)
        }.onFailure { error ->
            log(
                "display refresh skipped reason=$reason: " +
                    "${error.javaClass.simpleName}: ${error.message}"
            )
        }
    }

    private fun refresh(reason: String, displays: List<DisplayCandidate>) {
        lastDisplays = displays
        val transaction = settingsContext?.let(CoverQsModeConfig::readTransaction)
        if (transaction?.isStableOriginal == false) {
            val cover = selectedDisplayDuringQsTransition(
                displays,
                transaction,
                manualIdentity
            )
            publishResolution(
                reason,
                displays,
                cover?.let {
                    CoverDisplayResolution.Resolved(
                        it.toSnapshot(),
                        CoverDisplaySelectionSource.AUTO
                    )
                } ?: CoverDisplayResolution.Unavailable("full-qs-cover-missing")
            )
            return
        }
        val previousSnapshot = current()
        val previousCandidate = previousSnapshot?.let { snapshot ->
            displays.firstOrNull { candidate ->
                when {
                    snapshot.uniqueId != null -> candidate.uniqueId == snapshot.uniqueId
                    else -> candidate.id == snapshot.id
                }
            }
        }
        val next = when (
            val selected = CoverDisplaySelector.select(
                displays,
                previousCandidate,
                manualIdentity
            )
        ) {
            is CoverDisplaySelection.Resolved -> CoverDisplayResolution.Resolved(
                selected.candidate.toSnapshot(),
                selected.source
            )
            is CoverDisplaySelection.Unavailable ->
                CoverDisplayResolution.Unavailable(selected.reason)
            is CoverDisplaySelection.Ambiguous -> CoverDisplayResolution.Ambiguous(
                selected.candidates.map { it.id }
            )
        }
        publishResolution(reason, displays, next)
    }

    private fun publishResolution(
        reason: String,
        displays: List<DisplayCandidate>,
        next: CoverDisplayResolution
    ) {
        val previousSnapshot = current()
        val changed = synchronized(stateLock) {
            if (resolution == next) return
            resolution = next
            true
        }
        if (!changed) return

        val nextSnapshot = (next as? CoverDisplayResolution.Resolved)?.snapshot
        log(
            "resolution changed reason=$reason state=${describe(next)} " +
                "displays=${displays.joinToString(prefix = "[", postfix = "]", transform = ::describe)}"
        )
        listeners.forEach { listener ->
            runCatching { listener(previousSnapshot, nextSnapshot) }
                .onFailure { error -> log("display change listener failed: ${error.message}") }
        }
    }

    private fun collectDisplaysFromGlobal(): List<DisplayCandidate> = runCatching {
        val managerClass = Class.forName("android.hardware.display.DisplayManagerGlobal")
        val manager = managerClass.getMethod("getInstance").invoke(null)
            ?: return@runCatching emptyList()
        val displays = managerClass.getMethod("getDisplays").invoke(manager) as? Array<*>
            ?: return@runCatching emptyList()
        displays.mapNotNull(::candidateOf)
    }.getOrDefault(emptyList())

    private fun candidateOf(display: Any?): DisplayCandidate? {
        val value = display ?: return null
        val id = intMethod(value, "getDisplayId") ?: return null
        val size = realSizeOf(value)
        val address = objectMethod(value, "getAddress")
        return DisplayCandidate(
            id = id,
            width = size?.x ?: 0,
            height = size?.y ?: 0,
            rotation = intMethod(value, "getRotation") ?: 0,
            uniqueId = stringMethod(value, "getUniqueId"),
            name = stringMethod(value, "getName"),
            flags = intMethod(value, "getFlags") ?: 0,
            type = intMethod(value, "getType") ?: 0,
            displayGroupId = intMethod(value, "getDisplayGroupId") ?: 0,
            physicalAddress = physicalAddressOf(address),
            port = address?.let { intMethod(it, "getPort") }
        )
    }

    private fun realSizeOf(instance: Any): Point? = runCatching {
        Point().also { point ->
            instance.javaClass.getMethod("getRealSize", Point::class.java).invoke(instance, point)
        }.takeIf { point -> point.x > 0 && point.y > 0 }
    }.getOrNull()

    private fun objectMethod(instance: Any, name: String): Any? = runCatching {
        instance.javaClass.getMethod(name).invoke(instance)
    }.getOrNull()

    private fun physicalAddressOf(address: Any?): String? {
        val value = address ?: return null
        val physicalId = runCatching {
            (value.javaClass.getMethod("getPhysicalDisplayId").invoke(value) as? Number)?.toLong()
        }.getOrNull()
        if (physicalId != null) return java.lang.Long.toUnsignedString(physicalId, 16)
        return value.toString().takeIf {
            value.javaClass.simpleName.contains("Physical", ignoreCase = true)
        }
    }

    private fun intMethod(instance: Any, name: String): Int? = runCatching {
        (instance.javaClass.getMethod(name).invoke(instance) as? Number)?.toInt()
    }.getOrNull()

    private fun stringMethod(instance: Any, name: String): String? = runCatching {
        instance.javaClass.getMethod(name).invoke(instance) as? String
    }.getOrNull()

    private fun DisplayCandidate.toSnapshot() = CoverDisplaySnapshot(
        id = id,
        width = width,
        height = height,
        rotation = rotation,
        uniqueId = uniqueId,
        name = name,
        type = type
    )

    private fun describe(value: CoverDisplayResolution): String = when (value) {
        is CoverDisplayResolution.Resolved ->
            "resolved:${value.source}:${describe(value.snapshot)}"
        is CoverDisplayResolution.Unavailable -> "unavailable:${value.reason}"
        is CoverDisplayResolution.Ambiguous -> "ambiguous:${value.candidateIds}"
    }

    private fun describe(value: CoverDisplaySnapshot): String =
        "id=${value.id}:${value.width}x${value.height}:rotation=${value.rotation}:unique=${value.uniqueId}"

    private fun describe(value: DisplayCandidate): String =
        "id=${value.id}:${value.width}x${value.height}:type=${value.type}:" +
            "flags=0x${value.flags.toString(16)}:group=${value.displayGroupId}:" +
            "unique=${value.uniqueId}:name=${value.name}"

    private fun isScrcpyDisplay(candidate: DisplayCandidate): Boolean =
        candidate.type == 5 && candidate.uniqueId?.startsWith(
            "virtual:com.android.shell,2000,scrcpy,",
            ignoreCase = true
        ) == true

    private fun log(message: String) {
        android.util.Log.i(TAG, message)
        XposedBridge.log("$TAG: $message")
    }
}
