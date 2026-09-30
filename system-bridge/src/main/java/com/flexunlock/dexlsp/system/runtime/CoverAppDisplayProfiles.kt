package com.flexunlock.dexlsp.system.runtime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.provider.Settings
import android.util.Log
import com.flexunlock.dexlsp.config.AppDisplayProfile
import com.flexunlock.dexlsp.config.AppDisplayProfileConfig
import de.robv.android.xposed.XposedBridge
import java.util.concurrent.atomic.AtomicLong

internal fun resolveDisplayProfile(
    profiles: Map<String, AppDisplayProfile>,
    packageName: String,
    globallyAllowed: Boolean = true
): AppDisplayProfile? = profiles[packageName]
    ?: profiles[AppDisplayProfileConfig.GLOBAL_PROFILE_PACKAGE_NAME]
        ?.takeIf { packageName !in GLOBAL_PROFILE_EXCLUDED_PACKAGES }
        ?.copy(packageName = packageName)

private val GLOBAL_PROFILE_EXCLUDED_PACKAGES = setOf(
    "android",
    "com.android.systemui",
    "com.sec.android.app.launcher",
    "com.flexunlock.dexlsp"
)

object CoverAppDisplayProfiles {
    private const val TAG = "FlexUnlock-SystemBridge"
    private const val CONTROL_PERMISSION =
        "com.flexunlock.dexlsp.permission.CONTROL_COVER_DEX"

    @Volatile
    private var profiles: Map<String, AppDisplayProfile> = emptyMap()

    @Volatile
    private var initialized = false

    private val revision = AtomicLong(0L)

    fun initialize(context: Context, handler: Handler) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            profiles = AppDisplayProfileConfig.read(context)
            revision.set(AppDisplayProfileConfig.readRevision(context))
            context.registerReceiver(
                object : BroadcastReceiver() {
                    override fun onReceive(receiverContext: Context?, intent: Intent?) {
                        if (intent?.action != AppDisplayProfileConfig.ACTION_UPDATE) return
                        applyUpdate(context, intent)
                    }
                },
                IntentFilter(AppDisplayProfileConfig.ACTION_UPDATE),
                CONTROL_PERMISSION,
                handler,
                Context.RECEIVER_EXPORTED
            )
            initialized = true
            log("app display profiles loaded profiles=${profiles.size} revision=${revision.get()}")
        }
    }

    fun profileFor(packageName: String?): AppDisplayProfile? {
        val targetPackage = packageName ?: return null
        return resolveDisplayProfile(
            profiles,
            targetPackage,
            globallyAllowed = true
        )
    }

    fun snapshot(): Map<String, AppDisplayProfile> = profiles

    private fun applyUpdate(context: Context, intent: Intent) {
        val encoded = intent.getStringExtra(AppDisplayProfileConfig.EXTRA_ENCODED_PROFILES)
            ?: return log("app display profile update rejected: payload missing")
        val requested = AppDisplayProfileConfig.decode(encoded)
            ?: return log("app display profile update rejected: malformed payload")
        val canonical = AppDisplayProfileConfig.normalize(requested.values)
        val encodedCanonical = AppDisplayProfileConfig.encode(canonical.values)
        val nextRevision = revision.get() + 1L
        if (!Settings.Global.putString(
                context.contentResolver,
                AppDisplayProfileConfig.SETTINGS_KEY,
                encodedCanonical
            ) || !Settings.Global.putLong(
                context.contentResolver,
                AppDisplayProfileConfig.SETTINGS_REVISION_KEY,
                nextRevision
            )
        ) {
            log("app display profile persistence rejected revision=$nextRevision")
            return
        }

        profiles = canonical
        revision.set(nextRevision)
        log("app display profiles updated profiles=${canonical.size} revision=$nextRevision")
    }

    private fun log(message: String) {
        Log.i(TAG, message)
        XposedBridge.log("$TAG: $message")
    }
}
