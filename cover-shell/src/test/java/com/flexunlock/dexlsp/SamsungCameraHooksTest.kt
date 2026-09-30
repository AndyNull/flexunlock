package com.flexunlock.dexlsp

import org.junit.Assert.assertEquals
import org.junit.Test

class SamsungCameraHooksTest {
    @Test
    fun `original cover camera enters Samsung quick shot mode`() {
        assertEquals(1, cameraQuickShotDisplayId(0, true))
        assertEquals(1, cameraQuickShotDisplayId(1, true))
        assertEquals(0, cameraQuickShotDisplayId(0, false))
    }

    @Test
    fun `right side controls are separated before shutter`() {
        val offsets = cameraControlOffsets(
            bottom = CameraControlRect(825, 1, 1039, 1079),
            mode = CameraControlRect(847, 1, 944, 1079),
            zoom = CameraControlRect(740, 1, 886, 1079),
            windowWidth = 1080,
            windowHeight = 1079,
            gapPx = 17
        )

        assertEquals(CameraControlOffsets(-136, 0, -192, 0), offsets)
        assertEquals(808, 944 + offsets.modeX)
        assertEquals(825, 808 + 17)
        assertEquals(694, 886 + offsets.zoomX)
        assertEquals(711, 694 + 17)
    }

    @Test
    fun `already separated bottom controls keep native positions`() {
        val offsets = cameraControlOffsets(
            bottom = CameraControlRect(0, 800, 1080, 1080),
            mode = CameraControlRect(0, 680, 1080, 760),
            zoom = CameraControlRect(0, 540, 1080, 640),
            windowWidth = 1080,
            windowHeight = 1080,
            gapPx = 20
        )

        assertEquals(CameraControlOffsets(0, 0, 0, 0), offsets)
    }

    @Test
    fun `mode remains separated while zoom controls are rebuilt`() {
        val offsets = cameraControlOffsets(
            bottom = CameraControlRect(825, 161, 1039, 647),
            mode = CameraControlRect(864, 161, 944, 579),
            zoom = null,
            windowWidth = 1080,
            windowHeight = 1079,
            gapPx = 17
        )

        assertEquals(CameraControlOffsets(-136, 0, 0, 0), offsets)
    }

    @Test
    fun `screen offset is inverted through rotated camera root`() {
        val clockwise = cameraLocalOffset(100, 0, 90)
        val counterClockwise = cameraLocalOffset(100, 0, -90)
        val reverse = cameraLocalOffset(100, 0, 180)

        assertEquals(0f, clockwise.first, 0f)
        assertEquals(-100f, clockwise.second, 0f)
        assertEquals(0f, counterClockwise.first, 0f)
        assertEquals(100f, counterClockwise.second, 0f)
        assertEquals(-100f, reverse.first, 0f)
        assertEquals(0f, reverse.second, 0f)
    }

    @Test
    fun `preview appends 180 degrees to every native display transform`() {
        assertEquals(3, cameraBufferTransform180(0))
        assertEquals(2, cameraBufferTransform180(1))
        assertEquals(1, cameraBufferTransform180(2))
        assertEquals(0, cameraBufferTransform180(3))
        assertEquals(7, cameraBufferTransform180(4))
        assertEquals(6, cameraBufferTransform180(5))
        assertEquals(5, cameraBufferTransform180(6))
        assertEquals(4, cameraBufferTransform180(7))
        assertEquals(8, cameraBufferTransform180(8))
    }

    @Test
    fun `preview keeps native transform in portrait and compensates rotated roots`() {
        assertEquals(7, cameraPreviewTransform(4, 0, true))
        assertEquals(7, cameraPreviewTransform(4, 180, true))
        assertEquals(7, cameraPreviewTransform(4, 90, true))
        assertEquals(7, cameraPreviewTransform(4, 270, true))
        assertEquals(4, cameraPreviewTransform(4, 90, false))
        assertEquals(true, cameraPreviewNeedsCompensation(0, true))
        assertEquals(true, cameraPreviewNeedsCompensation(90, true))
    }

    @Test
    fun `surface flip rotates around destination bottom right`() {
        assertEquals(
            CameraSurfaceTransform(945, 1080, -0.75f, -0.8f),
            cameraSurfaceFlipTransform(135, 147, 0.75f, 0.8f, 810, 933)
        )
        assertEquals(
            null,
            cameraSurfaceFlipTransform(0, 0, 0f, 1f, 100, 100)
        )
    }

    @Test
    fun `interactive control bounds exclude full screen containers`() {
        assertEquals(
            CameraControlRect(740, 205, 1015, 875),
            cameraUnionRect(
                listOf(
                    CameraControlRect(874, 205, 991, 322),
                    CameraControlRect(850, 457, 1015, 622),
                    CameraControlRect(740, 758, 991, 875)
                )
            )
        )
        assertEquals(null, cameraUnionRect(emptyList()))
    }

    @Test
    fun `control offsets stay inside the camera window`() {
        val rect = CameraControlRect(740, 1, 886, 1079)

        assertEquals(-740 to 0, cameraBoundedOffset(rect, 1080, 1079, -900, 100))
        assertEquals(-192 to 0, cameraBoundedOffset(rect, 1080, 1079, -192, 0))
    }

}
