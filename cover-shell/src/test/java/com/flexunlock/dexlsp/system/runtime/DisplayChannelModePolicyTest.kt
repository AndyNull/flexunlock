package com.flexunlock.dexlsp.system.runtime

import com.flexunlock.dexlsp.CoverQsMode
import com.flexunlock.dexlsp.CoverQsTransaction
import com.flexunlock.dexlsp.CoverQsTransition
import com.flexunlock.dexlsp.DisplayMetricsSnapshot
import com.flexunlock.dexlsp.config.CoverDisplayOverride
import com.flexunlock.dexlsp.system.session.FoldState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayChannelModePolicyTest {
    @Test
    fun fullDexAndFullQsChannelsAreMutuallyExclusive() {
        assertTrue(fullDexRequiresOriginalChannel(true, false))
        assertFalse(fullDexRequiresOriginalChannel(true, true))
        assertFalse(fullDexRequiresOriginalChannel(false, false))
        assertTrue(fullQsRequiresFullDexDisabled(CoverQsMode.FULL, true))
        assertFalse(fullQsRequiresFullDexDisabled(CoverQsMode.ORIGINAL, true))
        assertFalse(fullQsRequiresFullDexDisabled(CoverQsMode.FULL, false))
    }

    @Test
    fun externalFullDexKeepsCurrentDisplayChannel() {
        assertFalse(fullDexRequiresOriginalChannel(true, false, externalTarget = true))
    }

    @Test
    fun refreshModeAcceptsOnlyWiredExternalDisplays() {
        assertTrue(isExternalDisplayType(2))
        assertTrue(isExternalDisplayType(6))
        assertFalse(isExternalDisplayType(1))
        assertFalse(isExternalDisplayType(3))
    }

    @Test
    fun `display override follows stable target identity`() {
        assertTrue(displayOverrideMatchesTarget(2, "hdmi-a", 2, "hdmi-a"))
        assertTrue(!displayOverrideMatchesTarget(2, "hdmi-a", 2, "hdmi-b"))
    }

    @Test
    fun `legacy display override follows its recorded display id`() {
        assertTrue(displayOverrideMatchesTarget(2, null, 2, "hdmi-a"))
        assertTrue(!displayOverrideMatchesTarget(1, null, 2, "hdmi-a"))
    }

    private val recovering = CoverQsTransaction(
        requested = CoverQsMode.ORIGINAL,
        state = CoverQsTransition.RECOVERING
    )

    @Test
    fun recoveringTransactionAcceptsExplicitOriginalRetry() {
        assertTrue(
            shouldRetryDisplayRecovery(
                recovering,
                DisplayRecoveryTrigger.ORIGINAL_REQUEST
            )
        )
    }

    @Test
    fun recoveringTransactionAcceptsTopologyAndKnownFoldRetries() {
        assertTrue(
            shouldRetryDisplayRecovery(
                recovering,
                DisplayRecoveryTrigger.TOPOLOGY_CHANGED
            )
        )
        assertTrue(
            shouldRetryDisplayRecovery(
                recovering,
                DisplayRecoveryTrigger.FOLD_CHANGED,
                FoldState.CLOSED
            )
        )
    }

    @Test
    fun stableOrUnknownFoldStateDoesNotStartRecovery() {
        assertFalse(
            shouldRetryDisplayRecovery(
                CoverQsTransaction(),
                DisplayRecoveryTrigger.ORIGINAL_REQUEST
            )
        )
        assertFalse(
            shouldRetryDisplayRecovery(
                recovering,
                DisplayRecoveryTrigger.FOLD_CHANGED,
                FoldState.UNKNOWN
            )
        )
    }

    @Test
    fun topologyRetryWaitsForFailedRoundCooldown() {
        assertFalse(
            shouldRetryDisplayRecovery(
                recovering,
                DisplayRecoveryTrigger.TOPOLOGY_CHANGED,
                nowUptimeMillis = 4_999L,
                topologyRetryAfterUptimeMillis = 5_000L
            )
        )
        assertTrue(
            shouldRetryDisplayRecovery(
                recovering,
                DisplayRecoveryTrigger.TOPOLOGY_CHANGED,
                nowUptimeMillis = 5_000L,
                topologyRetryAfterUptimeMillis = 5_000L
            )
        )
    }

    @Test
    fun additionalExternalOrVirtualDisplaysDoNotInvalidateCoreTopology() {
        assertTrue(
            hasRequiredBuiltInDisplays(
                listOf(0 to 1, 1 to 1, 2 to 2, 3 to 5)
            )
        )
        assertFalse(hasRequiredBuiltInDisplays(listOf(0 to 1, 2 to 1)))
        assertFalse(hasRequiredBuiltInDisplays(listOf(0 to 1, 1 to 2)))
    }

    @Test
    fun closedDeviceStatesSwapDisplayLayout() {
        assertTrue(shouldSwapDisplayLayout(0))
        assertTrue(shouldSwapDisplayLayout(1))
        assertFalse(shouldSwapDisplayLayout(2))
        assertFalse(shouldSwapDisplayLayout(3))
    }

    @Test
    fun displayZeroIsAUsableCoverId() {
        assertTrue(isUsableDisplayId(0))
        assertTrue(isUsableDisplayId(1))
        assertFalse(isUsableDisplayId(-1))
    }

    @Test
    fun uiRestartRequiresANewRunningPid() {
        assertTrue(hasUiProcessRestarted(100, 101))
        assertTrue(hasUiProcessRestarted(null, 101))
        assertFalse(hasUiProcessRestarted(100, 100))
        assertFalse(hasUiProcessRestarted(100, null))
    }

    @Test
    fun originalRestoreNeverKeepsTheFullCanvasOnDisplayZero() {
        val staleInner = DisplayMetricsSnapshot(
            0,
            "inner",
            1080,
            2640,
            480,
            1080,
            1080,
            510
        )
        val customizedCover = DisplayMetricsSnapshot(
            1,
            "cover",
            748,
            720,
            340,
            1122,
            1080,
            510
        )

        assertEquals(CoverDisplayOverride(1080, 2640, 480), restoredDisplayMetrics(staleInner))
        assertEquals(CoverDisplayOverride(1122, 1080, 510), restoredDisplayMetrics(customizedCover))
    }
}
