package com.flexunlock.dexlsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverDisplaySelectorTest {
    @Test
    fun `only default display is unavailable`() {
        val selection = CoverDisplaySelector.select(listOf(mainDisplay()))

        assertTrue(selection is CoverDisplaySelection.Unavailable)
    }

    @Test
    fun `built in display with id one is selected`() {
        val selection = CoverDisplaySelector.select(
            listOf(mainDisplay(), coverDisplay(id = 1, uniqueId = "local:cover-1"))
        )

        assertEquals(1, resolvedCandidate(selection).id)
    }

    @Test
    fun `virtual id one does not hide physical id two cover`() {
        val selection = CoverDisplaySelector.select(
            listOf(
                mainDisplay(),
                DisplayCandidate(
                    id = 1,
                    width = 1200,
                    height = 700,
                    uniqueId = "virtual:desktop",
                    type = DISPLAY_TYPE_VIRTUAL
                ),
                coverDisplay(id = 2, uniqueId = "local:cover-2")
            )
        )

        assertEquals(2, resolvedCandidate(selection).id)
    }

    @Test
    fun `delayed physical display changes unavailable to resolved`() {
        val before = CoverDisplaySelector.select(listOf(mainDisplay()))
        val after = CoverDisplaySelector.select(
            listOf(mainDisplay(), coverDisplay(id = 4, uniqueId = "local:cover-delayed"))
        )

        assertTrue(before is CoverDisplaySelection.Unavailable)
        assertEquals(4, resolvedCandidate(after).id)
    }

    @Test
    fun `removed cover rebuilt with new id is selected by stable unique id`() {
        val oldCandidate = coverDisplay(id = 1, uniqueId = "local:cover-stable")
        val beforeRemoval = CoverDisplaySelector.select(listOf(mainDisplay(), oldCandidate))
        val removed = CoverDisplaySelector.select(
            displays = listOf(mainDisplay()),
            previous = resolvedCandidate(beforeRemoval)
        )
        val rebuilt = CoverDisplaySelector.select(
            displays = listOf(
                mainDisplay(),
                coverDisplay(id = 7, uniqueId = "local:cover-stable")
            ),
            previous = oldCandidate
        )

        assertTrue(removed is CoverDisplaySelection.Unavailable)
        assertEquals(7, resolvedCandidate(rebuilt).id)
    }

    @Test
    fun `previous physical display survives transient default metrics`() {
        val previous = coverDisplay(id = 1, uniqueId = "local:cover-stable")
        val transientMain = mainDisplay().copy(width = 748, height = 720)
        val selection = CoverDisplaySelector.select(
            displays = listOf(transientMain, previous),
            previous = previous
        )

        assertEquals(1, resolvedCandidate(selection).id)
    }

    @Test
    fun `manual cover survives logical resolution larger than remapped display zero`() {
        val transientMain = mainDisplay().copy(width = 748, height = 720)
        val resizedCover = coverDisplay(id = 1, uniqueId = "local:cover-stable").copy(
            width = 1080,
            height = 1040
        )
        val selection = CoverDisplaySelector.select(
            displays = listOf(transientMain, resizedCover),
            manualIdentity = DisplayStableIdentity(uniqueId = "local:cover-stable")
        )

        assertEquals(1, resolvedCandidate(selection).id)
    }

    @Test
    fun `same sized default metrics keep roles anchored by display zero`() {
        val transientMain = mainDisplay().copy(width = 748, height = 720)
        val selection = CoverDisplaySelector.select(
            displays = listOf(
                transientMain,
                coverDisplay(id = 1, uniqueId = "local:cover-unknown")
            )
        )

        assertEquals(1, resolvedCandidate(selection).id)
    }

    @Test
    fun `equally credible physical displays remain ambiguous`() {
        val selection = CoverDisplaySelector.select(
            listOf(
                mainDisplay(),
                coverDisplay(id = 2, uniqueId = "local:panel-a"),
                coverDisplay(id = 3, uniqueId = "local:panel-b")
            )
        )

        assertTrue(selection is CoverDisplaySelection.Ambiguous)
        assertEquals(
            setOf(2, 3),
            (selection as CoverDisplaySelection.Ambiguous).candidates.map { it.id }.toSet()
        )
    }

    @Test
    fun `previous physical display disambiguates multiple candidates`() {
        val previous = coverDisplay(id = 3, uniqueId = "local:panel-b")
        val selection = CoverDisplaySelector.select(
            displays = listOf(
                mainDisplay(),
                coverDisplay(id = 2, uniqueId = "local:panel-a"),
                previous
            ),
            previous = previous
        )

        assertEquals(3, resolvedCandidate(selection).id)
    }

    @Test
    fun `wired displays do not become automatic cover`() {
        val selection = CoverDisplaySelector.select(
            listOf(
                mainDisplay(),
                DisplayCandidate(
                    id = 4,
                    width = 1920,
                    height = 1080,
                    uniqueId = "local:hdmi",
                    type = DISPLAY_TYPE_HDMI,
                    flags = TRUSTED_COVER_FLAGS
                ),
                DisplayCandidate(
                    id = 5,
                    width = 1280,
                    height = 720,
                    uniqueId = "network:presentation",
                    type = DISPLAY_TYPE_WIFI
                ),
                DisplayCandidate(
                    id = 6,
                    width = 900,
                    height = 600,
                    uniqueId = "overlay:test",
                    type = DISPLAY_TYPE_OVERLAY
                )
            )
        )

        assertTrue(selection is CoverDisplaySelection.Unavailable)
    }

    @Test
    fun `trusted wired displays are available for manual selection`() {
        val hdmi = wiredDisplay(4, "local:hdmi", DISPLAY_TYPE_HDMI)
        val displayPort = wiredDisplay(5, "local:display-port", DISPLAY_TYPE_DISPLAY_PORT)

        assertEquals(
            listOf(4, 5),
            CoverDisplaySelector.selectableCandidates(
                listOf(mainDisplay(), hdmi, displayPort)
            ).map { it.id }
        )
    }

    @Test
    fun `manual identity selects trusted wired display`() {
        val hdmi = wiredDisplay(4, "local:hdmi", DISPLAY_TYPE_HDMI)
        val selection = CoverDisplaySelector.select(
            displays = listOf(mainDisplay(), hdmi),
            manualIdentity = hdmi.stableIdentity
        )

        val resolved = selection as CoverDisplaySelection.Resolved
        assertEquals(4, resolved.candidate.id)
        assertEquals(CoverDisplaySelectionSource.MANUAL, resolved.source)
    }

    @Test
    fun `manual wired display remains selected during full qs transaction`() {
        val hdmi = wiredDisplay(4, "local:hdmi", DISPLAY_TYPE_HDMI)
        val transaction = CoverQsTransaction(
            requested = CoverQsMode.FULL,
            applied = CoverQsMode.FULL,
            state = CoverQsTransition.FULL,
            snapshots = listOf(
                DisplayMetricsSnapshot(1, "local:cover", 748, 720, 340, 748, 720, 340)
            )
        )

        assertEquals(
            4,
            selectedDisplayDuringQsTransition(
                listOf(mainDisplay(), coverDisplay(1, "local:cover"), hdmi),
                transaction,
                hdmi.stableIdentity
            )?.id
        )
    }

    @Test
    fun `wired display is not retained after restoring automatic mode`() {
        val hdmi = wiredDisplay(4, "local:hdmi", DISPLAY_TYPE_HDMI)
        val selection = CoverDisplaySelector.select(
            displays = listOf(mainDisplay(), hdmi),
            previous = hdmi
        )

        assertTrue(selection is CoverDisplaySelection.Unavailable)
    }

    @Test
    fun `untrusted wired display is not selectable`() {
        val hdmi = wiredDisplay(4, "local:hdmi", DISPLAY_TYPE_HDMI).copy(flags = 0)

        assertTrue(
            CoverDisplaySelector.selectableCandidates(listOf(mainDisplay(), hdmi)).isEmpty()
        )
    }

    @Test
    fun `manual stable identity selects requested trusted candidate`() {
        val selection = CoverDisplaySelector.select(
            displays = listOf(
                mainDisplay(),
                coverDisplay(2, "local:panel-a"),
                coverDisplay(3, "local:panel-b")
            ),
            manualIdentity = DisplayStableIdentity(uniqueId = "local:panel-b")
        )

        val resolved = selection as CoverDisplaySelection.Resolved
        assertEquals(3, resolved.candidate.id)
        assertEquals(CoverDisplaySelectionSource.MANUAL, resolved.source)
    }

    @Test
    fun `missing manual identity falls back to unique automatic candidate`() {
        val selection = CoverDisplaySelector.select(
            displays = listOf(mainDisplay(), coverDisplay(4, "local:auto-cover")),
            manualIdentity = DisplayStableIdentity(uniqueId = "local:missing")
        )

        val resolved = selection as CoverDisplaySelection.Resolved
        assertEquals(4, resolved.candidate.id)
        assertEquals(CoverDisplaySelectionSource.AUTO_FALLBACK, resolved.source)
    }

    @Test
    fun `manual unique identity rebinds after display id changes`() {
        val selection = CoverDisplaySelector.select(
            displays = listOf(mainDisplay(), coverDisplay(9, "local:stable-cover")),
            manualIdentity = DisplayStableIdentity(uniqueId = "local:stable-cover")
        )

        assertEquals(9, resolvedCandidate(selection).id)
    }

    @Test
    fun `manual identity rejects display zero and ordinary virtual display`() {
        val virtual = DisplayCandidate(
            id = 8,
            width = 748,
            height = 720,
            uniqueId = "virtual:cover",
            type = DISPLAY_TYPE_VIRTUAL,
            flags = TRUSTED_COVER_FLAGS
        )
        val defaultSelection = CoverDisplaySelector.select(
            displays = listOf(mainDisplay(), virtual),
            manualIdentity = DisplayStableIdentity(uniqueId = "local:main")
        )
        val virtualSelection = CoverDisplaySelector.select(
            displays = listOf(mainDisplay(), virtual),
            manualIdentity = DisplayStableIdentity(uniqueId = "virtual:cover")
        )

        assertTrue(defaultSelection is CoverDisplaySelection.Unavailable)
        assertTrue(virtualSelection is CoverDisplaySelection.Unavailable)
    }

    @Test
    fun `trusted scrcpy desktop is available for manual testing`() {
        val virtual = DisplayCandidate(
            id = 8,
            width = 1920,
            height = 1080,
            uniqueId = "virtual:com.android.shell,2000,scrcpy,1",
            type = DISPLAY_TYPE_VIRTUAL,
            flags = TRUSTED_SCRCPY_FLAGS
        )

        val selection = CoverDisplaySelector.select(
            displays = listOf(mainDisplay(), virtual),
            manualIdentity = virtual.stableIdentity
        )

        assertEquals(8, resolvedCandidate(selection).id)
        assertEquals(listOf(8), CoverDisplaySelector.selectableCandidates(
            listOf(mainDisplay(), virtual)
        ).map { it.id })
    }

    @Test
    fun `duplicate manual stable identity remains ambiguous`() {
        val selection = CoverDisplaySelector.select(
            displays = listOf(
                mainDisplay(),
                coverDisplay(2, "local:duplicate"),
                coverDisplay(3, "local:duplicate")
            ),
            manualIdentity = DisplayStableIdentity(uniqueId = "local:duplicate")
        )

        assertTrue(selection is CoverDisplaySelection.Ambiguous)
    }

    @Test
    fun `built in secondary without trusted decoration flags is rejected`() {
        val selection = CoverDisplaySelector.select(
            displays = listOf(
                mainDisplay(),
                DisplayCandidate(
                    id = 2,
                    width = 748,
                    height = 720,
                    uniqueId = "local:untrusted",
                    type = DISPLAY_TYPE_BUILT_IN
                )
            )
        )

        assertTrue(selection is CoverDisplaySelection.Unavailable)
    }

    private fun resolvedCandidate(selection: CoverDisplaySelection): DisplayCandidate {
        assertTrue(selection is CoverDisplaySelection.Resolved)
        return (selection as CoverDisplaySelection.Resolved).candidate
    }

    private fun mainDisplay() = DisplayCandidate(
        id = 0,
        width = 2208,
        height = 1840,
        uniqueId = "local:main",
        type = DISPLAY_TYPE_BUILT_IN
    )

    private fun coverDisplay(id: Int, uniqueId: String) = DisplayCandidate(
        id = id,
        width = 748,
        height = 720,
        uniqueId = uniqueId,
        flags = TRUSTED_COVER_FLAGS,
        type = DISPLAY_TYPE_BUILT_IN
    )

    private fun wiredDisplay(id: Int, uniqueId: String, type: Int) = DisplayCandidate(
        id = id,
        width = 3840,
        height = 2160,
        uniqueId = uniqueId,
        flags = 1 shl 7,
        type = type,
        displayGroupId = 2
    )

    private companion object {
        const val DISPLAY_TYPE_BUILT_IN = 1
        const val DISPLAY_TYPE_HDMI = 2
        const val DISPLAY_TYPE_WIFI = 3
        const val DISPLAY_TYPE_OVERLAY = 4
        const val DISPLAY_TYPE_VIRTUAL = 5
        const val DISPLAY_TYPE_DISPLAY_PORT = 6
        const val TRUSTED_COVER_FLAGS = (1 shl 7) or (1 shl 6)
        const val TRUSTED_SCRCPY_FLAGS = TRUSTED_COVER_FLAGS or
            (1 shl 8) or (1 shl 9) or (1 shl 11)
    }
}
