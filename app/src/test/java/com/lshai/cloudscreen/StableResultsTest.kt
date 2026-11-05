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
}
