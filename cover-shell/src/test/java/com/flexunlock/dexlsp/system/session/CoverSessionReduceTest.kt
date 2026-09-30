package com.flexunlock.dexlsp.system.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverSessionReduceTest {
    @Test
    fun unknownFoldLeavesSessionUnchanged() {
        val start = CoverSession(fold = FoldState.OPENED)
        val result = reduce(start, CoverEvent.FoldObserved(FoldState.UNKNOWN))

        assertEquals(start, result.state)
        assertTrue(result.effects.isEmpty())
    }

    @Test
    fun closingEnablesSessionAndStartsDex() {
        val start = CoverSession(
            fold = FoldState.OPENED,
            enabled = false,
            launchAttempts = 2,
            display = DisplayState.READY
        )
        val result = reduce(start, CoverEvent.FoldObserved(FoldState.CLOSED))

        assertEquals(FoldState.CLOSED, result.state.fold)
        assertTrue(result.state.enabled)
        assertEquals(0, result.state.launchAttempts)
        assertEquals(DexState.STARTING, result.state.dex)
        assertEquals(listOf(CoverEffect.EnsureDexStarted), result.effects)
    }

    @Test
    fun displayReadyOnClosedSessionStartsNativeHome() {
        val start = CoverSession(
            fold = FoldState.CLOSED,
            display = DisplayState.PRESENT,
            dex = DexState.ON,
            launchAttempts = 2
        )
        val result = reduce(start, CoverEvent.DisplayObserved(DisplayState.READY))

        assertEquals(DisplayState.READY, result.state.display)
        assertEquals(ShellState.STARTING, result.state.shell)
        assertEquals(1L, result.state.generation)
        assertEquals(1, result.state.launchAttempts)
        assertEquals(
            listOf(CoverEffect.StartCoverHome(1L)),
            result.effects
        )
    }

    @Test
    fun displayIdentityChangeClearsShellThenRelaunches() {
        val start = CoverSession(
            fold = FoldState.CLOSED,
            display = DisplayState.READY,
            dex = DexState.ON,
            shell = ShellState.ACTIVE,
            generation = 3,
            launchAttempts = 1
        )
        val result = reduce(start, CoverEvent.DisplayIdentityChanged(DisplayState.READY))

        assertEquals(ShellState.STARTING, result.state.shell)
        assertEquals(4L, result.state.generation)
        assertEquals(1, result.state.launchAttempts)
        assertEquals(
            listOf(CoverEffect.StartCoverHome(4L)),
            result.effects
        )
    }

    @Test
    fun openingStopsHomeDexAndSamsungShell() {
        val start = CoverSession(
            fold = FoldState.CLOSED,
            dex = DexState.ON,
            shell = ShellState.ACTIVE,
            generation = 5
        )
        val result = reduce(start, CoverEvent.FoldObserved(FoldState.OPENED))

        assertEquals(FoldState.OPENED, result.state.fold)
        assertEquals(ShellState.STOPPING, result.state.shell)
        assertEquals(DexState.STOPPING, result.state.dex)
        assertEquals(
            listOf(
                CoverEffect.StopCoverHome(5),
                CoverEffect.StopSamsungDisplayOneShell,
                CoverEffect.EnsureDexStopped
            ),
            result.effects
        )
    }

    @Test
    fun absentDisplayStopsStartingShell() {
        val start = CoverSession(
            fold = FoldState.CLOSED,
            display = DisplayState.READY,
            dex = DexState.ON,
            shell = ShellState.STARTING,
            generation = 2
        )
        val result = reduce(start, CoverEvent.DisplayObserved(DisplayState.ABSENT))

        assertEquals(DisplayState.ABSENT, result.state.display)
        assertEquals(ShellState.STOPPING, result.state.shell)
        assertEquals(listOf(CoverEffect.StopCoverHome(2)), result.effects)
    }

    @Test
    fun closedSessionWaitsUntilDisplayIsReady() {
        val start = CoverSession(
            fold = FoldState.CLOSED,
            display = DisplayState.PRESENT,
            dex = DexState.ON
        )
        val result = reduce(start, CoverEvent.DexObserved(DexState.ON))

        assertEquals(start, result.state)
        assertTrue(result.effects.isEmpty())
    }

    @Test
    fun launchAttemptsAreCappedPerClosedEpoch() {
        val start = CoverSession(
            fold = FoldState.CLOSED,
            display = DisplayState.READY,
            dex = DexState.ON,
            shell = ShellState.ABSENT,
            launchAttempts = 2,
            generation = 9
        )
        val result = reduce(start, CoverEvent.ShellObserved(ShellState.ABSENT))

        assertEquals(start, result.state)
        assertTrue(result.effects.isEmpty())
    }

    @Test
    fun homeModeChangeForcesFreshHomeLaunch() {
        val start = CoverSession(
            fold = FoldState.CLOSED,
            display = DisplayState.READY,
            dex = DexState.ON,
            shell = ShellState.ACTIVE,
            launchAttempts = 2,
            generation = 9
        )
        val result = reduce(start, CoverEvent.HomeModeChanged)

        assertEquals(ShellState.STARTING, result.state.shell)
        assertEquals(1, result.state.launchAttempts)
        assertEquals(10L, result.state.generation)
        assertEquals(listOf(CoverEffect.StartCoverHome(10)), result.effects)
    }

    @Test
    fun homeModeChangeWhileDisplayIsOffDefersFreshLaunch() {
        val start = CoverSession(
            fold = FoldState.CLOSED,
            display = DisplayState.PRESENT,
            dex = DexState.ON,
            shell = ShellState.ACTIVE,
            launchAttempts = 2,
            generation = 9
        )
        val reset = reduce(start, CoverEvent.HomeModeChanged)
        val ready = reduce(reset.state, CoverEvent.DisplayObserved(DisplayState.READY))

        assertEquals(ShellState.ABSENT, reset.state.shell)
        assertTrue(reset.effects.isEmpty())
        assertEquals(ShellState.STARTING, ready.state.shell)
        assertEquals(listOf(CoverEffect.StartCoverHome(10)), ready.effects)
    }

    @Test
    fun disableStopsHomeAndDexWhileClosed() {
        val start = CoverSession(
            fold = FoldState.CLOSED,
            display = DisplayState.READY,
            dex = DexState.ON,
            shell = ShellState.ACTIVE,
            generation = 4
        )
        val result = reduce(start, CoverEvent.EnabledChanged(false))

        assertEquals(false, result.state.enabled)
        assertEquals(ShellState.STOPPING, result.state.shell)
        assertEquals(DexState.STOPPING, result.state.dex)
        assertEquals(
            listOf(
                CoverEffect.StopCoverHome(4),
                CoverEffect.EnsureDexStopped
            ),
            result.effects
        )
    }

    @Test
    fun reenableWhileStoppingRestartsAfterStopObservations() {
        val active = CoverSession(
            fold = FoldState.CLOSED,
            display = DisplayState.READY,
            dex = DexState.ON,
            shell = ShellState.ACTIVE,
            generation = 4
        )
        val stopping = reduce(active, CoverEvent.EnabledChanged(false)).state
        val reenabled = reduce(stopping, CoverEvent.EnabledChanged(true)).state
        val shellStopped = reduce(reenabled, CoverEvent.ShellObserved(ShellState.ABSENT)).state
        val result = reduce(shellStopped, CoverEvent.DexObserved(DexState.OFF))

        assertTrue(result.state.enabled)
        assertEquals(DexState.STARTING, result.state.dex)
        assertEquals(listOf(CoverEffect.EnsureDexStarted), result.effects)
    }
}
