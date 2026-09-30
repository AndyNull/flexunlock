package com.flexunlock.dexlsp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemUiHooksTest {
    @Test
    fun fullQsUsesPhoneWallpaperSlotsWithoutChangingExplicitModes() {
        assertEquals(5, resolveFullQsWallpaperWhich(true, 1))
        assertEquals(6, resolveFullQsWallpaperWhich(true, 2))
        assertEquals(17, resolveFullQsWallpaperWhich(true, 17))
        assertEquals(18, resolveFullQsWallpaperWhich(true, 18))
        assertEquals(2, resolveFullQsWallpaperWhich(false, 2))
    }

    @Test
    fun dismissesCoverGlobalActionsImmediatelyOnlyWhileSleeping() {
        assertTrue(dismissCoverGlobalActionsImmediately(true, false))
        assertFalse(dismissCoverGlobalActionsImmediately(true, true))
        assertFalse(dismissCoverGlobalActionsImmediately(false, false))
    }

    @Test
    fun usesSystemUiGlobalActionsContentOnlyOnTheActiveCoverDisplay() {
        assertTrue(useSystemUiGlobalActionsContent(true, true))
        assertFalse(useSystemUiGlobalActionsContent(true, false))
        assertFalse(useSystemUiGlobalActionsContent(false, true))
    }

    @Test
    fun routesOnlyWhenFullQsIsStableAndDefaultDisplayIsCover() {
        assertTrue(routeFullQsVolumeToDefaultDisplay(true, true))
        assertFalse(routeFullQsVolumeToDefaultDisplay(false, true))
        assertFalse(routeFullQsVolumeToDefaultDisplay(true, false))
        assertFalse(routeFullQsVolumeToDefaultDisplay(false, false))
    }

    @Test
    fun usesFlashlightMainPathForClosedCoverSessionInEitherQsMode() {
        assertTrue(useCoverFlashlightMainPath(true, false))
        assertFalse(useCoverFlashlightMainPath(false, false))
        assertFalse(useCoverFlashlightMainPath(true, true))
    }
}
