package com.flexunlock.dexlsp.system.runtime

import android.os.PowerManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverTimeoutPolicyTest {
    @Test
    fun `screen wake locks defer the module timeout`() {
        assertTrue(isScreenKeepingWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK))
        assertTrue(isScreenKeepingWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK))
        assertTrue(isScreenKeepingWakeLock(PowerManager.FULL_WAKE_LOCK))
        assertFalse(isScreenKeepingWakeLock(PowerManager.PARTIAL_WAKE_LOCK))
        assertFalse(isScreenKeepingWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK))
    }

    @Test
    fun `module external display wake lock does not defer cover timeout`() {
        assertFalse(isScreenKeepingWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "FlexUnlock:ExternalDisplay"
        ))
        assertTrue(isScreenKeepingWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK,
            "WindowManager/displayId:1"
        ))
        assertTrue(isScreenKeepingWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK, null))
    }

    @Test
    fun `keep screen on windows defer the module timeout`() {
        assertTrue(isWindowKeepingScreenOn(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON))
        assertFalse(isWindowKeepingScreenOn(0))
    }

    @Test
    fun `locked cover accepts Samsung default-display user activity proxy`() {
        assertTrue(isCoverUserActivity(1, 1, false, true, true))
        assertTrue(isCoverUserActivity(0, 1, false, true, true))
        assertFalse(isCoverUserActivity(0, 1, true, true, true))
        assertFalse(isCoverUserActivity(0, 1, false, true, false))
        assertFalse(isCoverUserActivity(0, 1, false, false, true))
    }
}
