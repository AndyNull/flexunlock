package com.flexunlock.dexlsp.config

import com.flexunlock.dexlsp.CoverDisplayResolution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CoverDisplayConfigTest {
    @Test
    fun statusWithoutHalfModeDefaultsToDisabled() {
        val legacyStatus = CoverDisplayConfig.encodeStatus(
            CoverDisplayStatus(
                manualIdentity = null,
                resolution = CoverDisplayResolution.Unavailable("disconnected"),
                candidates = emptyList()
            )
        ).lineSequence().filterNot { it.startsWith("halfMode|") }.joinToString("\n")
        val decoded = CoverDisplayConfig.decodeStatus(legacyStatus)

        assertEquals(false, decoded?.halfModeEnabled)
    }

    @Test
    fun `IME compact percent is bounded`() {
        assertEquals(35, normalizeImeCompactPercent(20))
        assertEquals(60, normalizeImeCompactPercent(60))
        assertEquals(100, normalizeImeCompactPercent(120))
    }

    @Test
    fun `resolution selection keeps preferred refresh`() {
        val modes = listOf(
            CoverDisplayMode(1920, 1080, 60f),
            CoverDisplayMode(1920, 1080, 120f),
            CoverDisplayMode(2560, 1440, 60f),
            CoverDisplayMode(2560, 1440, 120f)
        )

        assertEquals(
            CoverDisplayMode(2560, 1440, 120f),
            selectModeForResolution(modes, modes[2], modes[1])
        )
    }

    @Test
    fun `refresh selection keeps preferred resolution`() {
        val modes = listOf(
            CoverDisplayMode(1920, 1080, 60f),
            CoverDisplayMode(2560, 1440, 60f),
            CoverDisplayMode(1920, 1080, 120f),
            CoverDisplayMode(2560, 1440, 120f)
        )

        assertEquals(
            CoverDisplayMode(1920, 1080, 120f),
            selectModeForRefresh(modes, modes[3], modes[0])
        )
    }

    @Test
    fun `lockscreen timeout accepts only supported presets`() {
        assertEquals(60_000L, normalizeLockscreenTimeoutMillis(60_000L))
        assertEquals(1_980_000L, normalizeLockscreenTimeoutMillis(1_980_000L))
        assertEquals(30_000L, normalizeLockscreenTimeoutMillis(10_000L))
    }

    @Test
    fun `system update package states round trip`() {
        val states = mapOf(
            "com.wssyncmldm" to 0,
            "com.sec.android.soagent" to 3
        )

        assertEquals(
            states,
            decodeSystemUpdatePackageStates(encodeSystemUpdatePackageStates(states))
        )
    }

    @Test
    fun `system update package states reject unrelated packages and invalid states`() {
        assertNull(decodeSystemUpdatePackageStates("v1\ncom.example.update|0"))
        assertNull(decodeSystemUpdatePackageStates("v1\ncom.wssyncmldm|5"))
        assertNull(decodeSystemUpdatePackageStates("v2\ncom.wssyncmldm|0"))
    }

    @Test
    fun cameraModeDefaultsToInnerAndDecodesOriginal() {
        assertEquals(CoverCameraMode.INNER, CoverCameraMode.from(-1))
        assertEquals(CoverCameraMode.INNER, CoverCameraMode.from(0))
        assertEquals(CoverCameraMode.ORIGINAL, CoverCameraMode.from(1))
    }

    @Test
    fun displayOverrideRoundTrips() {
        val value = CoverDisplayOverride(1920, 1080, 280)

        assertEquals(value, CoverDisplayConfig.decodeOverride(CoverDisplayConfig.encodeOverride(value)))
    }

    @Test
    fun nativeDisplayOverrideDecodesAsNull() {
        assertNull(CoverDisplayConfig.decodeOverride(CoverDisplayConfig.AUTO_VALUE))
        assertNull(CoverDisplayConfig.decodeOverride("v1|1920|bad|280"))
    }

    @Test
    fun displayModeRoundTripsAndAutoDecodesAsNull() {
        val value = CoverDisplayMode(2560, 1440, 119.88f)

        assertEquals(value, CoverDisplayConfig.decodeDisplayMode(CoverDisplayConfig.encodeDisplayMode(value)))
        assertNull(CoverDisplayConfig.decodeDisplayMode(CoverDisplayConfig.AUTO_VALUE))
        assertNull(CoverDisplayConfig.decodeDisplayMode("v1|2560|1440|bad"))
        assertEquals(true, displayModesMatch(value, value.copy(refreshRate = 119.885f)))
        assertEquals(false, displayModesMatch(value, value.copy(refreshRate = 60f)))
    }

    @Test
    fun preferredDisplayModeRoundTripsThroughStatus() {
        val mode = CoverDisplayMode(3840, 2160, 60f)
        val supportedModes = listOf(mode, CoverDisplayMode(1920, 1080, 120f))
        val status = CoverDisplayStatus(
            manualIdentity = null,
            resolution = CoverDisplayResolution.Unavailable("disconnected"),
            candidates = emptyList(),
            preferredDisplayMode = mode,
            supportedDisplayModes = supportedModes,
            halfModeEnabled = true
        )

        val decoded = CoverDisplayConfig.decodeStatus(CoverDisplayConfig.encodeStatus(status))
        assertEquals(mode, decoded?.preferredDisplayMode)
        assertEquals(supportedModes, decoded?.supportedDisplayModes)
        assertEquals(true, decoded?.halfModeEnabled)
    }
}
