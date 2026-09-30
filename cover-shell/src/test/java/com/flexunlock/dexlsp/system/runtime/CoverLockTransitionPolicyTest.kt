package com.flexunlock.dexlsp.system.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class CoverLockTransitionPolicyTest {
    @Test
    fun sleepRemainsRestrictedUntilKeyguardAppears() {
        val pending = coverLockStateAfterSleep(CoverLockState.UNLOCKED)

        assertEquals(CoverLockState.LOCK_PENDING, pending)
        assertEquals(CoverLockState.LOCK_PENDING, coverLockStateAfterKeyguard(pending, false))
        assertEquals(CoverLockState.LOCKED, coverLockStateAfterKeyguard(pending, true))
    }

    @Test
    fun keyguardDismissReleasesCommittedLock() {
        assertEquals(
            CoverLockState.UNLOCKED,
            coverLockStateAfterKeyguard(CoverLockState.LOCKED, false)
        )
    }

    @Test
    fun repeatedSleepDoesNotDowngradeLockedState() {
        assertEquals(CoverLockState.LOCKED, coverLockStateAfterSleep(CoverLockState.LOCKED))
    }
}
