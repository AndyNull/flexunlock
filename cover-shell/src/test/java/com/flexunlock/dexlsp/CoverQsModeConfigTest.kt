package com.flexunlock.dexlsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverQsModeConfigTest {
    @Test
    fun transactionRoundTripsDisplaySnapshots() {
        val transaction = CoverQsTransaction(
            requested = CoverQsMode.FULL,
            applied = CoverQsMode.ORIGINAL,
            state = CoverQsTransition.ENABLING,
            snapshots = listOf(
                DisplayMetricsSnapshot(0, "local:0|内屏", 1080, 2640, 480, 1080, 2640, 480),
                DisplayMetricsSnapshot(1, "local:1", 748, 720, 340, 1122, 1080, 340)
            )
        )

        assertEquals(transaction, CoverQsModeConfig.decode(CoverQsModeConfig.encode(transaction)))
    }

    @Test
    fun malformedTransactionIsRejected() {
        assertNull(CoverQsModeConfig.decode("v1|FULL|9|1"))
        assertNull(CoverQsModeConfig.decode("v1|FULL|1|1\ndisplay|0|bad"))
    }

    @Test
    fun stableStateRequiresMatchingAppliedMode() {
        assertTrue(CoverQsTransaction().isStableOriginal)
        assertFalse(CoverQsTransaction(state = CoverQsTransition.ENABLING).isStableOriginal)
        assertTrue(
            CoverQsTransaction(
                requested = CoverQsMode.FULL,
                applied = CoverQsMode.FULL,
                state = CoverQsTransition.FULL
            ).isStableFull
        )
    }

    @Test
    fun fullTransactionFollowsPhysicalCoverAfterDisplaySwap() {
        val transaction = CoverQsTransaction(
            requested = CoverQsMode.FULL,
            applied = CoverQsMode.FULL,
            state = CoverQsTransition.FULL,
            snapshots = listOf(
                DisplayMetricsSnapshot(0, "local:inner", 1080, 2640, 480, 1080, 2640, 480),
                DisplayMetricsSnapshot(1, "local:cover", 748, 720, 340, 1122, 1080, 510)
            )
        )
        val displays = listOf(
            DisplayCandidate(0, 1079, 1080, uniqueId = "local:cover"),
            DisplayCandidate(1, 1080, 2640, uniqueId = "local:inner")
        )

        assertEquals(0, transactionalCoverDisplay(displays, transaction)?.id)
    }
}
