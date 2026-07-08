package com.flexunlock.simple.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import timber.log.Timber

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != "android.intent.action.QUICKBOOT_POWERON") return

        val shouldAutoStart = context.getSharedPreferences("flexunlock", Context.MODE_PRIVATE)
            .getBoolean("guard_enabled", false)
        if (!shouldAutoStart) return

        val serviceIntent = Intent(context, CoverHomeGuardService::class.java).apply {
            action = CoverHomeGuardService.ACTION_START_GUARD
        }
        try {
            ContextCompat.startForegroundService(context, serviceIntent)
            Timber.i("CoverHomeGuardService auto-started from boot")
        } catch (e: Exception) {
            Timber.e(e, "Failed to start service from boot")
        }
    }
}
