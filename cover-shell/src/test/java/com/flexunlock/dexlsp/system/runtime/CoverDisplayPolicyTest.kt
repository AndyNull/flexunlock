package com.flexunlock.dexlsp.system.runtime

import com.flexunlock.dexlsp.DisplayMetricsSnapshot
import com.flexunlock.dexlsp.CoverDisplaySnapshot
import com.flexunlock.dexlsp.config.CoverDisplayOverride
import org.junit.Assert.assertEquals
import org.junit.Test

class CoverDisplayPolicyTest {
    @Test
    fun `IME compact density uses the configured scale`() {
        assertEquals(204, scaledImeDensity(340, 60))
    }

    @Test
    fun `display rotation does not count as a geometry change`() {
        val landscape = CoverDisplaySnapshot(1, 748, 720, 0, "cover", type = 1)
        val portrait = CoverDisplaySnapshot(1, 720, 748, 1, "cover", type = 1)
        assertEquals(false, coverDisplayGeometryChanged(landscape, portrait))
    }

    @Test
    fun `display size change still triggers policy refresh`() {
        val current = CoverDisplaySnapshot(1, 748, 720, 0, "cover", type = 1)
        val resized = CoverDisplaySnapshot(1, 1080, 1080, 0, "cover", type = 1)
        assertEquals(true, coverDisplayGeometryChanged(current, resized))
    }

    @Test
    fun `rotation lock follows the system setting in every channel`() {
        assertEquals(true, coverRotationLocked(false, true))
        assertEquals(true, coverRotationLocked(true, true))
        assertEquals(false, coverRotationLocked(true, false))
        assertEquals(false, coverRotationLocked(false, false))
    }

    @Test
    fun `rotation lock keeps the current physical angle`() {
        assertEquals(2, coverDisplayRotationTarget(true, 2, 1, 3, 3))
        assertEquals(1, coverDisplayRotationTarget(true, 1, 3, -1, 2))
    }

    @Test
    fun `unlocked rotation follows a valid sensor angle`() {
        assertEquals(3, coverDisplayRotationTarget(false, 0, 1, 3, 0))
    }

    @Test
    fun `unlocked rotation keeps the resolved angle without sensor data`() {
        assertEquals(1, coverDisplayRotationTarget(false, 0, 2, -1, 1))
    }

    @Test
    fun `rotation lock keeps the current angle over stale sensor data`() {
        assertEquals(2, coverDisplayRotationTarget(true, 2, -1, 1, 1))
    }

    @Test
    fun `locking rotation captures the current physical angle`() {
        assertEquals(3, coverClosedTargetRotation(true, 0, 3, 0))
        assertEquals(1, coverClosedTargetRotation(true, 1, 0, 1))
        assertEquals(2, coverClosedTargetRotation(false, 1, 2, 1))
    }

    @Test
    fun `locking rotation prefers the pending sensor angle over stale current rotation`() {
        assertEquals(
            3,
            coverClosedTargetRotation(
                rotationLocked = true,
                currentMode = 0,
                currentRotation = 0,
                userRotation = 0,
                sensorRotation = 3,
                proposedRotation = 3,
                desiredRotation = 3
            )
        )
    }

    @Test
    fun `full canvas keeps a dedicated user override`() {
        val snapshot = DisplayMetricsSnapshot(1, "cover", 748, 720, 340, 748, 720, 340)
        assertEquals(CoverDisplayOverride(719, 720, 340), fullCanvasOverride(snapshot, null))
        assertEquals(
            CoverDisplayOverride(1079, 1080, 280),
            fullCanvasOverride(snapshot, CoverDisplayOverride(1079, 1080, 280))
        )
    }

    @Test
    fun `full canvas accepts near square metrics only`() {
        assertEquals(true, displayMetricsAspectMatches(719, 720, 1, 1))
        assertEquals(false, displayMetricsAspectMatches(748, 600, 1, 1))
    }

    @Test
    fun `external metrics use the selected physical mode`() {
        assertEquals(
            CoverDisplayOverride(1920, 1080, 240),
            externalDisplayMetrics(1920, 1080, 240)
        )
    }

    @Test
    fun `native metrics short circuit requires exact base match`() {
        val target = CoverDisplayOverride(1920, 1080, 240)
        assertEquals(true, displayMetricsMatch(1920, 1080, 240, target))
        assertEquals(false, displayMetricsMatch(1920, 1080, 280, target))
    }

    @Test
    fun `external dex flags keep original capabilities`() {
        assertEquals(0xC0203E3, externalDexDisplayFlags(0x81))
    }

    @Test
    fun `task display forcing stays on the source display`() {
        assertEquals(true, shouldForceTaskLaunchToTarget(null, 1, 1))
        assertEquals(false, shouldForceTaskLaunchToTarget(null, 7, 1))
        assertEquals(true, shouldForceTaskLaunchToTarget(null, null, 1))
        assertEquals(true, shouldForceTaskLaunchToTarget(1, null, 1))
        assertEquals(false, shouldForceTaskLaunchToTarget(6, null, 1))
    }

    @Test
    fun `built in target identity ignores rotation-only changes`() {
        val portrait = com.flexunlock.dexlsp.CoverDisplaySnapshot(
            1, 748, 720, 0, "local:cover", type = 1
        )
        val landscape = portrait.copy(width = 720, height = 748, rotation = 1)

        assertEquals(true, isSameDisplayTarget(portrait, landscape))
        assertEquals(false, isSameDisplayTarget(portrait, landscape.copy(uniqueId = "local:other")))
    }

    @Test
    fun `half mode removes the bottom cutout strip in native geometry`() {
        assertEquals(
            HalfModeRegion(0, 0, 748, 654),
            resolveHalfModeRegion(
                748,
                720,
                listOf(HalfModeRegion(379, 654, 748, 720))
            )
        )
        assertEquals(-33, halfModeDisplayOffset(720, 0, 654))
    }

    @Test
    fun `half mode removes the left cutout strip after rotation`() {
        assertEquals(
            HalfModeRegion(66, 0, 720, 748),
            resolveHalfModeRegion(
                720,
                748,
                listOf(HalfModeRegion(0, 379, 66, 748))
            )
        )
        assertEquals(33, halfModeDisplayOffset(720, 66, 654))
    }

    @Test
    fun `half mode publishes a rounded rectangular display shape`() {
        assertEquals(
            "M49,0 H699 A49,49 0 0 1 748,49 V605 A49,49 0 0 1 699,654 " +
                "H49 A49,49 0 0 1 0,605 V49 A49,49 0 0 1 49,0 Z",
            roundedRectDisplayShapeSpec(748, 654, 49)
        )
    }
}
