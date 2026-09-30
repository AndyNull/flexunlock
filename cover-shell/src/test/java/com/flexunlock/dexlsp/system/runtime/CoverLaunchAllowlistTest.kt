package com.flexunlock.dexlsp.system.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverLaunchAllowlistTest {
    @Test
    fun systemSettingsStayOnCoverWithoutUserAllowlist() {
        assertTrue(CoverLaunchAllowlist.contains("com.android.settings"))
        assertTrue(CoverLaunchAllowlist.contains("com.android.systemui"))
        assertTrue(CoverLaunchAllowlist.contains("com.android.settings.intelligence"))
        assertTrue(CoverLaunchAllowlist.contains("com.samsung.android.settings.intelligence"))
    }

    @Test
    fun goodLockPluginsStayOnCoverWithoutUserAllowlist() {
        assertTrue(CoverLaunchAllowlist.contains("com.samsung.systemui.navistar"))
        assertTrue(CoverLaunchAllowlist.contains("com.flexunlock.dexlsp"))
    }

    @Test
    fun unrelatedPackagesStayOffCoverWithoutUserAllowlist() {
        assertFalse(CoverLaunchAllowlist.contains("com.example.maps"))
        assertFalse(CoverLaunchAllowlist.contains(null))
    }

    @Test
    fun nestedLaunchFromSettingsKeepsChildOnCover() {
        assertTrue(
            CoverLaunchAllowlist.allowsCoverLaunch(
                "com.android.settings",
                "com.sec.android.app.launcher"
            )
        )
        assertTrue(
            CoverLaunchAllowlist.allowsCoverLaunch(
                "com.android.systemui",
                "com.android.settings"
            )
        )
        assertTrue(
            CoverLaunchAllowlist.allowsCoverLaunch(
                "com.example.pairing",
                "com.android.settings"
            )
        )
        assertTrue(
            CoverLaunchAllowlist.allowsCoverLaunch(
                "com.example.maps",
                "com.sec.android.app.launcher"
            )
        )
    }

    @Test
    fun innerHomeIsNotForcedOntoCoverDisplay() {
        assertFalse(
            CoverLaunchAllowlist.shouldForceCoverTaskDisplay(
                "com.sec.android.app.launcher",
                "com.sec.android.app.launcher.activities.LauncherActivity",
                "com.android.systemui"
            )
        )
        assertFalse(
            CoverLaunchAllowlist.shouldForceCoverTaskDisplay(
                "com.sec.android.app.launcher",
                null,
                "com.android.systemui"
            )
        )
        assertTrue(
            CoverLaunchAllowlist.shouldForceCoverTaskDisplay(
                "com.sec.android.app.launcher",
                "com.honeyspace.dexservice.SecondaryLauncher",
                "com.android.systemui"
            )
        )
        assertTrue(
            CoverLaunchAllowlist.shouldForceCoverTaskDisplay(
                "com.android.settings",
                "com.android.settings.Settings",
                "com.sec.android.app.launcher"
            )
        )
        assertFalse(
            CoverLaunchAllowlist.shouldForceCoverTaskDisplay(
                "com.android.systemui",
                "com.android.systemui.subscreen.SubHomeActivity",
                "android"
            )
        )
        assertTrue(
            CoverLaunchAllowlist.shouldForceCoverTaskDisplay(
                "com.android.systemui",
                "com.android.systemui.settings.brightness.BrightnessDialog",
                "android"
            )
        )
    }
}
