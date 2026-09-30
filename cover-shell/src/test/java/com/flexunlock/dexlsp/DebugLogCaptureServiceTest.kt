package com.flexunlock.dexlsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugLogCaptureServiceTest {
    @Test
    fun `compact capture keeps module and system warnings without noisy buffers`() {
        val command = logcatCommand(DebugLogMode.COMPACT)

        assertTrue("FlexUnlock-SystemBridge:V" in command)
        assertTrue("*:W" in command)
        assertTrue("main" in command)
        assertFalse("events" in command)
        assertFalse("radio" in command)
        assertFalse("all" in command)
    }

    @Test
    fun `full capture keeps useful system buffers but excludes radio`() {
        val command = logcatCommand(DebugLogMode.FULL)

        assertTrue("main" in command)
        assertTrue("system" in command)
        assertTrue("crash" in command)
        assertTrue("events" in command)
        assertTrue("*:D" in command)
        assertFalse("radio" in command)
        assertFalse("all" in command)
    }

    @Test
    fun `compact snapshot covers core runtime services`() {
        val commands = diagnosticCommands(DebugLogMode.COMPACT).map { it.joinToString(" ") }

        assertTrue("dumpsys display" in commands)
        assertTrue("dumpsys window displays" in commands)
        assertTrue("dumpsys activity displays" in commands)
        assertTrue("dumpsys input" in commands)
        assertTrue("dumpsys input_method" in commands)
    }

    @Test
    fun `full snapshot excludes low value global dumps`() {
        val commands = diagnosticCommands(DebugLogMode.FULL).map { it.joinToString(" ") }

        assertTrue("dumpsys SurfaceFlinger" in commands)
        assertTrue("dumpsys activity processes" in commands)
        assertFalse(commands.any { it.startsWith("settings ") })
        assertFalse("getprop" in commands)
    }

    @Test
    fun `log size defaults to fifty megabytes and stays bounded`() {
        assertEquals(50, DebugLogCaptureConfig.DEFAULT_LIMIT_MB)
        assertEquals(10, normalizeDebugLogLimitMb(1))
        assertEquals(125, normalizeDebugLogLimitMb(125))
        assertEquals(500, normalizeDebugLogLimitMb(1_000))
    }
}
