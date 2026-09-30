package com.flexunlock.dexlsp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverRuntimePolicyTest {
    @Test
    fun `cover view policy excludes external output`() {
        assertTrue(coverViewEligible(true, true, true))
        assertFalse(coverViewEligible(true, false, true))
        assertFalse(coverViewEligible(false, true, true))
    }

    @Test
    fun `cover UI is disabled while full DeX owns the target display`() {
        assertTrue(coverUiSessionEligible(sessionEligible = true, fullDexEnabled = false))
        assertFalse(coverUiSessionEligible(sessionEligible = true, fullDexEnabled = true))
        assertFalse(coverUiSessionEligible(sessionEligible = false, fullDexEnabled = false))
    }
}
