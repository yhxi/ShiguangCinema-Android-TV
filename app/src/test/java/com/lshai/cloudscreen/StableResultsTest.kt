package com.lshai.cloudscreen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StableResultsTest {
    @Test fun ranksExactTitleBeforeRelatedAndUnrelatedTitles() {
        assertEquals(0, searchTitleRank("神奇校车", "神奇校车"))
        assertEquals(1, searchTitleRank("神奇校车第二季", "神奇校车"))
        assertEquals(2, searchTitleRank("其他动画", "神奇校车"))
    }
    @Test fun keepsExistingRowsAndAppendsNewOnesInTheirIncomingOrder() {
        assertEquals(listOf("B", "A", "C", "D"),
            keepExistingResultOrder(listOf("C", "A", "B", "D"), listOf("B", "A")) { it })
    }

    @Test fun doesNotRestoreRowsRemovedByAFilter() {
        assertEquals(listOf("B", "C"),
            keepExistingResultOrder(listOf("C", "B"), listOf("A", "B")) { it })
    }

    @Test fun touchModeDoesNotRestoreGridSelection() {
        assertNull(searchSelectionToRestore("B", listOf("A", "B"), inTouchMode = true))
    }

    @Test fun dpadSelectionUsesLatestResultsAndIgnoresRemovedRows() {
        assertEquals(1, searchSelectionToRestore("B", listOf("A", "B", "C"), inTouchMode = false))
        assertNull(searchSelectionToRestore("B", listOf("A", "C"), inTouchMode = false))
        assertNull(searchSelectionToRestore(null, listOf("A"), inTouchMode = false))
    }

    @Test fun resumesLastVisitEvenWhenHigherEpisodeWasWatched() {
        assertEquals(1, resumeEpisodeIndex(1, booleanArrayOf(false, true, false, true)))
        assertEquals(3, resumeEpisodeIndex(99, booleanArrayOf(false, true, false, true)))
        assertEquals(-1, resumeEpisodeIndex(null, booleanArrayOf()))
    }
    @Test fun lateExactMatchDoesNotDisplaceExistingResult() {
        val sorted = listOf("动画", "动画第二季").sortedBy { searchTitleRank(it, "动画") }
        assertEquals(listOf("动画第二季", "动画"), keepExistingResultOrder(sorted, listOf("动画第二季")) { it })
    }
}
