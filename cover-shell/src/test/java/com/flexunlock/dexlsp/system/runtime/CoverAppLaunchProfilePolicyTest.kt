package com.flexunlock.dexlsp.system.runtime

import com.flexunlock.dexlsp.config.AppDisplayProfile
import com.flexunlock.dexlsp.config.AppDisplayProfileConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverAppLaunchProfilePolicyTest {
    @Test
    fun globalProfileAppliesToUserAppsWithoutAnOverride() {
        val global = AppDisplayProfile(
            AppDisplayProfileConfig.GLOBAL_PROFILE_PACKAGE_NAME,
            fullscreenPercent = 85
        )
        val exact = AppDisplayProfile("com.example.exact", fullscreenPercent = 75)
        val profiles = mapOf(global.packageName to global, exact.packageName to exact)

        assertEquals(exact, resolveDisplayProfile(profiles, exact.packageName, globallyAllowed = true))
        assertEquals(
            global.copy(packageName = "com.example.allowed"),
            resolveDisplayProfile(profiles, "com.example.allowed", globallyAllowed = true)
        )
        assertEquals(
            global.copy(packageName = "com.example.user"),
            resolveDisplayProfile(profiles, "com.example.user", globallyAllowed = false)
        )
        assertEquals(
            null,
            resolveDisplayProfile(profiles, "com.sec.android.app.launcher", globallyAllowed = true)
        )
    }

    @Test
    fun densityFollowsFullscreenPercentOfNativeCoverDpi() {
        val nativeDensity = 320

        assertEquals(nativeDensity, CoverAppLaunchProfilePolicy.densityForPercent(nativeDensity, 100))
        assertEquals(256, CoverAppLaunchProfilePolicy.densityForPercent(nativeDensity, 80))
        assertEquals(384, CoverAppLaunchProfilePolicy.densityForPercent(nativeDensity, 120))
        assertEquals(238, CoverAppLaunchProfilePolicy.densityForPercent(340, 70))
        assertTrue(
            CoverAppLaunchProfilePolicy.densityForPercent(nativeDensity, 80) < nativeDensity
        )
        assertTrue(
            CoverAppLaunchProfilePolicy.densityForPercent(nativeDensity, 120) > nativeDensity
        )
    }

    @Test
    fun coverDensityRejectsPhoneFallbackDpi() {
        assertEquals(null, CoverAppLaunchProfilePolicy.sanitizeCoverDensityDpi(160))
        assertEquals(null, CoverAppLaunchProfilePolicy.sanitizeCoverDensityDpi(560))
        assertEquals(320, CoverAppLaunchProfilePolicy.sanitizeCoverDensityDpi(320))
    }

    @Test
    fun nativeCoverDensityFallsBackWhenPhoneDpiLeaks() {
        assertEquals(
            340,
            CoverAppLaunchProfilePolicy.resolveNativeCoverDensityDpi(
                fromRealMetrics = 160,
                fromDisplayContext = 160,
                lastGood = 0
            )
        )
        assertEquals(
            340,
            CoverAppLaunchProfilePolicy.resolveNativeCoverDensityDpi(
                fromRealMetrics = 340,
                fromDisplayContext = 160,
                lastGood = 0
            )
        )
        assertEquals(
            320,
            CoverAppLaunchProfilePolicy.resolveNativeCoverDensityDpi(
                fromRealMetrics = 160,
                fromDisplayContext = 160,
                lastGood = 320
            )
        )
    }

    @Test
    fun fullModePinsDensityOnlyOnSwappedBuiltInCover() {
        assertTrue(shouldPinFullModeTaskDensity(true, true, true, 0, 0))
        assertTrue(!shouldPinFullModeTaskDensity(false, true, true, 0, 0))
        assertTrue(!shouldPinFullModeTaskDensity(true, false, true, 0, 0))
        assertTrue(!shouldPinFullModeTaskDensity(true, true, false, 0, 0))
        assertTrue(!shouldPinFullModeTaskDensity(true, true, true, 1, 1))
        assertTrue(!shouldPinFullModeTaskDensity(true, true, true, 0, 1))
    }

    @Test
    fun popupBoundsUseIndependentPercentagesInsideSafeArea() {
        val safeArea = PopupArea(16, 20, 732, 670)
        val bounds = PopupBoundsCalculator.calculate(safeArea, 70, 60)

        assertEquals(501, bounds.width)
        assertEquals(390, bounds.height)
        assertTrue(bounds.left >= safeArea.left && bounds.right <= safeArea.right)
        assertTrue(bounds.top >= safeArea.top && bounds.bottom <= safeArea.bottom)
        assertTrue(kotlin.math.abs((safeArea.left + safeArea.right) / 2 - bounds.centerX) <= 1)
        assertTrue(kotlin.math.abs((safeArea.top + safeArea.bottom) / 2 - bounds.centerY) <= 1)
    }

    @Test
    fun popupPercentagesAreClampedToSafeArea() {
        val safeArea = PopupArea(0, 0, 748, 654)
        val bounds = PopupBoundsCalculator.calculate(safeArea, 1, 200)

        assertTrue(bounds.width in 373..374)
        assertEquals(safeArea.height, bounds.height)
        assertTrue(bounds.left in 187..188)
        assertEquals(safeArea.top, bounds.top)
    }
}
