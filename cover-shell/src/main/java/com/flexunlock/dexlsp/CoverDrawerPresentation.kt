package com.flexunlock.dexlsp

internal const val COVER_NOTIFICATION_PAGER_OFFSET_MARK =
    "flexunlockNotificationPagerOffsetX"

internal fun coverDrawerLogicalScale(
    logicalWidthPx: Int,
    logicalHeightPx: Int,
    nativeWidthPx: Int,
    nativeHeightPx: Int,
    rotation: Int
): Float {
    val rotated = rotation == android.view.Surface.ROTATION_90 ||
        rotation == android.view.Surface.ROTATION_270
    val widthScale = logicalWidthPx.toFloat() / if (rotated) nativeHeightPx else nativeWidthPx
    val heightScale = logicalHeightPx.toFloat() / if (rotated) nativeWidthPx else nativeHeightPx
    return minOf(widthScale, heightScale)
}

internal fun safeCoverDrawerIconSizePx(
    isLandscape: Boolean,
    requestedSizePx: Int,
    itemWidthPx: Int,
    itemHeightPx: Int,
    paddingLeftPx: Int,
    paddingTopPx: Int,
    paddingRightPx: Int,
    paddingBottomPx: Int,
    labelHeightPx: Int,
    labelGapPx: Int,
    edgeSafetyPx: Int
): Int {
    if (!isLandscape) return requestedSizePx.coerceAtLeast(1)
    return safeCoverLabeledIconSizePx(
        requestedSizePx,
        itemWidthPx,
        itemHeightPx,
        paddingLeftPx,
        paddingTopPx,
        paddingRightPx,
        paddingBottomPx,
        labelHeightPx,
        labelGapPx,
        edgeSafetyPx
    )
}

internal fun safeCoverHomeIconSizePx(
    hasVisibleLabel: Boolean,
    requestedSizePx: Int,
    itemWidthPx: Int,
    itemHeightPx: Int,
    paddingLeftPx: Int,
    paddingTopPx: Int,
    paddingRightPx: Int,
    paddingBottomPx: Int,
    labelHeightPx: Int,
    labelGapPx: Int,
    edgeSafetyPx: Int
): Int {
    if (!hasVisibleLabel || itemWidthPx <= 0 || itemHeightPx <= 0) {
        return requestedSizePx.coerceAtLeast(1)
    }
    return safeCoverLabeledIconSizePx(
        requestedSizePx,
        itemWidthPx,
        itemHeightPx,
        paddingLeftPx,
        paddingTopPx,
        paddingRightPx,
        paddingBottomPx,
        labelHeightPx,
        labelGapPx,
        edgeSafetyPx
    )
}

private fun safeCoverLabeledIconSizePx(
    requestedSizePx: Int,
    itemWidthPx: Int,
    itemHeightPx: Int,
    paddingLeftPx: Int,
    paddingTopPx: Int,
    paddingRightPx: Int,
    paddingBottomPx: Int,
    labelHeightPx: Int,
    labelGapPx: Int,
    edgeSafetyPx: Int
): Int {
    val safeWidth = itemWidthPx - paddingLeftPx - paddingRightPx - edgeSafetyPx
    val safeHeight = itemHeightPx -
        paddingTopPx -
        paddingBottomPx -
        labelHeightPx -
        labelGapPx -
        edgeSafetyPx
    return minOf(requestedSizePx, safeWidth, safeHeight).coerceAtLeast(1)
}

// Measured on Flip5 cover at 0°: workspace_tab_layout.bottom=102, cell_layout.top=137.
internal const val COVER_DRAWER_TAB_APP_GAP_PX = 35

internal fun coverDrawerUsesRotatedGap(rotation: Int, stableFull: Boolean): Boolean =
    rotation == android.view.Surface.ROTATION_180 || stableFull && (
        rotation == android.view.Surface.ROTATION_90 ||
            rotation == android.view.Surface.ROTATION_270
        )

internal fun coverDrawerRotation180RecyclerShiftPx(
    tabBottomOnScreenPx: Int,
    cellTopOnScreenPx: Int,
    targetGapPx: Int = COVER_DRAWER_TAB_APP_GAP_PX
): Int = ((cellTopOnScreenPx - tabBottomOnScreenPx) - targetGapPx).coerceIn(0, 160)

internal fun coverDrawerRotatedRecyclerShiftPx(
    tabBottomOnScreenPx: Int,
    cellTopOnScreenPx: Int,
    targetGapPx: Int = COVER_DRAWER_TAB_APP_GAP_PX
): Int = (tabBottomOnScreenPx + targetGapPx - cellTopOnScreenPx).coerceIn(-160, 160)

internal fun coverDrawerStableTopPaddingPx(
    recyclerTopOnScreenPx: Int,
    recyclerTranslationYPx: Int,
    tabBottomOnScreenPx: Int,
    targetGapPx: Int = COVER_DRAWER_TAB_APP_GAP_PX
): Int = (
    tabBottomOnScreenPx + targetGapPx -
        (recyclerTopOnScreenPx - recyclerTranslationYPx)
    ).coerceAtLeast(0)

internal fun coverDrawerCenteredIconTopPx(
    itemHeightPx: Int,
    paddingTopPx: Int,
    paddingBottomPx: Int,
    iconHeightPx: Int,
    labelHeightPx: Int,
    labelGapPx: Int
): Int {
    val contentHeight = iconHeightPx + labelHeightPx + labelGapPx
    val availableHeight = itemHeightPx - paddingTopPx - paddingBottomPx
    return paddingTopPx + ((availableHeight - contentHeight).coerceAtLeast(0) / 2)
}

internal fun coverHomeIconSizeForCellPx(requestedSizePx: Int, availableSizePx: Int): Int {
    val maximum = minOf(MAX_COVER_ICON_SIZE_PX, (availableSizePx - 2).coerceAtLeast(1))
    val minimum = minOf(MIN_COVER_ICON_SIZE_PX, maximum)
    return minimum + (requestedSizePx - MIN_COVER_ICON_SIZE_PX)
        .coerceIn(0, MAX_COVER_ICON_SIZE_PX - MIN_COVER_ICON_SIZE_PX) *
        (maximum - minimum) / (MAX_COVER_ICON_SIZE_PX - MIN_COVER_ICON_SIZE_PX)
}

internal fun coverDrawerInitialTopPaddingPx(
    stableFull: Boolean,
    baseTopPaddingPx: Int,
    workTabMeasuredHeightPx: Int,
    workTabLayoutHeightPx: Int,
    workTabTranslationYPx: Int = 0
): Int = if (stableFull) {
    maxOf(
        baseTopPaddingPx,
        workTabMeasuredHeightPx,
        workTabLayoutHeightPx
    ) + workTabTranslationYPx.coerceAtLeast(0)
} else {
    baseTopPaddingPx
}.coerceAtLeast(0)

internal fun coverDrawerWorkTabTranslationPx(
    statusBarHeightPx: Int,
    hostTopPx: Int
): Int = (statusBarHeightPx - hostTopPx).coerceAtLeast(0)

internal fun coverQsEdgePullFromTop(
    rotation: Int,
    yPx: Float,
    zonePx: Float,
    @Suppress("UNUSED_PARAMETER") heightPx: Int = 0
): Boolean = rotation != 0 && yPx >= 0f && yPx <= zonePx

internal fun coverDrawerSearchButtonTopMarginPx(
    currentGapPx: Int,
    targetGapPx: Int,
    currentTopMarginPx: Int
): Int {
    val extra = targetGapPx - currentGapPx
    return (currentTopMarginPx + extra).coerceAtLeast(0)
}

internal fun coverDrawerLandSearchClusterMinHeightPx(
    moreHeightPx: Int,
    searchHeightPx: Int,
    gapPx: Int
): Int = (moreHeightPx.coerceAtLeast(1) + searchHeightPx.coerceAtLeast(1) + gapPx.coerceAtLeast(0))

internal fun coverDrawerCellWidthPx(
    layoutWidthPx: Int,
    searchReserveLeftPx: Int,
    columnCount: Int
): Int {
    val columns = columnCount.coerceAtLeast(1)
    val width = layoutWidthPx.coerceAtLeast(1)
    val usable = if (searchReserveLeftPx in 1 until width) {
        searchReserveLeftPx
    } else {
        width
    }
    return (usable / columns).coerceAtLeast(1)
}

internal fun coverDrawerCellHeightPx(
    layoutHeightPx: Int,
    rowCount: Int
): Int = (layoutHeightPx.coerceAtLeast(1) / rowCount.coerceAtLeast(1)).coerceAtLeast(1)

internal fun coverGestureSafeWidthPx(
    rotation: Int,
    displayWidthPx: Int,
    bottomCutoutLeftPx: Int?,
    fullQs: Boolean
): Float {
    val width = displayWidthPx.coerceAtLeast(1)
    if (rotation != android.view.Surface.ROTATION_0) return width.toFloat()
    if (bottomCutoutLeftPx != null && bottomCutoutLeftPx in 1 until width) {
        return bottomCutoutLeftPx.toFloat()
    }
    return if (fullQs) width * 379f / 748f else width.toFloat()
}

internal fun coverGestureRegionType(
    xPx: Float,
    yPx: Float,
    displayHeightPx: Int,
    gestureTopPx: Float,
    gestureWidthPx: Float
): String? {
    if (yPx < gestureTopPx || yPx > displayHeightPx || xPx < 0f || xPx > gestureWidthPx) {
        return null
    }
    return when {
        xPx < gestureWidthPx / 3f -> "RECENT"
        xPx < gestureWidthPx * 2f / 3f -> "HOME"
        else -> "BACK"
    }
}

internal fun coverGestureBottomTopPx(
    displayHeightPx: Int,
    navigationBarHeightPx: Int,
    nativeTopPx: Float
): Float {
    val height = displayHeightPx.coerceAtLeast(1)
    val navigationHeight = navigationBarHeightPx.coerceIn(1, height)
    val expectedTop = (height - navigationHeight).toFloat()
    val minimumHeight = (navigationHeight / 2).coerceAtLeast(1)
    return nativeTopPx.takeIf {
        it >= expectedTop && height - it >= minimumHeight
    } ?: expectedTop
}

internal fun coverGestureConfiguredTopPx(
    displayHeightPx: Int,
    navigationBarHeightPx: Int,
    configuredTopPx: Float?,
    configuredBottomPx: Float?
): Float {
    val height = displayHeightPx.coerceAtLeast(1)
    val top = configuredTopPx ?: -1f
    val bottom = configuredBottomPx ?: -1f
    val span = bottom - top
    val minimumSpan = (height / 40).coerceAtLeast(8)
    if (
        kotlin.math.abs(bottom - height) <= 1f &&
        span >= minimumSpan &&
        span <= height / 4f
    ) return top
    val fallbackHeight = maxOf(
        navigationBarHeightPx.coerceIn(1, height),
        (height * 53f / 720f).toInt().coerceAtLeast(1)
    )
    return (height - fallbackHeight.coerceAtMost(height)).toFloat()
}

internal fun coverDrawerBottomPaddingPx(
    rotation: Int,
    portraitPaddingPx: Int,
    landscape: Boolean = false
): Int = if (
    landscape ||
    rotation == android.view.Surface.ROTATION_90 ||
    rotation == android.view.Surface.ROTATION_270
) {
    0
} else {
    portraitPaddingPx.coerceAtLeast(0)
}

internal fun coverDrawerLayoutSettled(
    layoutWidthPx: Int,
    layoutHeightPx: Int,
    screenWidthPx: Int,
    screenHeightPx: Int
): Boolean {
    if (layoutWidthPx <= 4 || layoutHeightPx <= 4) return false
    val minScreen = minOf(screenWidthPx, screenHeightPx).coerceAtLeast(1)
    return layoutWidthPx >= minScreen * 72 / 100 &&
        layoutHeightPx >= minScreen * 55 / 100
}

internal data class CoverQsSpecialPlacement(
    val mediaTopPx: Int?,
    val brightnessTopPx: Int?
)

internal fun coverQsContentBottomPx(
    tileBottomPx: Int,
    gapPx: Int,
    mediaTopPx: Int?,
    mediaHeightPx: Int,
    brightnessTopPx: Int?,
    brightnessHeightPx: Int
): Int {
    val gap = gapPx.coerceAtLeast(0)
    var bottom = tileBottomPx.coerceAtLeast(0)
    if (mediaTopPx != null) {
        bottom = maxOf(bottom, mediaTopPx + mediaHeightPx.coerceAtLeast(0))
    }
    if (brightnessTopPx != null) {
        bottom = maxOf(bottom, brightnessTopPx + brightnessHeightPx.coerceAtLeast(0))
    }
    return bottom + gap
}

internal fun coverQsChromePlacementActive(expandedFraction: Float): Boolean =
    expandedFraction > 0.001f

internal fun coverQsCustomLayoutReady(expandedFraction: Float, preDrawCount: Int): Boolean =
    coverQsChromePlacementActive(expandedFraction) && preDrawCount >= 2

internal fun coverQsChromeRevealAlpha(expandedFraction: Float): Float =
    if (coverQsChromePlacementActive(expandedFraction)) 1f else 0f

internal fun coverCompactLauncherUiEnabled(fullDexEnabled: Boolean): Boolean = !fullDexEnabled

internal fun coverScaleDimensionForWidth(
    valuePx: Int,
    sourceWidthPx: Int,
    targetWidthPx: Int
): Int = (valuePx.coerceAtLeast(0).toLong() * targetWidthPx.coerceAtLeast(1) /
    sourceWidthPx.coerceAtLeast(1)).toInt().coerceAtLeast(0)

internal data class CoverQsVerticalClipBounds(
    val topPx: Int,
    val bottomPx: Int
)

internal fun coverQsVerticalClipBounds(
    viewTopPx: Int,
    viewHeightPx: Int,
    viewportHeightPx: Int
): CoverQsVerticalClipBounds {
    val height = viewHeightPx.coerceAtLeast(0)
    val viewportHeight = viewportHeightPx.coerceAtLeast(0)
    val visibleTop = (-viewTopPx).coerceIn(0, height)
    val visibleBottom = (viewportHeight - viewTopPx).coerceIn(0, height)
    return if (visibleBottom > visibleTop) {
        CoverQsVerticalClipBounds(visibleTop, visibleBottom)
    } else {
        CoverQsVerticalClipBounds(0, 0)
    }
}

internal fun coverQsOpenOffset(
    dragging: Boolean,
    userScrolled: Boolean,
    currentOffset: Float,
    minOffset: Float
): Float {
    if (!userScrolled && !dragging) return 0f
    return currentOffset.coerceIn(minOf(minOffset, 0f), 0f)
}

internal fun coverQsSpecialPlacement(
    tileBottomPx: Int,
    gapPx: Int,
    mediaHeightPx: Int,
    brightnessHeightPx: Int,
    @Suppress("UNUSED_PARAMETER") panelHeightPx: Int,
    @Suppress("UNUSED_PARAMETER") bottomSafePx: Int,
    mediaHidden: Boolean,
    brightnessHidden: Boolean,
    brightnessFirst: Boolean,
    pairGapPx: Int = gapPx
): CoverQsSpecialPlacement {
    val tileBottom = tileBottomPx.coerceAtLeast(0)
    val gap = gapPx.coerceAtLeast(0)
    val pairGap = pairGapPx.coerceAtLeast(gap)
    val mediaH = mediaHeightPx.coerceAtLeast(0)
    val brightnessH = brightnessHeightPx.coerceAtLeast(0)
    if (mediaHidden && brightnessHidden) {
        return CoverQsSpecialPlacement(mediaTopPx = null, brightnessTopPx = null)
    }
    if (mediaHidden) {
        return CoverQsSpecialPlacement(
            mediaTopPx = null,
            brightnessTopPx = tileBottom + gap
        )
    }
    if (brightnessHidden) {
        return CoverQsSpecialPlacement(
            mediaTopPx = tileBottom + gap,
            brightnessTopPx = null
        )
    }
    return if (brightnessFirst) {
        val brightnessTop = tileBottom + gap
        CoverQsSpecialPlacement(
            mediaTopPx = coverQsStackedTopPx(brightnessTop, brightnessH, pairGap),
            brightnessTopPx = brightnessTop
        )
    } else {
        val mediaTop = tileBottom + gap
        CoverQsSpecialPlacement(
            mediaTopPx = mediaTop,
            brightnessTopPx = coverQsStackedTopPx(mediaTop, mediaH, pairGap)
        )
    }
}

internal fun coverQsSpecialPairGapPx(
    rowGapPx: Int,
    @Suppress("UNUSED_PARAMETER") lastRowInsetPx: Int = 0
): Int = rowGapPx.coerceAtLeast(0)

internal fun coverQsDrawnHeightPx(
    layoutHeightPx: Int,
    descendantRelativeBottomsPx: Iterable<Int>
): Int {
    val layout = layoutHeightPx.coerceAtLeast(0)
    val drawn = descendantRelativeBottomsPx.maxOrNull()?.coerceAtLeast(0) ?: 0
    return maxOf(layout, drawn)
}

/**
 * Tile cells are taller than their icons. The visual gap from the last icon to
 * brightness is therefore rowGap plus the unused padding under the icon; the
 * brightness/media capsules fill their views, so they need that extra inset or
 * they look glued together.
 */
internal fun coverQsLastRowInsetPx(
    lastRowHeightPx: Int,
    lastRowContentBottomPx: Int,
    lastRowViewBottomPx: Int
): Int {
    val fromContent = (lastRowViewBottomPx - lastRowContentBottomPx).coerceAtLeast(0)
    if (fromContent > 0) return fromContent
    return lastRowHeightPx.coerceAtLeast(0) / 4
}

internal fun coverQsStackedTopPx(
    firstTopPx: Int,
    firstHeightPx: Int,
    gapPx: Int
): Int = firstTopPx + firstHeightPx.coerceAtLeast(0) + gapPx.coerceAtLeast(0)

internal fun coverQsOverlapPushPx(
    firstTopPx: Int,
    firstHeightPx: Int,
    secondTopPx: Int,
    gapPx: Int
): Int = (coverQsStackedTopPx(firstTopPx, firstHeightPx, gapPx) - secondTopPx).coerceAtLeast(0)

internal data class CoverTopStatusBand(
    val leftPx: Int,
    val widthPx: Int
)

/**
 * Usable top status-bar strip after the camera island. Rotation 90 puts the
 * island on the right of the top edge, so the battery end must sit left of
 * that bite instead of using the full window width.
 */
internal fun coverTopStatusBand(
    rotation: Int,
    windowWidthPx: Int,
    windowHeightPx: Int,
    cutoutRects: List<CoverCutoutRect>,
    headerHeightPx: Int
): CoverTopStatusBand {
    val windowW = windowWidthPx.coerceAtLeast(1)
    if (rotation == android.view.Surface.ROTATION_90 ||
        rotation == android.view.Surface.ROTATION_270
    ) {
        return CoverTopStatusBand(leftPx = 0, widthPx = windowW)
    }
    val headerH = headerHeightPx.coerceAtLeast(1)
    var left = 0
    var right = windowW
    cutoutRects.forEach { rect ->
        if (rect.width <= 0 || rect.height <= 0) return@forEach
        if (rect.bottom <= 0 || rect.top >= headerH) return@forEach
        if (rect.left <= 1) left = maxOf(left, rect.right)
        if (rect.right >= windowW - 1) right = minOf(right, rect.left)
        if (rect.top <= 1 && rect.width >= rect.height) {
            if (rect.left > windowW / 2) right = minOf(right, rect.left)
            else if (rect.right < windowW / 2) left = maxOf(left, rect.right)
        }
    }
    val width = (right - left).coerceIn(1, windowW)
    return CoverTopStatusBand(leftPx = left.coerceAtLeast(0), widthPx = width)
}

internal data class CoverQsGridGeometry(
    val columns: Int,
    val cellWidthPx: Int,
    val gapPx: Int
) {
    val spanPx: Int = cellWidthPx * columns + gapPx * (columns - 1).coerceAtLeast(0)
}

/**
 * Samsung positions a tile at `(cellWidth + gap) * column`; layout padding is
 * not part of that formula. Fit the configured column count directly into the
 * visible local width, preserving the native tile size whenever it fits.
 */
internal fun coverQsGridGeometry(
    visibleWidthPx: Int,
    columns: Int,
    nativeCellWidthPx: Int,
    minimumGapPx: Int
): CoverQsGridGeometry {
    val count = columns.coerceAtLeast(1)
    val width = visibleWidthPx.coerceAtLeast(1)
    val nativeCell = nativeCellWidthPx.coerceAtLeast(1)
    if (count == 1) {
        return CoverQsGridGeometry(count, minOf(nativeCell, width), 0)
    }
    val minimumGap = minimumGapPx.coerceAtLeast(0)
    val maximumCell = ((width - minimumGap * (count - 1)) / count).coerceAtLeast(1)
    val cellWidth = minOf(nativeCell, maximumCell)
    val gap = ((width - cellWidth * count) / (count - 1)).coerceAtLeast(0)
    return CoverQsGridGeometry(count, cellWidth, gap)
}

internal fun coverQsCenteredGridLeft(
    screenWidthPx: Int,
    leftSafePx: Int,
    rightSafePx: Int,
    gridWidthPx: Int
): Int {
    val screenWidth = screenWidthPx.coerceAtLeast(1)
    val left = leftSafePx.coerceIn(0, screenWidth)
    val right = (screenWidth - rightSafePx.coerceAtLeast(0)).coerceIn(left, screenWidth)
    val freeWidth = (right - left - gridWidthPx.coerceAtLeast(0)).coerceAtLeast(0)
    return left + freeWidth / 2
}

internal fun coverQsEffectiveGapPx(
    rotation: Int,
    nativeGapPx: Int,
    compactInsetPx: Int
): Int = if (rotation == android.view.Surface.ROTATION_90 ||
    rotation == android.view.Surface.ROTATION_270
) {
    (nativeGapPx - compactInsetPx).coerceAtLeast(0)
} else {
    nativeGapPx.coerceAtLeast(0)
}

internal fun coverQsUnpagedLeftPx(
    screenLeftPx: Float,
    ownTranslationPx: Float,
    pagerOffsetPx: Float
): Float = screenLeftPx - ownTranslationPx - pagerOffsetPx

internal fun coverNotificationPagerOffsetPx(selected: Boolean, widthPx: Int): Float =
    if (selected) -widthPx.coerceAtLeast(1).toFloat() else 0f

internal fun coverNotificationOwnsHorizontalGesture(
    dxPx: Float,
    dyPx: Float,
    touchSlopPx: Int,
    viewportWidthPx: Int
): Boolean {
    val horizontalSlop = maxOf(touchSlopPx.toFloat(), viewportWidthPx.coerceAtLeast(1) * 0.016f)
    return kotlin.math.abs(dxPx) >= horizontalSlop &&
        kotlin.math.abs(dxPx) > kotlin.math.abs(dyPx) * 1.35f
}

internal fun normalizeCoverTileSpec(spec: String): String = when (val value = spec.trim()) {
    "battery", "PowerSaving" -> "BatteryMode"
    else -> value
}

internal fun normalizeCoverTileSpecs(specs: Iterable<String>): List<String> =
    specs.map(::normalizeCoverTileSpec).filter(String::isNotEmpty).distinct()

internal fun isWorkspaceTransitionCancelCallback(methodName: String): Boolean =
    methodName == "cancelState" || methodName == "onCancelScreenAnimation"

internal fun displayMetricsAspectMatches(
    value: com.flexunlock.dexlsp.config.CoverDisplayOverride,
    nativeWidth: Int,
    nativeHeight: Int
): Boolean {
    val expected = value.width.toLong() * nativeHeight.coerceAtLeast(1)
    val actual = value.height.toLong() * nativeWidth.coerceAtLeast(1)
    return kotlin.math.abs(expected - actual) * 50L <= expected
}

internal fun coverQsShouldPinPagerMeasureCache(
    rotation: Int,
    columns: Int,
    pageCount: Int,
    pageHeightPx: Int,
    widthMeasureSpecSizePx: Int
): Boolean =
    (rotation == 1 || rotation == 3) &&
        columns == CoverQsGrid.FIVE_BY_FOUR.columns &&
        pageCount > 0 &&
        pageHeightPx > 0 &&
        widthMeasureSpecSizePx > 0

internal fun coverQsPageOwnsVisibleFrame(pageIndex: Int): Boolean = pageIndex == 0

internal data class CoverQsTileEdgeInset(
    val leftPx: Int,
    val rightPx: Int
)

/**
 * Portrait cover QS (90/270) keeps a left/right camera column. Inset the
 * tile grid by that column so the first/last tile row is not clipped.
 */
internal fun coverQsTileEdgeInset(
    rotation: Int,
    windowWidthPx: Int,
    cutoutRects: List<CoverCutoutRect>
): CoverQsTileEdgeInset {
    if (rotation != 1 && rotation != 3) return CoverQsTileEdgeInset(0, 0)
    val windowW = windowWidthPx.coerceAtLeast(1)
    var left = 0
    var right = 0
    cutoutRects.forEach { rect ->
        if (rect.width <= 0 || rect.height <= 0) return@forEach
        if (rect.height < rect.width) return@forEach
        if (rect.left <= 1) left = maxOf(left, rect.right)
        if (rect.right >= windowW - 1) right = maxOf(right, windowW - rect.left)
    }
    return CoverQsTileEdgeInset(leftPx = left, rightPx = right)
}

/** Positive island height at the top for 180°; 0 otherwise. */
internal fun coverQsIslandLiftPx(
    rotation: Int,
    windowHeightPx: Int,
    cutoutRects: List<CoverCutoutRect>
): Int {
    if (rotation != 2) return 0
    val windowH = windowHeightPx.coerceAtLeast(1)
    val fromTop = cutoutRects
        .filter { it.width > 0 && it.height > 0 && it.top <= 1 }
        .maxOfOrNull { it.bottom } ?: 0
    val fromBottom = cutoutRects
        .filter { it.width > 0 && it.height > 0 && it.bottom >= windowH - 1 }
        .maxOfOrNull { windowH - it.top } ?: 0
    return maxOf(fromTop, fromBottom)
}

internal fun qsEditButtonRotation270ExtraYPx(
    rotation: Int,
    hostHeightPx: Int,
    windowHeightPx: Int,
    edgePaddingPx: Int
): Int {
    if (rotation != 3) return 0
    if (hostHeightPx <= 0) return 0
    val window = windowHeightPx.coerceAtLeast(hostHeightPx)
    return (window - hostHeightPx - edgePaddingPx.coerceAtLeast(0)).coerceAtLeast(0)
}

internal data class CoverCutoutRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val width: Int get() = (right - left).coerceAtLeast(0)
    val height: Int get() = (bottom - top).coerceAtLeast(0)
}

internal data class CoverControlSidebarPlacement(
    val onCutoutSide: Boolean,
    val padLeftPx: Int,
    val padTopPx: Int,
    val padRightPx: Int,
    val padBottomPx: Int
)

internal fun coverControlSidebarPlacement(
    sidebarWidthPx: Int,
    cutoutRects: List<CoverCutoutRect>,
    systemBarLeftPx: Int,
    systemBarTopPx: Int,
    innerPadTopPx: Int,
    innerPadHorizontalPx: Int
): CoverControlSidebarPlacement {
    val sidebarW = sidebarWidthPx.coerceAtLeast(1)
    val onCutoutSide = cutoutRects.any { rect ->
        rect.width > 0 &&
            rect.height > 0 &&
            rect.left <= 1 &&
            rect.height >= rect.width &&
            rect.left < sidebarW
    }
    val innerH = innerPadHorizontalPx.coerceAtLeast(0)
    return CoverControlSidebarPlacement(
        onCutoutSide = onCutoutSide,
        padLeftPx = if (onCutoutSide) innerH else systemBarLeftPx.coerceAtLeast(0) + innerH,
        padTopPx = systemBarTopPx.coerceAtLeast(0) + innerPadTopPx.coerceAtLeast(0),
        padRightPx = innerH,
        padBottomPx = 0
    )
}

internal fun coverRotation270SidebarFilletExtraPx(bandWidthPx: Int): Int =
    (bandWidthPx.coerceAtLeast(1) * 34 / 100).coerceIn(18, 26)

internal fun coverRotation270SidebarPaintBand(liveBand: CoverCutoutRect): CoverCutoutRect {
    val extra = coverRotation270SidebarFilletExtraPx(liveBand.width)
    return CoverCutoutRect(
        left = liveBand.left,
        top = liveBand.top,
        right = liveBand.right,
        bottom = liveBand.bottom + extra
    )
}

internal fun coverLiveIslandBand(
    windowWidthPx: Int,
    windowHeightPx: Int,
    cutoutRects: List<CoverCutoutRect>
): CoverCutoutRect? {
    val windowW = windowWidthPx.coerceAtLeast(1)
    val windowH = windowHeightPx.coerceAtLeast(1)
    val camera = cutoutRects.firstOrNull { rect ->
        rect.width > 0 && rect.height > 0
    } ?: return null
    if (camera.left <= 1 && camera.height >= camera.width) {
        return if (camera.top > 1) {
            CoverCutoutRect(0, 0, camera.right.coerceAtLeast(1), camera.top)
        } else {
            CoverCutoutRect(0, camera.bottom, camera.right.coerceAtLeast(1), windowH)
        }
    }
    if (camera.bottom >= windowH - 1 && camera.width >= camera.height) {
        return if (camera.left > 1) {
            CoverCutoutRect(0, camera.top, camera.left, windowH)
        } else {
            CoverCutoutRect(camera.right, camera.top, windowW, windowH)
        }
    }
    return null
}

internal data class CoverQsEditLayout(
    val leftMarginPx: Int,
    val bottomMarginPx: Int,
    val widthPx: Int,
    val heightPx: Int
)

internal fun coverQsEditLiveBottomLayout(
    windowWidthPx: Int,
    windowHeightPx: Int,
    cutoutRects: List<CoverCutoutRect>,
    buttonWidthPx: Int,
    buttonHeightPx: Int,
    edgePaddingPx: Int
): CoverQsEditLayout {
    val windowW = windowWidthPx.coerceAtLeast(1)
    val windowH = windowHeightPx.coerceAtLeast(1)
    val buttonW = buttonWidthPx.coerceAtLeast(1)
    val buttonH = buttonHeightPx.coerceAtLeast(1)
    val pad = edgePaddingPx.coerceAtLeast(0)
    val camera = cutoutRects.firstOrNull { rect ->
        rect.width > 0 && rect.height > 0
    }
    val liveLeft: Int
    val liveRight: Int
    val bandHeight: Int
    if (camera == null) {
        liveLeft = 0
        liveRight = windowW
        bandHeight = buttonH + (pad * 2)
    } else if (camera.width >= camera.height) {
        val cameraAtBottom = camera.bottom >= windowH - 1
        if (cameraAtBottom) {
            liveLeft = if (camera.left > 1) 0 else camera.right
            liveRight = if (camera.left > 1) camera.left else windowW
            bandHeight = camera.height.coerceAtLeast(buttonH)
        } else {
            liveLeft = 0
            liveRight = windowW
            bandHeight = camera.height.coerceAtLeast(buttonH + (pad * 2))
        }
    } else {
        liveLeft = if (camera.left <= 1) camera.right else 0
        liveRight = if (camera.left <= 1) windowW else camera.left
        bandHeight = camera.width.coerceAtLeast(buttonH)
    }
    val usable = (liveRight - liveLeft).coerceAtLeast(buttonW)
    return CoverQsEditLayout(
        leftMarginPx = liveLeft + ((usable - buttonW) / 2).coerceAtLeast(0),
        bottomMarginPx = ((bandHeight - buttonH) / 2).coerceAtLeast(pad),
        widthPx = buttonW,
        heightPx = buttonH.coerceAtMost(bandHeight)
    )
}

internal fun coverControlRotation270CutoutBand(
    windowWidthPx: Int,
    windowHeightPx: Int,
    cutoutRects: List<CoverCutoutRect>
): CoverCutoutRect? = coverLiveIslandBand(windowWidthPx, windowHeightPx, cutoutRects)
