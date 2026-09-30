package com.flexunlock.dexlsp

import android.content.Context
import android.content.pm.ApplicationInfo
import java.util.concurrent.CopyOnWriteArrayList

internal object CoverLaunchPackageCatalog {
    private val lock = Any()
    private var cached: List<SelectablePackage>? = null
    private var loading = false
    private val waiters = CopyOnWriteArrayList<(Result<List<SelectablePackage>>) -> Unit>()

    fun peek(): List<SelectablePackage>? = synchronized(lock) { cached }

    fun ensureLoaded(
        context: Context,
        onResult: ((Result<List<SelectablePackage>>) -> Unit)? = null
    ) {
        load(context, force = false, onResult = onResult)
    }

    fun load(
        context: Context,
        force: Boolean,
        selectedPackages: Set<String> = emptySet(),
        onResult: ((Result<List<SelectablePackage>>) -> Unit)? = null
    ) {
        val appContext = context.applicationContext ?: context
        synchronized(lock) {
            val existing = cached
            if (!force && existing != null) {
                onResult?.invoke(Result.success(existing))
                return
            }
            if (onResult != null) waiters += onResult
            if (loading) return
            loading = true
        }
        Thread {
            val result = runCatching { query(appContext, selectedPackages) }
            val listeners: List<(Result<List<SelectablePackage>>) -> Unit>
            synchronized(lock) {
                loading = false
                result.onSuccess { cached = it }
                listeners = waiters.toList()
                waiters.clear()
            }
            listeners.forEach { listener ->
                runCatching { listener(result) }
            }
        }.apply { name = "CoverAllowlistCatalogLoader" }.start()
    }

    private fun query(
        context: Context,
        selectedPackages: Set<String>
    ): List<SelectablePackage> {
        val packageManager = context.packageManager
        val installed = packageManager.getInstalledApplications(0).associateBy { it.packageName }
        return (installed.keys + selectedPackages)
            .asSequence()
            .filterNot { it == context.packageName }
            .map { packageName ->
                val app = installed[packageName]
                SelectablePackage(
                    packageName = packageName,
                    label = app?.loadLabel(packageManager)?.toString() ?: packageName,
                    icon = app?.loadIcon(packageManager) ?: packageManager.defaultActivityIcon,
                    isSystem = app?.let(::isSystemApplication) ?: false,
                    selected = false,
                    profile = null
                )
            }
            .sortedWith(compareBy<SelectablePackage> { it.label.lowercase() }.thenBy { it.packageName })
            .toList()
    }

    private fun isSystemApplication(application: ApplicationInfo): Boolean =
        application.flags and (
            ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
            ) != 0
}
