package com.flexunlock.dexlsp.system.session

enum class FoldState {
    UNKNOWN,
    OPENED,
    CLOSED
}

enum class DisplayState {
    ABSENT,
    PRESENT,
    READY
}

enum class DexState {
    OFF,
    STARTING,
    ON,
    STOPPING
}

enum class ShellState {
    ABSENT,
    STARTING,
    ACTIVE,
    STOPPING
}

data class CoverSession(
    val fold: FoldState = FoldState.UNKNOWN,
    val display: DisplayState = DisplayState.ABSENT,
    val dex: DexState = DexState.OFF,
    val shell: ShellState = ShellState.ABSENT,
    val enabled: Boolean = true,
    val generation: Long = 0L,
    val launchAttempts: Int = 0
)

sealed interface CoverEvent {
    data class FoldObserved(val state: FoldState) : CoverEvent
    data class DisplayObserved(val state: DisplayState) : CoverEvent
    data class DisplayIdentityChanged(val state: DisplayState) : CoverEvent
    data class DexObserved(val state: DexState) : CoverEvent
    data class ShellObserved(val state: ShellState) : CoverEvent
    data class EnabledChanged(val enabled: Boolean) : CoverEvent
    data object HomeModeChanged : CoverEvent
}

sealed interface CoverEffect {
    data object EnsureDexStarted : CoverEffect
    data object EnsureDexStopped : CoverEffect
    data class StartCoverHome(val generation: Long) : CoverEffect
    data class StopCoverHome(val generation: Long) : CoverEffect
    data object StopSamsungDisplayOneShell : CoverEffect
}

data class CoverTransition(
    val state: CoverSession,
    val effects: List<CoverEffect>
)

fun reduce(state: CoverSession, event: CoverEvent): CoverTransition {
    val observed = when (event) {
        is CoverEvent.FoldObserved -> if (event.state == FoldState.UNKNOWN) {
            state
        } else {
            state.copy(
                fold = event.state,
                enabled = if (
                    event.state == FoldState.CLOSED && state.fold != FoldState.CLOSED
                ) {
                    true
                } else {
                    state.enabled
                },
                launchAttempts = if (
                    event.state == FoldState.CLOSED && state.fold != FoldState.CLOSED
                ) {
                    0
                } else {
                    state.launchAttempts
                }
            )
        }
        is CoverEvent.DisplayObserved -> state.copy(
            display = event.state,
            // A cover power cycle is a new native-Home recovery boundary. The
            // display can wake with SubHomeActivity on top while the previous
            // SecondaryLauncher task remains stopped.
            launchAttempts = if (
                event.state == DisplayState.READY && state.display != DisplayState.READY
            ) {
                0
            } else {
                state.launchAttempts
            }
        )
        is CoverEvent.DisplayIdentityChanged -> state.copy(
            display = event.state,
            shell = ShellState.ABSENT,
            launchAttempts = 0
        )
        is CoverEvent.DexObserved -> state.copy(dex = event.state)
        is CoverEvent.ShellObserved -> state.copy(shell = event.state)
        is CoverEvent.EnabledChanged -> state.copy(
            enabled = event.enabled,
            launchAttempts = if (event.enabled) 0 else state.launchAttempts
        )
        CoverEvent.HomeModeChanged -> state.copy(
            shell = ShellState.ABSENT,
            launchAttempts = 0
        )
    }

    val enteredOpened = event is CoverEvent.FoldObserved &&
        event.state == FoldState.OPENED &&
        state.fold != FoldState.OPENED
    return reconcile(observed, enteredOpened)
}

private fun reconcile(
    state: CoverSession,
    enteredOpened: Boolean = false
): CoverTransition {
    if (state.fold != FoldState.CLOSED || !state.enabled) {
        val effects = buildList {
            if (state.shell == ShellState.ACTIVE || state.shell == ShellState.STARTING) {
                add(CoverEffect.StopCoverHome(state.generation))
            }
            if (enteredOpened) {
                add(CoverEffect.StopSamsungDisplayOneShell)
            }
            if (state.dex == DexState.ON || state.dex == DexState.STARTING) {
                add(CoverEffect.EnsureDexStopped)
            }
        }
        return CoverTransition(
            state.copy(
                dex = if (CoverEffect.EnsureDexStopped in effects) DexState.STOPPING else state.dex,
                shell = if (effects.any { it is CoverEffect.StopCoverHome }) {
                    ShellState.STOPPING
                } else {
                    state.shell
                }
            ),
            effects
        )
    }

    if (state.display == DisplayState.ABSENT) {
        val shouldStopShell = state.shell == ShellState.ACTIVE || state.shell == ShellState.STARTING
        return CoverTransition(
            state.copy(shell = if (shouldStopShell) ShellState.STOPPING else state.shell),
            if (shouldStopShell) {
                listOf(CoverEffect.StopCoverHome(state.generation))
            } else {
                emptyList()
            }
        )
    }

    if (state.dex == DexState.OFF) {
        return CoverTransition(
            state.copy(dex = DexState.STARTING),
            listOf(CoverEffect.EnsureDexStarted)
        )
    }

    if (state.display != DisplayState.READY) {
        return CoverTransition(state, emptyList())
    }

    if (state.dex != DexState.ON || state.shell != ShellState.ABSENT) {
        return CoverTransition(state, emptyList())
    }

    if (state.launchAttempts >= MAX_LAUNCH_ATTEMPTS_PER_CLOSED_EPOCH) {
        return CoverTransition(state, emptyList())
    }

    val nextGeneration = state.generation + 1L
    return CoverTransition(
        state.copy(
            shell = ShellState.STARTING,
            generation = nextGeneration,
            launchAttempts = state.launchAttempts + 1
        ),
        listOf(CoverEffect.StartCoverHome(nextGeneration))
    )
}

private const val MAX_LAUNCH_ATTEMPTS_PER_CLOSED_EPOCH = 2
