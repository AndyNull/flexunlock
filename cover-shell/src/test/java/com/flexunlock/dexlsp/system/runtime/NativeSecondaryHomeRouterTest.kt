package com.flexunlock.dexlsp.system.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeSecondaryHomeRouterTest {
    @Test
    fun acceptsOnlySamsungSecondaryLauncherAsTargetHome() {
        val secondary = NativeSecondaryHomeRouter.CoverHomeOwner.SECONDARY_LAUNCHER
        val systemHome = NativeSecondaryHomeRouter.CoverHomeOwner.OTHER
        val subHome = NativeSecondaryHomeRouter.CoverHomeOwner.SYSTEM_UI_SUB_HOME

        assertTrue(isTargetHomeActive(true, false, secondary))
        assertFalse(isTargetHomeActive(true, false, systemHome))
        assertTrue(isTargetHomeActive(false, false, secondary))
        assertFalse(isTargetHomeActive(false, false, systemHome))
        assertTrue(isTargetHomeActive(false, true, secondary))
        assertFalse(isTargetHomeActive(false, true, systemHome))
        assertFalse(isTargetHomeActive(false, false, subHome))
        assertFalse(isTargetHomeActive(
            false,
            false,
            NativeSecondaryHomeRouter.CoverHomeOwner.NONE
        ))
    }

    @Test
    fun modeChangeRemovesOnlyTargetNonKeyguardHome() {
        assertTrue(restartableTargetHome(1, 1, 2, NativeSecondaryHomeRouter.CoverHomeOwner.SECONDARY_LAUNCHER))
        assertTrue(restartableTargetHome(2, 2, 2, NativeSecondaryHomeRouter.CoverHomeOwner.OTHER))
        assertFalse(restartableTargetHome(1, 0, 2, NativeSecondaryHomeRouter.CoverHomeOwner.SECONDARY_LAUNCHER))
        assertFalse(restartableTargetHome(1, 1, 1, NativeSecondaryHomeRouter.CoverHomeOwner.OTHER))
        assertFalse(restartableTargetHome(1, 1, 2, NativeSecondaryHomeRouter.CoverHomeOwner.NONE))
        assertFalse(restartableTargetHome(1, 1, 2, NativeSecondaryHomeRouter.CoverHomeOwner.SYSTEM_UI_SUB_HOME))
    }

    @Test
    fun externalHomeKeepsNewestTaskAndRemovesDuplicates() {
        assertEquals(listOf(2061, 2064, 2068, 2069), duplicateSecondaryHomeTaskIds(
            listOf(2061, 2064, 2068, 2069, 2070)
        ))
        assertTrue(duplicateSecondaryHomeTaskIds(listOf(2070)).isEmpty())
        assertTrue(duplicateSecondaryHomeTaskIds(emptyList()).isEmpty())
    }

    @Test
    fun externalAppKeepsNewestTaskPerComponent() {
        assertEquals(
            listOf(11, 22),
            duplicateExternalTaskIds(
                listOf(
                    ExternalTaskSnapshot(11, "gallery/.Gallery", 100),
                    ExternalTaskSnapshot(12, "gallery/.Gallery", 200),
                    ExternalTaskSnapshot(21, "messages/.Messages", 300),
                    ExternalTaskSnapshot(22, "messages/.Messages", 250)
                )
            )
        )
    }

    @Test
    fun suppressesOnlyInactiveInnerDisplayInStableFullQs() {
        assertTrue(suppressInactiveInnerSecondaryHome(true, 0, 1))
        assertFalse(suppressInactiveInnerSecondaryHome(true, null, 1))
        assertFalse(suppressInactiveInnerSecondaryHome(false, 0, 1))
        assertFalse(suppressInactiveInnerSecondaryHome(true, 1, 1))
        assertFalse(suppressInactiveInnerSecondaryHome(true, 0, 0))
    }

    @Test
    fun trustedKeyguardAppLaunchOwnsOnlyItsDismissTransition() {
        assertTrue(isTrustedKeyguardAppLaunchTransition(7L, 8L, 100L, 100L))
        assertFalse(isTrustedKeyguardAppLaunchTransition(null, 8L, 100L, 50L))
        assertFalse(isTrustedKeyguardAppLaunchTransition(7L, 7L, 100L, 50L))
        assertFalse(isTrustedKeyguardAppLaunchTransition(7L, 9L, 100L, 50L))
        assertFalse(isTrustedKeyguardAppLaunchTransition(7L, 8L, 100L, 101L))
    }

    @Test
    fun trustedKeyguardAppLaunchDelayExpiresAtDeadline() {
        assertEquals(50L, trustedKeyguardAppLaunchRemainingMillis(100L, 50L))
        assertEquals(0L, trustedKeyguardAppLaunchRemainingMillis(100L, 100L))
        assertEquals(0L, trustedKeyguardAppLaunchRemainingMillis(100L, 101L))
        assertEquals(0L, trustedKeyguardAppLaunchRemainingMillis(null, 50L))
    }

    @Test
    fun trustedKeyguardAppLaunchAllowsAliasRedirectOnlyInsideTargetPackage() {
        assertTrue(isTrustedKeyguardAppPackage("com.example", "com.example"))
        assertFalse(isTrustedKeyguardAppPackage("com.example", "com.other"))
        assertFalse(isTrustedKeyguardAppPackage(null, "com.example"))
    }

    @Test
    fun widgetAuthenticationOwnsOnlyTheFollowingDismissTransition() {
        assertTrue(isCoverWidgetAuthenticationTransition(11L, 12L, 200L, 200L))
        assertFalse(isCoverWidgetAuthenticationTransition(null, 12L, 200L, 100L))
        assertFalse(isCoverWidgetAuthenticationTransition(11L, 11L, 200L, 100L))
        assertFalse(isCoverWidgetAuthenticationTransition(11L, 13L, 200L, 100L))
        assertFalse(isCoverWidgetAuthenticationTransition(11L, 12L, 200L, 201L))
    }
}
