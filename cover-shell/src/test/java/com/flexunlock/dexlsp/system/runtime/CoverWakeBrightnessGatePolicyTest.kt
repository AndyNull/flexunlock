package com.flexunlock.dexlsp.system.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverWakeBrightnessGatePolicyTest {
    @Test
    fun disabledSystemSettingBlocksClosedCoverTapWake() {
        assertTrue(shouldBlockCoverTapWake(true, true, 0, 15) { false })
    }

    @Test
    fun enabledSystemSettingAllowsClosedCoverTapWake() {
        assertFalse(shouldBlockCoverTapWake(true, true, 0, 15) { true })
    }

    @Test
    fun nonTapWakeReasonsRemainAllowed() {
        assertFalse(shouldBlockCoverTapWake(true, true, 0, 1) { false })
        assertFalse(shouldBlockCoverTapWake(true, true, 0, 3) { false })
        assertFalse(shouldBlockCoverTapWake(true, true, 0, 4) { false })
    }

    @Test
    fun nonCoverSessionsRemainAllowed() {
        assertFalse(shouldBlockCoverTapWake(false, true, 0, 15) { false })
        assertFalse(shouldBlockCoverTapWake(true, false, 0, 15) { false })
        assertFalse(shouldBlockCoverTapWake(true, true, 1, 15) { false })
    }

    @Test
    fun nonTapWakeDoesNotReadSystemSetting() {
        assertFalse(shouldBlockCoverTapWake(true, true, 0, 1) {
            error("setting must not be read")
        })
    }
}
