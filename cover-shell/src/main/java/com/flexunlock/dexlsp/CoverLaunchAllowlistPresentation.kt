package com.flexunlock.dexlsp

internal object CoverLaunchAllowlistPresentation {
    fun sort(packages: Collection<SelectablePackage>): List<SelectablePackage> =
        packages.sortedWith(
            compareByDescending<SelectablePackage> { it.selected }
                .thenBy { it.label.lowercase() }
                .thenBy { it.packageName }
        )

    fun selectionScope(
        packages: Collection<SelectablePackage>,
        showSystemApps: Boolean,
        showUserApps: Boolean
    ): Set<String> = packages.asSequence()
        .filter { item -> if (item.isSystem) showSystemApps else showUserApps }
        .mapTo(linkedSetOf(), SelectablePackage::packageName)

    fun setAllInScope(
        packages: Collection<SelectablePackage>,
        selectedPackages: Set<String>,
        showSystemApps: Boolean,
        showUserApps: Boolean,
        selected: Boolean
    ): Set<String> {
        val scope = selectionScope(packages, showSystemApps, showUserApps)
        return selectedPackages.toMutableSet().apply {
            if (selected) addAll(scope) else removeAll(scope)
        }
    }

    fun invertInScope(
        packages: Collection<SelectablePackage>,
        selectedPackages: Set<String>,
        showSystemApps: Boolean,
        showUserApps: Boolean
    ): Set<String> {
        val scope = selectionScope(packages, showSystemApps, showUserApps)
        return selectedPackages.toMutableSet().apply {
            scope.forEach { packageName ->
                if (!remove(packageName)) add(packageName)
            }
        }
    }

    fun isScopeFullySelected(
        packages: Collection<SelectablePackage>,
        selectedPackages: Set<String>,
        showSystemApps: Boolean,
        showUserApps: Boolean
    ): Boolean {
        val scope = selectionScope(packages, showSystemApps, showUserApps)
        return scope.isNotEmpty() && scope.all(selectedPackages::contains)
    }
}
