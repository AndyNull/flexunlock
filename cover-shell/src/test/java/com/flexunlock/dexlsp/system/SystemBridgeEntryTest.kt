package com.flexunlock.dexlsp.system

import com.flexunlock.dexlsp.system.session.FoldState
import org.junit.Assert.assertEquals
import org.junit.Test

class SystemBridgeEntryTest {
    @Test
    fun `closed and tent states keep the cover session active`() {
        assertEquals(FoldState.CLOSED, foldStateOf(0))
        assertEquals(FoldState.CLOSED, foldStateOf(1))
    }

    @Test
    fun `half opened and opened states leave the cover session`() {
        assertEquals(FoldState.OPENED, foldStateOf(2))
        assertEquals(FoldState.OPENED, foldStateOf(3))
    }

    @Test
    fun `unknown device states stay unknown`() {
        assertEquals(FoldState.UNKNOWN, foldStateOf(99))
    }
}
