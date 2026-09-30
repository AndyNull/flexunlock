package com.flexunlock.dexlsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeCoverStatusBarHooksTest {
    @Test
    fun fullDexBlocksOnlyTheCoverQuickPanel() {
        assertTrue(fullDexBlocksCoverQuickPanel(fullDexEnabled = true, coverDisplay = true))
        assertFalse(fullDexBlocksCoverQuickPanel(fullDexEnabled = false, coverDisplay = true))
        assertFalse(fullDexBlocksCoverQuickPanel(fullDexEnabled = true, coverDisplay = false))
    }

    @Test
    fun statusScaleUsesCurrentDensity() {
        assertEquals(38, coverStatusDimensionPx(16f, 510f / 160f, 0.76f))
        assertEquals(25, coverStatusDimensionPx(16f, 340f / 160f, 0.76f))
        assertEquals(34, coverStatusDimensionPx(16f, 340f / 160f, 1f))
        assertEquals(
            25.585f,
            coverStatusTextSizePx(14f, 340f / 160f, 0.86f),
            0.001f
        )
    }

    @Test
    fun fullChannelTreatsMainLauncherAsCoverHome() {
        assertTrue(
            isCoverHomeActivity(
                "com.sec.android.app.launcher.activities.LauncherActivity",
                true
            )
        )
        assertFalse(
            isCoverHomeActivity(
                "com.sec.android.app.launcher.activities.LauncherActivity",
                false
            )
        )
        assertTrue(
            isFullCoverMainLauncher(
                "com.sec.android.app.launcher.activities.LauncherActivity",
                true
            )
        )
        assertFalse(
            isFullCoverMainLauncher(
                "com.honeyspace.dexservice.SecondaryLauncher",
                true
            )
        )
        assertTrue(isFullCoverMainLauncher("com.samsung.launcher.NewHome", true, true))
        assertFalse(isFullCoverMainLauncher("com.samsung.launcher.NewHome", false, true))
        assertTrue(isCoverHomeActivity("com.honeyspace.dexservice.SecondaryLauncher", false))
    }
}
