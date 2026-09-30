package com.flexunlock.dexlsp

import org.junit.Assert.assertEquals
import org.junit.Test

class CoverDexControlLayoutTest {
    @Test
    fun `compact controls use two columns on the cover display`() {
        assertEquals(2, compactActionColumnCount(479, 2))
        assertEquals(2, compactActionColumnCount(479, 3))
    }

    @Test
    fun `wide controls use at most three columns`() {
        assertEquals(3, compactActionColumnCount(480, 3))
        assertEquals(3, compactActionColumnCount(800, 5))
    }

    @Test
    fun `single control keeps the full row`() {
        assertEquals(1, compactActionColumnCount(800, 1))
    }
}
