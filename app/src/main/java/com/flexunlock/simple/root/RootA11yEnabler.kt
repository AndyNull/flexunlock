package com.flexunlock.simple.root

import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

object RootA11yEnabler {
    private const val OUR_SERVICE = "com.flexunlock.simple/com.flexunlock.simple.accessibility.SideKeyAccessibilityService"

    suspend fun isEnabled(): Boolean = withContext(Dispatchers.IO) {
        val out = ArrayList<String>()
        Shell.getShell().newJob().add("settings get secure enabled_accessibility_services")
            .to(out, ArrayList()).exec()
        out.joinToString("").trim().contains(OUR_SERVICE)
    }

    suspend fun enable(): Boolean = withContext(Dispatchers.IO) {
        try {
            val out = ArrayList<String>()
            Shell.getShell().newJob().add("settings get secure enabled_accessibility_services")
                .to(out, ArrayList()).exec()
            val current = out.joinToString("").trim()

            val services = if (current.isEmpty() || current == "null") {
                OUR_SERVICE
            } else {
                val list = current.split(":").filter { it.isNotEmpty() && it != "null" }.toMutableList()
                if (OUR_SERVICE !in list) list.add(OUR_SERVICE)
                list.joinToString(":")
            }

            Shell.getShell().newJob().add("settings put secure enabled_accessibility_services '$services'").exec()
            Shell.getShell().newJob().add("settings put secure accessibility_enabled 1").exec()
            Thread.sleep(1000)
            isEnabled()
        } catch (e: Exception) {
            Timber.e(e, "Failed to enable a11y")
            false
        }
    }
}
