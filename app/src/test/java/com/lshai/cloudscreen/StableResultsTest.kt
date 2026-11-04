package com.lshai.cloudscreen

import org.junit.Assert.assertEquals
import org.junit.Test

class StableResultsTest {
    @Test fun keepsExistingRowsAndAppendsNewOnesInTheirIncomingOrder() {
        assertEquals(listOf("B", "A", "C", "D"),
            keepExistingResultOrder(listOf("C", "A", "B", "D"), listOf("B", "A")) { it })
    }

    @Test fun doesNotRestoreRowsRemovedByAFilter() {
        assertEquals(listOf("B", "C"),
            keepExistingResultOrder(listOf("C", "B"), listOf("A", "B")) { it })
    }
}
