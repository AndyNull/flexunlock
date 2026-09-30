package com.flexunlock.dexlsp

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.drawable.Drawable
import org.junit.Assert.assertEquals
import org.junit.Test

class CoverLaunchAllowlistPresentationTest {
    @Test
    fun selectedPackagesStayFirstWithDeterministicTieBreakers() {
        val packages = listOf(
            selectable("com.example.zeta", "Same", selected = true),
            selectable("com.example.alpha", "same", selected = true),
            selectable("com.example.unselected", "Aardvark", selected = false),
            selectable("com.example.beta", "Beta", selected = false)
        )

        assertEquals(
            listOf(
                "com.example.alpha",
                "com.example.zeta",
                "com.example.unselected",
                "com.example.beta"
            ),
            CoverLaunchAllowlistPresentation.sort(packages).map { it.packageName }
        )
    }

    @Test
    fun `select all and clear only affect the enabled app type scope`() {
        val packages = listOf(
            selectable("user.one", "User one", selected = false),
            selectable("user.two", "User two", selected = false),
            selectable("system.one", "System one", selected = true, isSystem = true)
        )
        val selected = setOf("system.one")
        val allUsers = CoverLaunchAllowlistPresentation.setAllInScope(
            packages,
            selected,
            showSystemApps = false,
            showUserApps = true,
            selected = true
        )
        assertEquals(setOf("system.one", "user.one", "user.two"), allUsers)
        assertEquals(
            setOf("system.one"),
            CoverLaunchAllowlistPresentation.setAllInScope(
                packages,
                allUsers,
                showSystemApps = false,
                showUserApps = true,
                selected = false
            )
        )
    }

    @Test
    fun `invert toggles only packages in the enabled app type scope`() {
        val packages = listOf(
            selectable("user.one", "User one", selected = true),
            selectable("system.one", "System one", selected = true, isSystem = true),
            selectable("system.two", "System two", selected = false, isSystem = true)
        )
        assertEquals(
            setOf("user.one", "system.two"),
            CoverLaunchAllowlistPresentation.invertInScope(
                packages,
                setOf("user.one", "system.one"),
                showSystemApps = true,
                showUserApps = false
            )
        )
    }

    @Test
    fun `fully selected state requires a non-empty enabled scope`() {
        val packages = listOf(selectable("user.one", "User one", selected = true))
        assertEquals(
            true,
            CoverLaunchAllowlistPresentation.isScopeFullySelected(
                packages,
                setOf("user.one"),
                showSystemApps = false,
                showUserApps = true
            )
        )
        assertEquals(
            false,
            CoverLaunchAllowlistPresentation.isScopeFullySelected(
                packages,
                setOf("user.one"),
                showSystemApps = false,
                showUserApps = false
            )
        )
    }

    private fun selectable(
        packageName: String,
        label: String,
        selected: Boolean,
        isSystem: Boolean = false
    ) = SelectablePackage(
        packageName = packageName,
        label = label,
        icon = EmptyDrawable,
        isSystem = isSystem,
        selected = selected,
        profile = null
    )

    private object EmptyDrawable : Drawable() {
        override fun draw(canvas: Canvas) = Unit
        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit
        @Deprecated("Drawable API")
        override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSPARENT
    }
}