package com.flexunlock.dexlsp

import android.app.WallpaperManager
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.view.Window
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal object AodServiceHooks {
    private const val SCOPE = "AOD"
    private const val SUB_LAUNCHER_STATE_MANAGER =
        "com.samsung.android.app.sublauncher.presentation.plugin.support.SubLauncherStateManager"
    private const val LIVE_WALLPAPER_PROVIDER_REPOSITORY = "aod.Nm0"
    private const val COMMON_WALLPAPER_UTILS = "aod.i20"
    private const val CLOCK_HOLDER_WALLPAPER_SELECTOR = "aod.Bl1"
    private const val REFRESH_SHOW_WALLPAPER = "aod.Rx1"
    private const val STATIC_WALLPAPER_TYPE = 0
    // OneUI 8.5 上外屏(sub display)壁纸 which=17(wallpaper_sub_display_orig),
    // 18 是旧锁屏壁纸 which,读 18 会一直拿到默认图。
    private const val COVER_LOCK_WALLPAPER = 17
    private const val KEYGUARD_IMAGE_AUTHORITY = "com.android.systemui.keyguard.image"

    private val hookedRepositoryClasses = mutableSetOf<Class<*>>()
    private val hookedProviderClasses = mutableSetOf<Class<*>>()
    private val hookedSelectorClasses = mutableSetOf<Class<*>>()
    private val hookedWindowRefreshClasses = mutableSetOf<Class<*>>()
    private val pluginInstallScheduled = AtomicBoolean(false)
    private val wallpaperIo = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "flexunlock-aod-wallpaper").apply { isDaemon = true }
    }
    @Volatile private var latestStaticDrawable: Drawable? = null
    @Volatile private var latestSubLauncherWindow: Window? = null
    @Volatile private var shouldShowWallpaper: Boolean = false

    fun scheduleInstallPlugin(classLoader: ClassLoader?) {
        if (classLoader == null) {
            return CoverRuntime.log(SCOPE, "SubLauncher plugin ClassLoader unavailable")
        }
        if (!pluginInstallScheduled.compareAndSet(false, true)) return
        Thread(
            {
                runCatching { installPlugin(classLoader) }
                    .onFailure { error ->
                        pluginInstallScheduled.set(false)
                        CoverRuntime.log(
                            SCOPE,
                            "async plugin install failed: ${error.message}"
                        )
                    }
            },
            "flexunlock-aod-hooks"
        ).apply { isDaemon = true }.start()
    }

    fun installPlugin(classLoader: ClassLoader?) {
        if (classLoader == null) {
            return CoverRuntime.log(SCOPE, "SubLauncher plugin ClassLoader unavailable")
        }
        val stateManagerClass = XposedHelpers.findClassIfExists(
            SUB_LAUNCHER_STATE_MANAGER,
            classLoader
        ) ?: return CoverRuntime.log(SCOPE, "SubLauncher state manager unavailable in plugin")

        installLiveWallpaperFallback(classLoader, stateManagerClass)
        installCoverLockWallpaperProvider(classLoader, stateManagerClass)
        installClockHolderWallpaperSelector(classLoader, stateManagerClass)
        installWindowBackgroundFallback(classLoader, stateManagerClass)
        installWallpaperChangeRefresh(classLoader)
    }

    private fun installLiveWallpaperFallback(
        classLoader: ClassLoader,
        stateManagerClass: Class<*>
    ) {
        val repositoryClass = findCompatClass(
            classLoader = classLoader,
            v85Names = listOf(LIVE_WALLPAPER_PROVIDER_REPOSITORY),
            v8Names = listOf("aod.U70", LIVE_WALLPAPER_PROVIDER_REPOSITORY),
            v7Names = listOf("aod.P40", LIVE_WALLPAPER_PROVIDER_REPOSITORY)
        ) { candidate ->
            candidate.declaredMethods.any { method ->
                method.name == "b" &&
                    method.parameterTypes.contentEquals(arrayOf(String::class.java)) &&
                    Drawable::class.java.isAssignableFrom(method.returnType)
            }
        } ?: return CoverRuntime.log(
            SCOPE,
            "SubLauncher live-wallpaper repository unavailable in plugin"
        )
        synchronized(hookedRepositoryClasses) {
            if (!hookedRepositoryClasses.add(repositoryClass)) return
        }

        runCatching {
            val methods = repositoryClass.declaredMethods.filter { method ->
                method.name == "b" &&
                    method.parameterTypes.contentEquals(arrayOf(String::class.java))
            }
            require(methods.isNotEmpty()) {
                "live-wallpaper drawable method unavailable"
            }
            methods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!CoverRuntime.isBuiltInCoverSessionEligible()) return
                        val componentName = param.args.firstOrNull() as? String ?: return
                        if (componentName.isNotEmpty()) return
                        val target = runCatching {
                            XposedHelpers.getIntField(param.thisObject, "a")
                        }.getOrNull() ?: runCatching {
                            XposedHelpers.getIntField(param.thisObject, "b")
                        }.getOrNull() ?: runCatching {
                            XposedHelpers.getIntField(param.thisObject, "which")
                        }.getOrNull() ?: return
                        val wallpaperManager = runCatching {
                            XposedHelpers.getObjectField(param.thisObject, "c") as? WallpaperManager
                        }.getOrNull() ?: runCatching {
                            XposedHelpers.getObjectField(param.thisObject, "j") as? WallpaperManager
                        }.getOrNull() ?: return
                        val wallpaperType = runCatching {
                            (XposedHelpers.callMethod(
                                wallpaperManager,
                                "semGetWallpaperType",
                                target
                            ) as? Number)?.toInt()
                        }.getOrNull() ?: return
                        if (wallpaperType != STATIC_WALLPAPER_TYPE) return

                        param.result = null
                        CoverRuntime.log(
                            SCOPE,
                            "static cover wallpaper bypassed empty live component " +
                                "target=$target type=$wallpaperType " +
                                "classLoader=${repositoryClass.classLoader}"
                        )
                    }
                })
            }
            CoverRuntime.log(
                SCOPE,
                "SubLauncher static-wallpaper fallback installed " +
                    "state=${stateManagerClass.name} repository=${repositoryClass.name} " +
                    "classLoader=${repositoryClass.classLoader} methods=${methods.size}"
            )
        }.onFailure { error ->
            synchronized(hookedRepositoryClasses) {
                hookedRepositoryClasses.remove(repositoryClass)
            }
            CoverRuntime.log(
                SCOPE,
                "SubLauncher static-wallpaper fallback unavailable: ${error.message}"
            )
        }
    }

    private fun refreshCoverWallpaper(context: Context) {
        wallpaperIo.execute {
            val snapshot = CoverDisplayResolver.current() ?: return@execute
            val width = snapshot.width.takeIf { it > 0 } ?: return@execute
            val height = snapshot.height.takeIf { it > 0 } ?: return@execute
            val uri = Uri.Builder()
                .scheme("content")
                .authority(KEYGUARD_IMAGE_AUTHORITY)
                .appendPath("custom")
                .appendQueryParameter("type", "wallpaper")
                .appendQueryParameter("which", COVER_LOCK_WALLPAPER.toString())
                .appendQueryParameter("width", width.toString())
                .appendQueryParameter("height", height.toString())
                .build()
            val bitmap = runCatching {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream)
                }
            }.getOrNull() ?: return@execute
            val drawable = BitmapDrawable(context.resources, bitmap)
            latestStaticDrawable = drawable
            CoverRuntime.log(SCOPE, "refreshCoverWallpaper sig=" + bitmapSignature(bitmap))
            latestSubLauncherWindow?.decorView?.post {
                if (!shouldShowWallpaper) return@post
                latestSubLauncherWindow?.decorView?.background = drawable
            }
            CoverRuntime.log(
                SCOPE,
                "wallpaper changed, cover provider refreshed uri=$uri result=${bitmap.width}x${bitmap.height}"
            )
        }
    }

    private fun booleanFieldCompat(instance: Any, name: String): Boolean {
        runCatching { return XposedHelpers.getBooleanField(instance, name) }
        return when (
            val value = runCatching {
                XposedHelpers.getObjectField(instance, name)
            }.getOrNull()
        ) {
            is Boolean -> value
            is Number -> value.toInt() != 0
            else -> false
        }
    }

    private fun suspendMethods(candidate: Class<*>): List<java.lang.reflect.Method> =
        candidate.declaredMethods.filter { method ->
            method.name == "invokeSuspend" &&
                method.parameterCount == 1 &&
                !java.lang.reflect.Modifier.isStatic(method.modifiers) &&
                method.returnType != java.lang.Void.TYPE
        }

    private fun bitmapSignature(bitmap: android.graphics.Bitmap): String {
        return runCatching {
            val pts = listOf(
                0 to 0, bitmap.width - 1 to 0, 0 to bitmap.height - 1,
                bitmap.width - 1 to bitmap.height - 1,
                bitmap.width / 2 to bitmap.height / 2
            )
            val parts = pts.map { (x, y) ->
                val px = bitmap.getPixel(x, y)
                "(${(px shr 16) and 0xFF},${(px shr 8) and 0xFF},${px and 0xFF})"
            }
            "corners=${parts.joinToString(" ")}"
        }.getOrElse { "unavailable" }
    }

    private fun installWallpaperChangeRefresh(classLoader: ClassLoader) {
        val activityThreadClass = XposedHelpers.findClassIfExists(
            "android.app.ActivityThread",
            classLoader
        ) ?: return CoverRuntime.log(
            SCOPE,
            "cover wallpaper colors-change refresh unavailable: ActivityThread missing"
        )
        val app = runCatching {
            // Android 16 上字段名可能变化:枚举 ActivityThread 类型静态字段
            val candidate = activityThreadClass.declaredFields.firstOrNull { field ->
                java.lang.reflect.Modifier.isStatic(field.modifiers) &&
                    (field.type == activityThreadClass || field.name.contains("current"))
            }
            if (candidate == null) {
                CoverRuntime.log(
                    SCOPE,
                    "cover wallpaper refresh fields=" +
                        activityThreadClass.declaredFields.map { it.name + ":" + it.type.simpleName }.joinToString(",")
                )
                return@runCatching null
            }
            candidate.isAccessible = true
            candidate.get(null)
        }.getOrNull() ?: return CoverRuntime.log(
            SCOPE,
            "cover wallpaper colors-change refresh unavailable: no ActivityThread"
        )
        val context = runCatching {
            XposedHelpers.callMethod(app, "getSystemContext") as? Context
        }.getOrNull() ?: return CoverRuntime.log(
            SCOPE,
            "cover wallpaper colors-change refresh unavailable: no system context"
        )
        val wallpaperManager = context.getSystemService(Context.WALLPAPER_SERVICE) as? WallpaperManager
            ?: return
        runCatching {
            wallpaperManager.addOnColorsChangedListener(
                { _, _ ->
                    if (!CoverRuntime.isBuiltInCoverSessionEligible()) return@addOnColorsChangedListener
                    latestStaticDrawable = null
                    refreshCoverWallpaper(context)
                },
                android.os.Handler(android.os.Looper.getMainLooper())
            )
            CoverRuntime.log(
                SCOPE,
                "cover wallpaper colors-change refresh installed"
            )
        }.onFailure { error ->
            CoverRuntime.log(
                SCOPE,
                "cover wallpaper colors-change refresh unavailable: ${error.message}"
            )
        }
    }

    private fun installCoverLockWallpaperProvider(
        classLoader: ClassLoader,
        stateManagerClass: Class<*>
    ) {
        val methodNames = when (coverOneUiVersion()) {
            CoverOneUiVersion.V8 -> listOf("z", "s", "E")
            CoverOneUiVersion.V7 -> listOf("E", "s", "z")
            else -> listOf("s", "z", "E")
        }
        val providerSignature = arrayOf(
            Context::class.java,
            String::class.java,
            java.lang.Boolean.TYPE
        )
        val utilityClass = findCompatClass(
            classLoader = classLoader,
            v85Names = listOf(COMMON_WALLPAPER_UTILS),
            v8Names = listOf("aod.AbstractC2340gi", COMMON_WALLPAPER_UTILS),
            v7Names = listOf("aod.AbstractC1185Wv", COMMON_WALLPAPER_UTILS)
        ) { candidate ->
            candidate.declaredMethods.any { method ->
                method.name in methodNames &&
                    method.parameterTypes.contentEquals(providerSignature) &&
                    Drawable::class.java.isAssignableFrom(method.returnType)
            }
        } ?: return CoverRuntime.log(
            SCOPE,
            "SubLauncher keyguard-image utility unavailable in plugin"
        )
        synchronized(hookedProviderClasses) {
            if (!hookedProviderClasses.add(utilityClass)) return
        }

        runCatching {
            val methods = utilityClass.declaredMethods.filter { method ->
                method.name in methodNames &&
                    method.parameterTypes.contentEquals(providerSignature) &&
                    Drawable::class.java.isAssignableFrom(method.returnType)
            }
            require(methods.isNotEmpty()) {
                "keyguard-image drawable method unavailable"
            }
            methods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!CoverRuntime.isBuiltInCoverSessionEligible()) return
                        if (param.args[1] != "cover_wallpaper") return
                        val context = param.args[0] as? Context ?: return
                        val snapshot = CoverDisplayResolver.current() ?: return
                        if (snapshot.width <= 0 || snapshot.height <= 0) return
                        val width = snapshot.width
                        val height = snapshot.height
                        val cached = latestStaticDrawable as? BitmapDrawable
                        if (
                            cached?.bitmap?.width == width &&
                            cached.bitmap.height == height
                        ) {
                            param.result = cached
                            return
                        }
                        val uri = Uri.Builder()
                            .scheme("content")
                            .authority(KEYGUARD_IMAGE_AUTHORITY)
                            .appendPath("custom")
                            .appendQueryParameter("type", "wallpaper")
                            .appendQueryParameter("which", COVER_LOCK_WALLPAPER.toString())
                            .appendQueryParameter("width", width.toString())
                            .appendQueryParameter("height", height.toString())
                            .build()
                        val bitmap = runCatching {
                            context.contentResolver.openInputStream(uri)?.use { stream ->
                                BitmapFactory.decodeStream(stream)
                            }
                        }.getOrNull() ?: return

                        val drawable = BitmapDrawable(context.resources, bitmap)
                        latestStaticDrawable = drawable
                        CoverRuntime.log(SCOPE, "Provider sig=" + bitmapSignature(bitmap))
                        param.result = drawable
                        if (shouldShowWallpaper) {
                            latestSubLauncherWindow?.decorView?.post {
                                if (!shouldShowWallpaper) return@post
                                latestSubLauncherWindow?.decorView?.background = drawable
                                CoverRuntime.log(
                                    SCOPE,
                                    "applied which=$COVER_LOCK_WALLPAPER Provider drawable " +
                                        "to visible SubLauncher window"
                                )
                            }
                        }
                        CoverRuntime.log(
                            SCOPE,
                            "Provider request uri=$uri " +
                                "result=${bitmap.width}x${bitmap.height} " +
                                "classLoader=${utilityClass.classLoader}"
                        )
                    }
                })
            }
            CoverRuntime.log(
                SCOPE,
                "SubLauncher cover-lock Provider route installed " +
                    "state=${stateManagerClass.name} utility=${utilityClass.name} " +
                    "classLoader=${utilityClass.classLoader} methods=${methods.size}"
            )
        }.onFailure { error ->
            synchronized(hookedProviderClasses) {
                hookedProviderClasses.remove(utilityClass)
            }
            CoverRuntime.log(
                SCOPE,
                "SubLauncher cover-lock Provider route unavailable: ${error.message}"
            )
        }
    }

    private fun installClockHolderWallpaperSelector(
        classLoader: ClassLoader,
        stateManagerClass: Class<*>
    ) {
        val selectorClass = findCompatClass(
            classLoader = classLoader,
            v85Names = listOf(CLOCK_HOLDER_WALLPAPER_SELECTOR),
            v8Names = listOf(
                "aod.XT0",
                "aod.KT0",
                CLOCK_HOLDER_WALLPAPER_SELECTOR
            ),
            v7Names = listOf("aod.C1407aM0", CLOCK_HOLDER_WALLPAPER_SELECTOR)
        ) { candidate ->
            suspendMethods(candidate).size == 1 &&
                runCatching {
                    Drawable::class.java.isAssignableFrom(
                        candidate.getDeclaredField("d").type
                    )
                }.getOrDefault(false)
        } ?: return CoverRuntime.log(
            SCOPE,
            "SubLauncher clock-holder wallpaper selector unavailable in plugin"
        )
        synchronized(hookedSelectorClasses) {
            if (!hookedSelectorClasses.add(selectorClass)) return
        }

        runCatching {
            val methods = suspendMethods(selectorClass)
            require(methods.size == 1) {
                "clock-holder invokeSuspend shape ambiguous count=${methods.size}"
            }
            methods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(
                    method,
                    object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!CoverRuntime.isBuiltInCoverSessionEligible()) return
                        val presentMode = runCatching {
                            XposedHelpers.getObjectField(param.thisObject, "a")
                        }.getOrNull() ?: return
                        val isDefault = runCatching {
                            XposedHelpers.callMethod(presentMode, "isDefault") == true
                        }.getOrDefault(false)
                        if (!isDefault) return

                        val drawable = (param.result as? Drawable) ?: runCatching {
                            XposedHelpers.getObjectField(param.thisObject, "d") as? Drawable
                        }.getOrNull() ?: return
                        // 注意:不覆盖 latestStaticDrawable —— provider(which=17)才是壁纸权威来源,
                        // selector 的 drawable 可能是切换前的旧图,覆盖会导致切换样式后壁纸回退为默认。
                        if (param.result != null) return

                        param.result = drawable
                        CoverRuntime.log(
                            SCOPE,
                            "restored static cover drawable in default present mode " +
                                "selector=${selectorClass.name} " +
                                "classLoader=${selectorClass.classLoader}"
                        )
                    }
                })
            }
            CoverRuntime.log(
                SCOPE,
                "SubLauncher clock-holder wallpaper selector installed " +
                    "state=${stateManagerClass.name} selector=${selectorClass.name} " +
                    "classLoader=${selectorClass.classLoader} methods=${methods.size}"
            )
        }.onFailure { error ->
            synchronized(hookedSelectorClasses) {
                hookedSelectorClasses.remove(selectorClass)
            }
            CoverRuntime.log(
                SCOPE,
                "SubLauncher clock-holder wallpaper selector unavailable: ${error.message}"
            )
        }
    }

    private fun installWindowBackgroundFallback(
        classLoader: ClassLoader,
        stateManagerClass: Class<*>
    ) {
        val refreshClass = findCompatClass(
            classLoader = classLoader,
            v85Names = listOf(REFRESH_SHOW_WALLPAPER),
            v8Names = listOf(
                "aod.C1814d61",
                "aod.C2107f61",
                REFRESH_SHOW_WALLPAPER
            )
        ) { candidate -> suspendMethods(candidate).size == 1 } ?: return CoverRuntime.log(
            SCOPE,
            "SubLauncher wallpaper-window refresh unavailable in plugin"
        )
        synchronized(hookedWindowRefreshClasses) {
            if (!hookedWindowRefreshClasses.add(refreshClass)) return
        }

        runCatching {
            val methods = suspendMethods(refreshClass)
            require(methods.size == 1) {
                "wallpaper-window invokeSuspend shape ambiguous count=${methods.size}"
            }
            methods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(
                    method,
                    object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!CoverRuntime.isBuiltInCoverSessionEligible()) return
                        val shouldShow = booleanFieldCompat(param.thisObject, "a")
                        shouldShowWallpaper = shouldShow
                        val window = runCatching {
                            val subLauncherWindow = XposedHelpers.getObjectField(
                                param.thisObject,
                                "b"
                            )
                            val refreshUseCase = XposedHelpers.getObjectField(
                                subLauncherWindow,
                                "o"
                            )
                            val repository = XposedHelpers.getObjectField(
                                refreshUseCase,
                                "a"
                            )
                            XposedHelpers.getObjectField(repository, "a") as? Window
                        }.getOrNull() ?: return
                        latestSubLauncherWindow = window
                        if (!shouldShow) return
                        val drawable = latestStaticDrawable ?: return
                        if (window.decorView.background === drawable) return

                        window.decorView.background = drawable
                        CoverRuntime.log(
                            SCOPE,
                            "applied provider drawable to visible SubLauncher window " +
                                "refresh=${refreshClass.name} " +
                                "classLoader=${refreshClass.classLoader}"
                        )
                    }
                })
            }
            CoverRuntime.log(
                SCOPE,
                "SubLauncher wallpaper-window fallback installed " +
                    "state=${stateManagerClass.name} refresh=${refreshClass.name} " +
                    "classLoader=${refreshClass.classLoader} methods=${methods.size}"
            )
        }.onFailure { error ->
            synchronized(hookedWindowRefreshClasses) {
                hookedWindowRefreshClasses.remove(refreshClass)
            }
            CoverRuntime.log(
                SCOPE,
                "SubLauncher wallpaper-window fallback unavailable: ${error.message}"
            )
        }
    }
}
