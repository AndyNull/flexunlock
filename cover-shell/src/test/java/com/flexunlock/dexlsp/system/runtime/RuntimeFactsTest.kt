package com.flexunlock.dexlsp.system.runtime

import com.flexunlock.dexlsp.config.CoverDisplayMode
import com.flexunlock.dexlsp.system.session.FoldState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeFactsTest {
    @Test
    fun `built in target requires closed fold state`() {
        assertTrue(targetSessionEligible(FoldState.CLOSED, DISPLAY_TYPE_BUILT_IN))
        assertFalse(targetSessionEligible(FoldState.OPENED, DISPLAY_TYPE_BUILT_IN))
    }

    @Test
    fun `external target remains eligible while unfolded`() {
        assertTrue(targetSessionEligible(FoldState.OPENED, DISPLAY_TYPE_HDMI))
        assertTrue(targetSessionEligible(FoldState.OPENED, DISPLAY_TYPE_VIRTUAL))
        assertTrue(targetSessionEligible(FoldState.OPENED, DISPLAY_TYPE_DISPLAY_PORT))
        assertEquals(
            FoldState.CLOSED,
            effectiveSessionFoldState(FoldState.OPENED, DISPLAY_TYPE_HDMI)
        )
    }

    @Test
    fun `missing target is ineligible and preserves observed fold state`() {
        assertFalse(targetSessionEligible(FoldState.CLOSED, null))
        assertEquals(FoldState.OPENED, effectiveSessionFoldState(FoldState.OPENED, null))
    }

    @Test
    fun `security state blocks only built in target`() {
        assertTrue(targetSecurityRestricted(DISPLAY_TYPE_BUILT_IN, true, false))
        assertTrue(targetSecurityRestricted(DISPLAY_TYPE_BUILT_IN, false, true))
        assertFalse(targetSecurityRestricted(DISPLAY_TYPE_BUILT_IN, false, false))
        assertFalse(targetSecurityRestricted(DISPLAY_TYPE_HDMI, true, true))
        assertFalse(targetSecurityRestricted(DISPLAY_TYPE_DISPLAY_PORT, true, true))
        assertFalse(targetSecurityRestricted(DISPLAY_TYPE_VIRTUAL, true, true))
    }

    @Test
    fun `preferred display mode ignores system default sentinel`() {
        assertNull(preferredDisplayModeOrNull(-1, -1, 0f))
        assertNull(preferredDisplayModeOrNull(1920, 1080, Float.NaN))
        assertEquals(
            CoverDisplayMode(1920, 1080, 60f),
            preferredDisplayModeOrNull(1920, 1080, 60f)
        )
    }

    private companion object {
        const val DISPLAY_TYPE_BUILT_IN = 1
        const val DISPLAY_TYPE_HDMI = 2
        const val DISPLAY_TYPE_VIRTUAL = 5
        const val DISPLAY_TYPE_DISPLAY_PORT = 6
    }
}
