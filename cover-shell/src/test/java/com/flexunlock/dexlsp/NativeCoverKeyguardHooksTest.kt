package com.flexunlock.dexlsp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeCoverKeyguardHooksTest {
    @Test
    fun preservesOnlySecureVisibleNonDefaultPageAuthentication() {
        assertTrue(shouldPreserveCoverWidgetAuthentication(true, true, 1, 0))
        assertFalse(shouldPreserveCoverWidgetAuthentication(false, true, 1, 0))
        assertFalse(shouldPreserveCoverWidgetAuthentication(true, false, 1, 0))
        assertFalse(shouldPreserveCoverWidgetAuthentication(true, true, 0, 0))
        assertFalse(shouldPreserveCoverWidgetAuthentication(true, true, null, 0))
        assertFalse(shouldPreserveCoverWidgetAuthentication(true, true, 1, null))
    }
}
