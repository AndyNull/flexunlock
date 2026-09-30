package com.flexunlock.dexlsp

import com.flexunlock.dexlsp.config.resolveFullDexEnabled
import com.flexunlock.dexlsp.system.runtime.coverTimeoutMillisForKeyguard
import com.flexunlock.dexlsp.system.runtime.shouldScheduleCoverTimeoutForDisplayState
import com.flexunlock.dexlsp.system.runtime.fullDexImeOptions
import com.flexunlock.dexlsp.system.runtime.fullDexImeDisplayFlags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverDrawerPresentationTest {
    @Test
    fun `output mode is mutually exclusive`() {
        assertEquals(CoverOutputMode.ORIGINAL, resolveCoverOutputMode(false, CoverQsMode.ORIGINAL))
        assertEquals(CoverOutputMode.FULL_QS, resolveCoverOutputMode(false, CoverQsMode.FULL))
        assertEquals(CoverOutputMode.FULL_DEX, resolveCoverOutputMode(true, CoverQsMode.ORIGINAL))
        assertEquals(CoverOutputMode.FULL_DEX, resolveCoverOutputMode(true, CoverQsMode.FULL))
    }

    @Test
    fun `sideways drawer gap stays isolated to full mode`() {
        assertEquals(false, coverDrawerUsesRotatedGap(1, false))
        assertEquals(true, coverDrawerUsesRotatedGap(1, true))
        assertEquals(true, coverDrawerUsesRotatedGap(2, false))
        assertEquals(false, coverDrawerUsesRotatedGap(0, true))
    }

    @Test
    fun `rotated drawer keeps workspace gap`() {
        assertEquals(35, coverDrawerRotatedRecyclerShiftPx(200, 200))
        assertEquals(-35, coverDrawerRotatedRecyclerShiftPx(200, 270))
    }

    @Test
    fun `full drawer aligns work tabs below status bar without doubling the inset`() {
        assertEquals(12, coverDrawerWorkTabTranslationPx(94, 82))
        assertEquals(0, coverDrawerWorkTabTranslationPx(94, 102))
    }

    @Test
    fun `high resolution keeps drawer geometry scale in every rotation`() {
        assertEquals(1.5f, coverDrawerLogicalScale(1122, 1080, 748, 720, 0), 0.001f)
        assertEquals(1.5f, coverDrawerLogicalScale(1080, 1122, 748, 720, 1), 0.001f)
        assertEquals(1.5f, coverDrawerLogicalScale(1122, 1080, 748, 720, 2), 0.001f)
        assertEquals(1.5f, coverDrawerLogicalScale(1080, 1122, 748, 720, 3), 0.001f)
    }

    @Test
    fun `persisted full DeX switch overrides stale status cache`() {
        assertEquals(false, resolveFullDexEnabled(0, true))
        assertEquals(true, resolveFullDexEnabled(1, false))
        assertEquals(true, resolveFullDexEnabled(-1, true))
    }

    @Test
    fun `full DeX IME disables landscape extract UI only while active`() {
        assertEquals(0x10000000, fullDexImeOptions(0, true))
        assertEquals(7, fullDexImeOptions(7, false))
    }

    @Test
    fun `full DeX exposes the native DeX display flag to Honeyboard`() {
        assertEquals(0x20040, fullDexImeDisplayFlags(0x40, true))
        assertEquals(0x40, fullDexImeDisplayFlags(0x40, false))
    }

    @Test
    fun `cover lockscreen timeout is independent from desktop timeout`() {
        assertEquals(30_000L, coverTimeoutMillisForKeyguard(true, 600_000L))
        assertEquals(300_000L, coverTimeoutMillisForKeyguard(true, 600_000L, 300_000L))
        assertEquals(600_000L, coverTimeoutMillisForKeyguard(false, 600_000L))
        assertEquals(1_000L, coverTimeoutMillisForKeyguard(false, 0L))
    }

    @Test
    fun `display callbacks reset timeout only on a real wake edge`() {
        assertEquals(true, shouldScheduleCoverTimeoutForDisplayState(null, true))
        assertEquals(true, shouldScheduleCoverTimeoutForDisplayState(false, true))
        assertEquals(false, shouldScheduleCoverTimeoutForDisplayState(true, true))
        assertEquals(false, shouldScheduleCoverTimeoutForDisplayState(true, false))
    }

    @Test
    fun `landscape 104px request is capped for 85 by 126 cell`() {
        val size = safeCoverDrawerIconSizePx(
            isLandscape = true,
            requestedSizePx = 104,
            itemWidthPx = 85,
            itemHeightPx = 126,
            paddingLeftPx = 0,
            paddingTopPx = 0,
            paddingRightPx = 0,
            paddingBottomPx = 0,
            labelHeightPx = 17,
            labelGapPx = 3,
            edgeSafetyPx = 6
        )

        assertEquals(79, size)
    }

    @Test
    fun `landscape 77px request is preserved for 85 by 126 cell`() {
        val size = safeCoverDrawerIconSizePx(
            isLandscape = true,
            requestedSizePx = 77,
            itemWidthPx = 85,
            itemHeightPx = 126,
            paddingLeftPx = 0,
            paddingTopPx = 0,
            paddingRightPx = 0,
            paddingBottomPx = 0,
            labelHeightPx = 17,
            labelGapPx = 3,
            edgeSafetyPx = 6
        )

        assertEquals(77, size)
    }

    @Test
    fun `landscape label and padding space can cap icon by height`() {
        val size = safeCoverDrawerIconSizePx(
            isLandscape = true,
            requestedSizePx = 77,
            itemWidthPx = 100,
            itemHeightPx = 70,
            paddingLeftPx = 4,
            paddingTopPx = 5,
            paddingRightPx = 4,
            paddingBottomPx = 7,
            labelHeightPx = 18,
            labelGapPx = 3,
            edgeSafetyPx = 6
        )

        assertEquals(31, size)
    }

    @Test
    fun `portrait preserves configured value when cell has room`() {
        val size = safeCoverDrawerIconSizePx(
            isLandscape = false,
            requestedSizePx = 77,
            itemWidthPx = 100,
            itemHeightPx = 120,
            paddingLeftPx = 4,
            paddingTopPx = 5,
            paddingRightPx = 4,
            paddingBottomPx = 7,
            labelHeightPx = 18,
            labelGapPx = 3,
            edgeSafetyPx = 6
        )

        assertEquals(77, size)
    }

    @Test
    fun `portrait safety calculation never overrides configured value`() {
        val size = safeCoverDrawerIconSizePx(
            isLandscape = false,
            requestedSizePx = 104,
            itemWidthPx = 85,
            itemHeightPx = 70,
            paddingLeftPx = 4,
            paddingTopPx = 5,
            paddingRightPx = 4,
            paddingBottomPx = 7,
            labelHeightPx = 18,
            labelGapPx = 3,
            edgeSafetyPx = 6
        )

        assertEquals(104, size)
    }

    @Test
    fun `home label reserves vertical space before sizing the icon`() {
        assertEquals(
            91,
            safeCoverHomeIconSizePx(
                hasVisibleLabel = true,
                requestedSizePx = 104,
                itemWidthPx = 100,
                itemHeightPx = 120,
                paddingLeftPx = 0,
                paddingTopPx = 0,
                paddingRightPx = 0,
                paddingBottomPx = 0,
                labelHeightPx = 20,
                labelGapPx = 3,
                edgeSafetyPx = 6
            )
        )
    }

    @Test
    fun `home icon keeps configured size when labels are hidden`() {
        assertEquals(
            104,
            safeCoverHomeIconSizePx(
                hasVisibleLabel = false,
                requestedSizePx = 104,
                itemWidthPx = 85,
                itemHeightPx = 70,
                paddingLeftPx = 4,
                paddingTopPx = 5,
                paddingRightPx = 4,
                paddingBottomPx = 7,
                labelHeightPx = 18,
                labelGapPx = 3,
                edgeSafetyPx = 6
            )
        )
    }

    @Test
    fun `search buttons keep 0 deg gap by adjusting top margin`() {
        assertEquals(
            45,
            coverDrawerSearchButtonTopMarginPx(
                currentGapPx = 12,
                targetGapPx = 45,
                currentTopMarginPx = 12
            )
        )
    }

    @Test
    fun `search buttons do not change margin when gap already matches`() {
        assertEquals(
            8,
            coverDrawerSearchButtonTopMarginPx(
                currentGapPx = 45,
                targetGapPx = 45,
                currentTopMarginPx = 8
            )
        )
    }

    @Test
    fun `land search cluster height matches 0 deg 102px buttons with 45px gap`() {
        assertEquals(249, coverDrawerLandSearchClusterMinHeightPx(102, 102, 45))
    }

    @Test
    fun `full QS drawer reserves the native work tab before first layout`() {
        assertEquals(137, coverDrawerInitialTopPaddingPx(true, 22, 0, 137))
        assertEquals(137, coverDrawerInitialTopPaddingPx(true, 22, 137, 120))
        assertEquals(233, coverDrawerInitialTopPaddingPx(true, 22, 137, 120, 96))
        assertEquals(204, coverDrawerInitialTopPaddingPx(true, 22, 204, 204))
    }

    @Test
    fun `rotated drawer uses one stable top padding instead of translation`() {
        assertEquals(231, coverDrawerStableTopPaddingPx(64, 64, 196))
        assertEquals(231, coverDrawerStableTopPaddingPx(0, 0, 196))
        assertEquals(258, coverDrawerStableTopPaddingPx(0, 0, 223))
    }

    @Test
    fun `full drawer centers icon and label as one block`() {
        assertEquals(25, coverDrawerCenteredIconTopPx(197, 10, 10, 120, 27, 0))
        assertEquals(10, coverDrawerCenteredIconTopPx(120, 10, 10, 90, 20, 0))
        assertEquals(13, coverDrawerCenteredIconTopPx(172, 0, 0, 120, 37, -12))
    }

    @Test
    fun `work tab padding restores outside stable full QS`() {
        assertEquals(22, coverDrawerInitialTopPaddingPx(false, 22, 137, 137))
        assertEquals(22, coverDrawerInitialTopPaddingPx(true, 22, 0, 0))
    }

    @Test
    fun `drawer cells use the real layout width`() {
        assertEquals(120, coverDrawerCellWidthPx(724, 0, 6))
        assertEquals(155, coverDrawerCellWidthPx(989, 932, 6))
        assertEquals(124, coverDrawerCellHeightPx(496, 4))
        assertEquals(172, coverDrawerCellHeightPx(689, 4))
        assertEquals(182, coverDrawerCellHeightPx(729, 4))
    }

    @Test
    fun `full QS restores the Flip5 bottom-left gesture width without a cutout`() {
        assertEquals(546.7f, coverGestureSafeWidthPx(0, 1079, null, true), 0.1f)
        assertEquals(1079f, coverGestureSafeWidthPx(0, 1079, null, false), 0.1f)
        assertEquals(1080f, coverGestureSafeWidthPx(1, 1080, null, true), 0.1f)
    }

    @Test
    fun `zero rotation gestures only accept the bottom-left thirds`() {
        val width = coverGestureSafeWidthPx(0, 1079, null, true)
        assertEquals("RECENT", coverGestureRegionType(40f, 1040f, 1080, 1027f, width))
        assertEquals("HOME", coverGestureRegionType(250f, 1040f, 1080, 1027f, width))
        assertEquals("BACK", coverGestureRegionType(500f, 1040f, 1080, 1027f, width))
        assertEquals(null, coverGestureRegionType(40f, 900f, 1080, 1027f, width))
        assertEquals(null, coverGestureRegionType(700f, 1040f, 1080, 1027f, width))
    }

    @Test
    fun `full QS physical gesture coordinates retain the Flip5 bottom-left thirds`() {
        val width = coverGestureSafeWidthPx(0, 748, null, true)
        assertEquals(379f, width, 0.1f)
        assertEquals("RECENT", coverGestureRegionType(100f, 700f, 720, 667f, width))
        assertEquals("HOME", coverGestureRegionType(200f, 700f, 720, 667f, width))
        assertEquals("BACK", coverGestureRegionType(330f, 700f, 720, 667f, width))
        assertEquals(null, coverGestureRegionType(100f, 600f, 720, 667f, width))
    }

    @Test
    fun `full QS ignores a native gesture region expanded to the screen top`() {
        assertEquals(1027f, coverGestureBottomTopPx(1080, 53, 0f), 0.1f)
        assertEquals(1040f, coverGestureBottomTopPx(1080, 53, 1040f), 0.1f)
        assertEquals(667f, coverGestureBottomTopPx(720, 53, 719f), 0.1f)
        assertEquals(667f, coverGestureConfiguredTopPx(720, 1, 667f, 720f), 0.1f)
        assertEquals(667f, coverGestureConfiguredTopPx(720, 1, 719f, 720f), 0.1f)
        assertEquals(667f, coverGestureConfiguredTopPx(720, 1, 0f, 720f), 0.1f)
    }

    @Test
    fun `rotated drawer does not reserve portrait bottom cutout`() {
        assertEquals(72, coverDrawerBottomPaddingPx(0, 72))
        assertEquals(72, coverDrawerBottomPaddingPx(2, 72))
        assertEquals(0, coverDrawerBottomPaddingPx(1, 72))
        assertEquals(0, coverDrawerBottomPaddingPx(3, 72))
        assertEquals(0, coverDrawerBottomPaddingPx(0, 72, landscape = true))
        assertEquals(0, coverDrawerBottomPaddingPx(2, 72, landscape = true))
    }

    @Test
    fun `QS edit button only extra-translates at rotation 270`() {
        assertEquals(
            0,
            qsEditButtonRotation270ExtraYPx(
                rotation = 1,
                hostHeightPx = 594,
                windowHeightPx = 748,
                edgePaddingPx = 8
            )
        )
        assertEquals(
            146,
            qsEditButtonRotation270ExtraYPx(
                rotation = 3,
                hostHeightPx = 594,
                windowHeightPx = 748,
                edgePaddingPx = 8
            )
        )
    }

    @Test
    fun `270 deg left cutout keeps a full-height sidebar in the island column`() {
        val placement = coverControlSidebarPlacement(
            sidebarWidthPx = 196,
            cutoutRects = listOf(CoverCutoutRect(0, 379, 66, 748)),
            systemBarLeftPx = 66,
            systemBarTopPx = 0,
            innerPadTopPx = 12,
            innerPadHorizontalPx = 10
        )
        assertEquals(true, placement.onCutoutSide)
        assertEquals(10, placement.padLeftPx)
        assertEquals(12, placement.padTopPx)
        assertEquals(0, placement.padBottomPx)
    }

    @Test
    fun `0 deg right cutout leaves the left sidebar full height`() {
        val placement = coverControlSidebarPlacement(
            sidebarWidthPx = 196,
            cutoutRects = listOf(CoverCutoutRect(379, 654, 748, 720)),
            systemBarLeftPx = 0,
            systemBarTopPx = 0,
            innerPadTopPx = 12,
            innerPadHorizontalPx = 10
        )
        assertEquals(false, placement.onCutoutSide)
        assertEquals(10, placement.padLeftPx)
        assertEquals(0, placement.padBottomPx)
    }

    @Test
    fun `270 deg live island is above the camera hole`() {
        val band = coverLiveIslandBand(
            windowWidthPx = 720,
            windowHeightPx = 748,
            cutoutRects = listOf(CoverCutoutRect(0, 379, 66, 748))
        )
        assertEquals(0, band?.left)
        assertEquals(0, band?.top)
        assertEquals(66, band?.width)
        assertEquals(379, band?.height)
        val paint = coverRotation270SidebarPaintBand(band!!)
        assertEquals(66, paint.width)
        assertEquals(401, paint.height)
        assertEquals(true, paint.bottom < 748)
    }

    @Test
    fun `0 deg live island is left of the camera hole`() {
        val band = coverLiveIslandBand(
            windowWidthPx = 748,
            windowHeightPx = 720,
            cutoutRects = listOf(CoverCutoutRect(379, 654, 748, 720))
        )
        assertEquals(0, band?.left)
        assertEquals(654, band?.top)
        assertEquals(379, band?.width)
        assertEquals(66, band?.height)
    }

    @Test
    fun `180 deg extra tab-to-app gap becomes a recycler shift`() {
        assertEquals(0, coverDrawerRotation180RecyclerShiftPx(102, 137, 35))
        assertEquals(66, coverDrawerRotation180RecyclerShiftPx(102, 203, 35))
        assertEquals(0, coverDrawerRotation180RecyclerShiftPx(102, 120, 35))
    }

    @Test
    fun `native QS drag owns the visual top on every rotated cover`() {
        assertEquals(false, coverQsEdgePullFromTop(0, 40f, 220f))
        assertEquals(true, coverQsEdgePullFromTop(1, 40f, 220f))
        assertEquals(true, coverQsEdgePullFromTop(2, 40f, 220f, 720))
        assertEquals(true, coverQsEdgePullFromTop(3, 180f, 220f))
        assertEquals(false, coverQsEdgePullFromTop(1, -1f, 220f))
        assertEquals(false, coverQsEdgePullFromTop(2, 400f, 220f, 720))
        assertEquals(false, coverQsEdgePullFromTop(3, 700f, 220f, 720))
    }

    @Test
    fun `chrome activates with QS but keeps opaque geometry`() {
        assertEquals(false, coverQsChromePlacementActive(0f))
        assertEquals(false, coverQsChromePlacementActive(0.001f))
        assertEquals(true, coverQsChromePlacementActive(0.002f))
    }

    @Test
    fun `custom QS layout waits for two native pre-draw frames`() {
        assertEquals(false, coverQsCustomLayoutReady(0.5f, 0))
        assertEquals(false, coverQsCustomLayoutReady(0.5f, 1))
        assertEquals(true, coverQsCustomLayoutReady(0.5f, 2))
        assertEquals(false, coverQsCustomLayoutReady(0.001f, 2))
    }

    @Test
    fun `QS chrome stays visible throughout panel expansion`() {
        assertEquals(1f, coverQsChromeRevealAlpha(0.01f))
        assertEquals(1f, coverQsChromeRevealAlpha(0.5f))
        assertEquals(1f, coverQsChromeRevealAlpha(0.98f))
        assertEquals(1f, coverQsChromeRevealAlpha(1f))
    }

    @Test
    fun `full DeX disables compact launcher presentation`() {
        assertEquals(true, coverCompactLauncherUiEnabled(fullDexEnabled = false))
        assertEquals(false, coverCompactLauncherUiEnabled(fullDexEnabled = true))
    }

    @Test
    fun `QS cell height follows fitted physical width`() {
        assertEquals(90, coverScaleDimensionForWidth(135, 120, 80))
        assertEquals(0, coverScaleDimensionForWidth(0, 0, 0))
    }

    @Test
    fun `chrome below the QS viewport is fully clipped at collapsed geometry`() {
        assertEquals(
            CoverQsVerticalClipBounds(0, 0),
            coverQsVerticalClipBounds(
                viewTopPx = 684,
                viewHeightPx = 98,
                viewportHeightPx = 643
            )
        )
    }

    @Test
    fun `chrome is progressively revealed while it slides into the QS viewport`() {
        assertEquals(
            CoverQsVerticalClipBounds(0, 43),
            coverQsVerticalClipBounds(
                viewTopPx = 600,
                viewHeightPx = 98,
                viewportHeightPx = 643
            )
        )
        assertEquals(
            CoverQsVerticalClipBounds(0, 98),
            coverQsVerticalClipBounds(
                viewTopPx = 500,
                viewHeightPx = 98,
                viewportHeightPx = 643
            )
        )
    }

    @Test
    fun `chrome is clipped in reverse while QS content slides out`() {
        assertEquals(
            CoverQsVerticalClipBounds(0, 43),
            coverQsVerticalClipBounds(
                viewTopPx = 600,
                viewHeightPx = 98,
                viewportHeightPx = 643
            )
        )
        assertEquals(
            CoverQsVerticalClipBounds(40, 98),
            coverQsVerticalClipBounds(
                viewTopPx = -40,
                viewHeightPx = 98,
                viewportHeightPx = 643
            )
        )
    }

    @Test
    fun `90 and 270 QS edit center in the bottom live band`() {
        val layout = coverQsEditLiveBottomLayout(
            windowWidthPx = 720,
            windowHeightPx = 748,
            cutoutRects = listOf(CoverCutoutRect(0, 379, 66, 748)),
            buttonWidthPx = 58,
            buttonHeightPx = 28,
            edgePaddingPx = 8
        )
        assertEquals(364, layout.leftMarginPx)
        assertEquals(19, layout.bottomMarginPx)
    }

    @Test
    fun `180 deg QS edit centers on the full bottom edge`() {
        val layout = coverQsEditLiveBottomLayout(
            windowWidthPx = 748,
            windowHeightPx = 720,
            cutoutRects = listOf(CoverCutoutRect(0, 0, 369, 66)),
            buttonWidthPx = 58,
            buttonHeightPx = 28,
            edgePaddingPx = 8
        )
        assertEquals(345, layout.leftMarginPx)
    }

    @Test
    fun `rotated top status band relies on native side padding`() {
        val island = listOf(CoverCutoutRect(654, 0, 720, 369))
        val band = coverTopStatusBand(1, 720, 748, island, 48)
        assertEquals(0, band.leftPx)
        assertEquals(720, band.widthPx)
        assertEquals(720, coverTopStatusBand(3, 720, 748, island, 48).widthPx)
        val full = coverTopStatusBand(0, 748, 720, island, 48)
        assertEquals(748, full.widthPx)
    }

    @Test
    fun `90 and 270 QS tiles inset by the side island`() {
        val rightIsland = listOf(CoverCutoutRect(654, 0, 720, 369))
        val leftIsland = listOf(CoverCutoutRect(0, 379, 66, 748))
        assertEquals(CoverQsTileEdgeInset(0, 66), coverQsTileEdgeInset(1, 720, rightIsland))
        assertEquals(CoverQsTileEdgeInset(66, 0), coverQsTileEdgeInset(3, 720, leftIsland))
        assertEquals(CoverQsTileEdgeInset(0, 0), coverQsTileEdgeInset(0, 748, leftIsland))
    }

    @Test
    fun `five column landscape QS keeps positive gaps inside side-safe widths`() {
        listOf(720, 748).forEach { windowWidth ->
            val visibleWidth = windowWidth - 66
            val geometry = coverQsGridGeometry(
                visibleWidthPx = visibleWidth,
                columns = 5,
                nativeCellWidthPx = 111,
                minimumGapPx = 17
            )

            assertEquals(5, geometry.columns)
            assertTrue(geometry.gapPx > 0)
            assertTrue(geometry.spanPx <= visibleWidth)
        }
    }

    @Test
    fun `runtime landscape QS page fits five tiles without visual overlap`() {
        val geometry = coverQsGridGeometry(
            visibleWidthPx = 592,
            columns = 5,
            nativeCellWidthPx = 111,
            minimumGapPx = 17
        )

        assertEquals(5, geometry.columns)
        assertEquals(104, geometry.cellWidthPx)
        assertEquals(18, geometry.gapPx)
        assertEquals(592, geometry.spanPx)
    }

    @Test
    fun `centered QS grid consumes the safe rectangle instead of the raw screen`() {
        assertEquals(66, coverQsCenteredGridLeft(720, 66, 66, 588))
        assertEquals(80, coverQsCenteredGridLeft(720, 66, 66, 560))
    }

    @Test
    fun `asymmetric camera safe rectangle centers the five-column grid`() {
        assertEquals(31, coverQsCenteredGridLeft(720, 0, 66, 592))
        assertEquals(97, coverQsCenteredGridLeft(720, 66, 0, 592))
    }

    @Test
    fun `rotated five-column QS pins the native pager measure cache`() {
        listOf(1, 3).forEach { rotation ->
            assertEquals(
                true,
                coverQsShouldPinPagerMeasureCache(
                    rotation = rotation,
                    columns = 5,
                    pageCount = 1,
                    pageHeightPx = 546,
                    widthMeasureSpecSizePx = 720
                )
            )
        }
    }

    @Test
    fun `pager cache stays native outside a ready rotated five-column layout`() {
        val base = { rotation: Int, columns: Int, pages: Int, height: Int, width: Int ->
            coverQsShouldPinPagerMeasureCache(rotation, columns, pages, height, width)
        }
        assertEquals(false, base(0, 5, 1, 546, 748))
        assertEquals(false, base(3, 4, 1, 546, 720))
        assertEquals(false, base(3, 5, 0, 546, 720))
        assertEquals(false, base(3, 5, 1, 0, 720))
        assertEquals(false, base(3, 5, 1, 546, 0))
    }

    @Test
    fun `only the first QS page owns visible safe-area translation`() {
        assertEquals(true, coverQsPageOwnsVisibleFrame(0))
        assertEquals(false, coverQsPageOwnsVisibleFrame(1))
        assertEquals(false, coverQsPageOwnsVisibleFrame(-1))
    }

    @Test
    fun `180 deg QS lifts by the top island height`() {
        val island = listOf(CoverCutoutRect(0, 0, 369, 66))
        assertEquals(66, coverQsIslandLiftPx(2, 720, island))
        assertEquals(0, coverQsIslandLiftPx(0, 720, island))
        assertEquals(0, coverQsIslandLiftPx(1, 748, island))
    }

    @Test
    fun `drawer cell size waits until the swipe-open layout has settled`() {
        assertEquals(false, coverDrawerLayoutSettled(180, 200, 720, 748))
        assertEquals(true, coverDrawerLayoutSettled(720, 500, 720, 748))
    }

    @Test
    fun `media and brightness stack below tiles without overlapping when they overflow`() {
        val placement = coverQsSpecialPlacement(
            tileBottomPx = 600,
            gapPx = 8,
            mediaHeightPx = 72,
            brightnessHeightPx = 64,
            panelHeightPx = 720,
            bottomSafePx = 20,
            mediaHidden = false,
            brightnessHidden = false,
            brightnessFirst = false
        )
        assertEquals(608, placement.mediaTopPx)
        assertEquals(688, placement.brightnessTopPx)
    }

    @Test
    fun `brightness-first still keeps media below the brightness row`() {
        val placement = coverQsSpecialPlacement(
            tileBottomPx = 600,
            gapPx = 8,
            mediaHeightPx = 72,
            brightnessHeightPx = 64,
            panelHeightPx = 720,
            bottomSafePx = 20,
            mediaHidden = false,
            brightnessHidden = false,
            brightnessFirst = true
        )
        assertEquals(608, placement.brightnessTopPx)
        assertEquals(680, placement.mediaTopPx)
    }

    @Test
    fun `brightness-first content bottom includes the media card`() {
        val placement = coverQsSpecialPlacement(
            tileBottomPx = 600,
            gapPx = 8,
            mediaHeightPx = 72,
            brightnessHeightPx = 64,
            panelHeightPx = 720,
            bottomSafePx = 36,
            mediaHidden = false,
            brightnessHidden = false,
            brightnessFirst = true
        )
        val contentBottom = coverQsContentBottomPx(
            tileBottomPx = 600,
            gapPx = 8,
            mediaTopPx = placement.mediaTopPx,
            mediaHeightPx = 72,
            brightnessTopPx = placement.brightnessTopPx,
            brightnessHeightPx = 64
        )
        assertEquals(760, contentBottom)
        assertEquals(true, contentBottom > 720 - 36)
    }

    @Test
    fun `brightness-first uses the same gap between special tiles as brightness-last`() {
        val gap = 8
        val mediaHeight = 72
        val brightnessHeight = 36
        val mediaFirst = coverQsSpecialPlacement(
            tileBottomPx = 400,
            gapPx = gap,
            mediaHeightPx = mediaHeight,
            brightnessHeightPx = brightnessHeight,
            panelHeightPx = 720,
            bottomSafePx = 20,
            mediaHidden = false,
            brightnessHidden = false,
            brightnessFirst = false
        )
        val brightnessFirst = coverQsSpecialPlacement(
            tileBottomPx = 400,
            gapPx = gap,
            mediaHeightPx = mediaHeight,
            brightnessHeightPx = brightnessHeight,
            panelHeightPx = 720,
            bottomSafePx = 20,
            mediaHidden = false,
            brightnessHidden = false,
            brightnessFirst = true
        )
        val mediaFirstGap = mediaFirst.brightnessTopPx!! - (mediaFirst.mediaTopPx!! + mediaHeight)
        val brightnessFirstGap = brightnessFirst.mediaTopPx!! -
            (brightnessFirst.brightnessTopPx!! + brightnessHeight)
        assertEquals(gap, mediaFirstGap)
        assertEquals(gap, brightnessFirstGap)
        assertEquals(mediaFirstGap, brightnessFirstGap)
        assertEquals(
            34,
            coverQsSpecialPairGapPx(rowGapPx = 34, lastRowInsetPx = 20)
        )
        assertEquals(96, coverQsDrawnHeightPx(70, listOf(70, 96, 64)))
        assertEquals(70, coverQsDrawnHeightPx(70, listOf(48, 70)))
        assertEquals(27, coverQsLastRowInsetPx(111, 546, 546))
        assertEquals(20, coverQsLastRowInsetPx(111, 526, 546))
        val spaced = coverQsSpecialPlacement(
            tileBottomPx = 400,
            gapPx = 34,
            mediaHeightPx = mediaHeight,
            brightnessHeightPx = brightnessHeight,
            panelHeightPx = 720,
            bottomSafePx = 20,
            mediaHidden = false,
            brightnessHidden = false,
            brightnessFirst = true,
            pairGapPx = coverQsSpecialPairGapPx(34, 20)
        )
        assertEquals(34, spaced.mediaTopPx!! - (spaced.brightnessTopPx!! + brightnessHeight))
        assertEquals(0, coverQsOverlapPushPx(580, 98, 712, 34))
        assertEquals(132, coverQsOverlapPushPx(580, 98, 580, 34))
        assertEquals(98, coverQsOverlapPushPx(650, 70, 656, 34))
        val brightnessLast = coverQsSpecialPlacement(
            tileBottomPx = 400,
            gapPx = 34,
            mediaHeightPx = mediaHeight,
            brightnessHeightPx = brightnessHeight,
            panelHeightPx = 720,
            bottomSafePx = 20,
            mediaHidden = false,
            brightnessHidden = false,
            brightnessFirst = false,
            pairGapPx = coverQsSpecialPairGapPx(34, 20)
        )
        assertEquals(34, brightnessLast.brightnessTopPx!! - (brightnessLast.mediaTopPx!! + mediaHeight))
        val drawnBrightness = coverQsDrawnHeightPx(70, listOf(96))
        val drawnStack = coverQsSpecialPlacement(
            tileBottomPx = 650,
            gapPx = 34,
            mediaHeightPx = 80,
            brightnessHeightPx = drawnBrightness,
            panelHeightPx = 720,
            bottomSafePx = 36,
            mediaHidden = false,
            brightnessHidden = false,
            brightnessFirst = true,
            pairGapPx = 34
        )
        assertEquals(34, drawnStack.mediaTopPx!! - (drawnStack.brightnessTopPx!! + drawnBrightness))
    }

    @Test
    fun `opening QS stays at the top until the user scrolls`() {
        assertEquals(0f, coverQsOpenOffset(
            dragging = false,
            userScrolled = false,
            currentOffset = -180f,
            minOffset = -180f
        ))
        assertEquals(-180f, coverQsOpenOffset(
            dragging = true,
            userScrolled = true,
            currentOffset = -180f,
            minOffset = -180f
        ))
        assertEquals(-90f, coverQsOpenOffset(
            dragging = false,
            userScrolled = true,
            currentOffset = -90f,
            minOffset = -180f
        ))
    }

    @Test
    fun `portrait QS keeps the full native horizontal gap`() {
        assertEquals(40, coverQsEffectiveGapPx(0, 40, 8))
        assertEquals(40, coverQsEffectiveGapPx(2, 40, 8))
        assertEquals(32, coverQsEffectiveGapPx(1, 40, 8))
        assertEquals(32, coverQsEffectiveGapPx(3, 40, 8))
    }

    @Test
    fun `notification pager offset is excluded from QS frame correction`() {
        assertEquals(31f, coverQsUnpagedLeftPx(-689f, 18f, -738f), 0.001f)
        assertEquals(-748f, coverNotificationPagerOffsetPx(true, 748), 0.001f)
        assertEquals(-720f, coverNotificationPagerOffsetPx(true, 720), 0.001f)
        assertEquals(0f, coverNotificationPagerOffsetPx(false, 720), 0.001f)
    }

    @Test
    fun `low DPI vertical pull does not activate notification pager`() {
        assertTrue(coverNotificationOwnsHorizontalGesture(-36f, 8f, 7, 748))
        assertTrue(!coverNotificationOwnsHorizontalGesture(-10f, 80f, 7, 748))
        assertTrue(!coverNotificationOwnsHorizontalGesture(-10f, 2f, 7, 748))
        assertTrue(!coverNotificationOwnsHorizontalGesture(-30f, 24f, 7, 748))
    }

    @Test
    fun `legacy Samsung power tile specs normalize to native BatteryMode`() {
        assertEquals(
            listOf("Wifi", "BatteryMode", "Bluetooth"),
            normalizeCoverTileSpecs(listOf("Wifi", "battery", "PowerSaving", "Bluetooth"))
        )
    }

    @Test
    fun `workspace cancellation callbacks release pending drawer transition`() {
        assertTrue(isWorkspaceTransitionCancelCallback("cancelState"))
        assertTrue(isWorkspaceTransitionCancelCallback("onCancelScreenAnimation"))
        assertTrue(!isWorkspaceTransitionCancelCallback("doOnStateChangeEnd"))
    }

    @Test
    fun `display metrics use the active channel aspect ratio`() {
        assertTrue(displayMetricsAspectMatches(
            com.flexunlock.dexlsp.config.CoverDisplayOverride(719, 720, 280),
            719,
            720
        ))
        assertTrue(!displayMetricsAspectMatches(
            com.flexunlock.dexlsp.config.CoverDisplayOverride(748, 600, 280),
            719,
            720
        ))
    }

}
