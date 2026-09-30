package com.flexunlock.dexlsp

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherHooksTest {
    @Test
    fun centersQuarterHeightInputSpanInStableFullQs() {
        assertEquals(EdgeHandleInputSpan(270, 450), edgeHandleInputSpan(720, true))
        assertEquals(EdgeHandleInputSpan(0, 720), edgeHandleInputSpan(720, false))
        assertEquals(EdgeHandleInputSpan(0, 1), edgeHandleInputSpan(1, true))
    }

    @Test
    fun bypassesNativeHomeOnlyForFullQsRecentRegion() {
        assertTrue(shouldToggleCoverRecentsDirectly(true, "RECENT"))
        assertFalse(shouldToggleCoverRecentsDirectly(true, "HOME"))
        assertFalse(shouldToggleCoverRecentsDirectly(true, "BACK"))
        assertFalse(shouldToggleCoverRecentsDirectly(false, "RECENT"))
    }

    @Test
    fun finishesRecentsOnlyOnManagedExternalDisplay() {
        assertTrue(shouldFinishManagedExternalRecents(true, true))
        assertFalse(shouldFinishManagedExternalRecents(false, true))
        assertFalse(shouldFinishManagedExternalRecents(true, false))
    }

    @Test
    fun launchesHomeOnlyForShortScrcpyHomeRelease() {
        val scrcpy = "virtual:com.android.shell,2000,scrcpy,1"
        assertTrue(shouldLaunchScrcpyHome(KeyEvent.ACTION_UP, 0, KeyEvent.KEYCODE_HOME, scrcpy))
        assertFalse(shouldLaunchScrcpyHome(KeyEvent.ACTION_DOWN, 0, KeyEvent.KEYCODE_HOME, scrcpy))
        assertFalse(shouldLaunchScrcpyHome(KeyEvent.ACTION_UP, 128, KeyEvent.KEYCODE_HOME, scrcpy))
        assertFalse(shouldLaunchScrcpyHome(KeyEvent.ACTION_UP, 0, KeyEvent.KEYCODE_BACK, scrcpy))
        assertFalse(shouldLaunchScrcpyHome(KeyEvent.ACTION_UP, 0, KeyEvent.KEYCODE_HOME, "local:1"))
    }

    @Test
    fun appLaunchRouteFollowsTheLauncherSourceDisplay() {
        assertTrue(shouldRouteLauncherAppToDisplay(14, false, true))
        assertTrue(shouldRouteLauncherAppToDisplay(1, true, false))
        assertFalse(shouldRouteLauncherAppToDisplay(0, true, false))
        assertFalse(shouldRouteLauncherAppToDisplay(null, false, false))
    }

}
