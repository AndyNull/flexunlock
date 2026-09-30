package com.flexunlock.dexlsp

internal data class DisplayStableIdentity(
    val uniqueId: String? = null,
    val physicalAddress: String? = null,
    val port: Int? = null
) {
    fun normalized(): DisplayStableIdentity? {
        val normalizedUniqueId = uniqueId?.trim()?.takeIf(String::isNotEmpty)
        val normalizedAddress = physicalAddress?.trim()?.takeIf(String::isNotEmpty)
        val normalizedPort = port?.takeIf { it >= 0 }
        return DisplayStableIdentity(normalizedUniqueId, normalizedAddress, normalizedPort)
            .takeIf { it.uniqueId != null || it.physicalAddress != null || it.port != null }
    }
}

internal data class DisplayCandidate(
    val id: Int,
    val width: Int,
    val height: Int,
    val rotation: Int = 0,
    val uniqueId: String? = null,
    val name: String? = null,
    val flags: Int = 0,
    val type: Int = 0,
    val displayGroupId: Int = 0,
    val physicalAddress: String? = null,
    val port: Int? = null
) {
    val area: Long = width.toLong() * height.toLong()
    val stableIdentity: DisplayStableIdentity?
        get() = DisplayStableIdentity(uniqueId, physicalAddress, port).normalized()
}

internal enum class CoverDisplaySelectionSource {
    AUTO,
    MANUAL,
    AUTO_FALLBACK
}

internal sealed interface CoverDisplaySelection {
    data class Resolved(
        val candidate: DisplayCandidate,
        val source: CoverDisplaySelectionSource = CoverDisplaySelectionSource.AUTO
    ) : CoverDisplaySelection

    data class Unavailable(val reason: String) : CoverDisplaySelection
    data class Ambiguous(val candidates: List<DisplayCandidate>) : CoverDisplaySelection
}

internal object CoverDisplaySelector {
    private const val DEFAULT_DISPLAY_ID = 0
    private const val DEFAULT_DISPLAY_GROUP = 0
    private const val DISPLAY_TYPE_UNKNOWN = 0
    private const val DISPLAY_TYPE_BUILT_IN = 1
    private const val DISPLAY_TYPE_HDMI = 2
    private const val DISPLAY_TYPE_WIFI = 3
    private const val DISPLAY_TYPE_OVERLAY = 4
    private const val DISPLAY_TYPE_VIRTUAL = 5
    private const val DISPLAY_TYPE_DISPLAY_PORT = 6
    private const val FLAG_TRUSTED = 1 shl 7
    private const val FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS = 1 shl 6
    private const val FLAG_OWN_DISPLAY_GROUP = 1 shl 8
    private const val FLAG_ALWAYS_UNLOCKED = 1 shl 9
    private const val FLAG_OWN_FOCUS = 1 shl 11

    fun select(
        displays: List<DisplayCandidate>,
        previous: DisplayCandidate? = null,
        manualIdentity: DisplayStableIdentity? = null
    ): CoverDisplaySelection {
        val main = displays.firstOrNull { it.id == DEFAULT_DISPLAY_ID }
            ?: return CoverDisplaySelection.Unavailable("default-display-missing")
        if (main.width <= 0 || main.height <= 0) {
            return CoverDisplaySelection.Unavailable("default-display-size-missing")
        }

        // The default display is the role anchor. A same-sized physical secondary panel
        // remains a candidate; dimensions only constrain obviously larger presentations.
        val automatic = trustedCandidates(displays)
        val normalizedManual = manualIdentity?.normalized()
        if (normalizedManual != null) {
            val matches = selectableCandidates(displays).filter { candidate ->
                matchesStableIdentity(candidate, normalizedManual)
            }
            when (matches.size) {
                1 -> return CoverDisplaySelection.Resolved(
                    matches.first(),
                    CoverDisplaySelectionSource.MANUAL
                )
                in 2..Int.MAX_VALUE -> return CoverDisplaySelection.Ambiguous(matches)
            }
        }

        previous?.let { old ->
            val matches = automatic.filter { candidate ->
                when {
                    old.stableIdentity != null ->
                        matchesStableIdentity(candidate, old.stableIdentity!!)
                    else -> candidate.id == old.id
                }
            }
            when (matches.size) {
                1 -> return CoverDisplaySelection.Resolved(
                    matches.first(),
                    if (normalizedManual == null) {
                        CoverDisplaySelectionSource.AUTO
                    } else {
                        CoverDisplaySelectionSource.AUTO_FALLBACK
                    }
                )
                in 2..Int.MAX_VALUE -> return CoverDisplaySelection.Ambiguous(matches)
            }
        }

        val candidates = automatic.filter { it.area <= main.area }
        val ranked = candidates.sortedWith(
            compareByDescending<DisplayCandidate> { coverSignalScore(it, main) }
                .thenByDescending { it.area }
                .thenBy { it.id }
        )
        if (ranked.isEmpty()) {
            return CoverDisplaySelection.Unavailable(
                if (normalizedManual == null) "cover-display-missing" else "manual-missing-auto-unavailable"
            )
        }

        val bestScore = coverSignalScore(ranked.first(), main)
        val strongest = ranked.filter { coverSignalScore(it, main) == bestScore }
        return when (strongest.size) {
            1 -> CoverDisplaySelection.Resolved(
                strongest.first(),
                if (normalizedManual == null) {
                    CoverDisplaySelectionSource.AUTO
                } else {
                    CoverDisplaySelectionSource.AUTO_FALLBACK
                }
            )
            else -> CoverDisplaySelection.Ambiguous(strongest)
        }
    }

    fun selectableCandidates(displays: List<DisplayCandidate>): List<DisplayCandidate> = displays
        .asSequence()
        .filter(::isEligibleManualCandidate)
        .sortedBy { it.id }
        .toList()

    fun trustedCandidates(displays: List<DisplayCandidate>): List<DisplayCandidate> = displays
        .asSequence()
        .filter(::isEligibleCoverCandidate)
        .sortedBy { it.id }
        .toList()

    private fun isEligibleCoverCandidate(candidate: DisplayCandidate): Boolean =
        candidate.id != DEFAULT_DISPLAY_ID &&
            candidate.width > 0 &&
            candidate.height > 0 &&
            candidate.displayGroupId == DEFAULT_DISPLAY_GROUP &&
            candidate.flags and FLAG_TRUSTED != 0 &&
            candidate.flags and FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS != 0 &&
            isLocalPhysicalDisplay(candidate)

    private fun isEligibleManualCandidate(candidate: DisplayCandidate): Boolean =
        candidate.id != DEFAULT_DISPLAY_ID &&
            candidate.width > 0 &&
            candidate.height > 0 &&
            candidate.flags and FLAG_TRUSTED != 0 &&
            candidate.stableIdentity != null &&
            (candidate.type == DISPLAY_TYPE_HDMI ||
                candidate.type == DISPLAY_TYPE_DISPLAY_PORT ||
                isScrcpyDesktop(candidate) ||
                isEligibleCoverCandidate(candidate))

    private fun isScrcpyDesktop(candidate: DisplayCandidate): Boolean =
        candidate.type == DISPLAY_TYPE_VIRTUAL &&
            candidate.uniqueId?.startsWith(
                "virtual:com.android.shell,2000,scrcpy,",
                ignoreCase = true
            ) == true &&
            candidate.flags and FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS != 0 &&
            candidate.flags and FLAG_OWN_DISPLAY_GROUP != 0 &&
            candidate.flags and FLAG_ALWAYS_UNLOCKED != 0 &&
            candidate.flags and FLAG_OWN_FOCUS != 0

    private fun isLocalPhysicalDisplay(candidate: DisplayCandidate): Boolean {
        if (candidate.type in setOf(
                DISPLAY_TYPE_HDMI,
                DISPLAY_TYPE_WIFI,
                DISPLAY_TYPE_OVERLAY,
                DISPLAY_TYPE_VIRTUAL
            )
        ) return false
        val uniqueId = candidate.uniqueId.orEmpty()
        if (uniqueId.startsWith("virtual:", ignoreCase = true)) return false
        if (uniqueId.startsWith("overlay:", ignoreCase = true)) return false
        if (uniqueId.startsWith("network:", ignoreCase = true)) return false
        if (uniqueId.isNotEmpty() && !uniqueId.startsWith("local:", ignoreCase = true)) {
            return candidate.type == DISPLAY_TYPE_BUILT_IN
        }
        return candidate.type == DISPLAY_TYPE_UNKNOWN ||
            candidate.type == DISPLAY_TYPE_BUILT_IN
    }

    private fun matchesStableIdentity(
        candidate: DisplayCandidate,
        identity: DisplayStableIdentity
    ): Boolean = when {
        identity.uniqueId != null -> candidate.uniqueId == identity.uniqueId
        identity.physicalAddress != null -> candidate.physicalAddress == identity.physicalAddress
        identity.port != null -> candidate.port == identity.port
        else -> false
    }

    private fun coverSignalScore(candidate: DisplayCandidate, main: DisplayCandidate): Int {
        var score = 0
        if (candidate.type == DISPLAY_TYPE_BUILT_IN) score += 8
        if (candidate.uniqueId?.startsWith("local:", ignoreCase = true) == true) score += 4
        if (candidate.flags and FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS != 0) score += 2
        if (candidate.width < main.width && candidate.height < main.height) score += 2
        if (candidate.area * 2L < main.area) score += 1
        return score
    }
}
