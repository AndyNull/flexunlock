package com.flexunlock.dexlsp.system.runtime

import com.flexunlock.dexlsp.system.session.FoldState

object RuntimeFacts {
    @Volatile
    var foldState: FoldState = FoldState.UNKNOWN
        private set

    @Synchronized
    fun updateFoldState(state: FoldState): Boolean {
        if (foldState == state) return false
        foldState = state
        return true
    }

    fun isClosed(): Boolean = foldState == FoldState.CLOSED

    fun isTargetSessionEligible(): Boolean =
        targetSessionEligible(foldState, com.flexunlock.dexlsp.CoverDisplayResolver.current()?.type)

    fun sessionFoldState(state: FoldState): FoldState =
        effectiveSessionFoldState(state, com.flexunlock.dexlsp.CoverDisplayResolver.current()?.type)

    fun isTargetSecurityRestricted(): Boolean = targetSecurityRestricted(
        com.flexunlock.dexlsp.CoverDisplayResolver.current()?.type,
        NativeSecondaryHomeRouter.isCoverKeyguardShowing(),
        CoverLockTransitionPolicy.isAccessRestricted()
    )
}

internal fun targetSessionEligible(foldState: FoldState, targetType: Int?): Boolean =
    when (targetType) {
        DISPLAY_TYPE_BUILT_IN -> foldState == FoldState.CLOSED
        DISPLAY_TYPE_HDMI, DISPLAY_TYPE_VIRTUAL, DISPLAY_TYPE_DISPLAY_PORT -> true
        else -> false
    }

internal fun effectiveSessionFoldState(foldState: FoldState, targetType: Int?): FoldState =
    if (targetType == DISPLAY_TYPE_HDMI ||
        targetType == DISPLAY_TYPE_VIRTUAL ||
        targetType == DISPLAY_TYPE_DISPLAY_PORT
    ) {
        FoldState.CLOSED
    } else {
        foldState
    }

internal fun targetSecurityRestricted(
    targetType: Int?,
    keyguardShowing: Boolean,
    accessRestricted: Boolean
): Boolean = targetType == DISPLAY_TYPE_BUILT_IN && (keyguardShowing || accessRestricted)

private const val DISPLAY_TYPE_BUILT_IN = 1
private const val DISPLAY_TYPE_HDMI = 2
private const val DISPLAY_TYPE_VIRTUAL = 5
private const val DISPLAY_TYPE_DISPLAY_PORT = 6
