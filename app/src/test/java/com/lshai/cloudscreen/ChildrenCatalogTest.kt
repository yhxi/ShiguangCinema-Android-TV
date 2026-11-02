package com.lshai.cloudscreen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChildrenCatalogTest {
    @Test fun catalogContainsFiftyDistinctTitlesInThreeSections() {
        val sections = ChildrenCatalog.sections
        assertEquals(3, sections.size)
        val titles = sections.flatMap { it.titles }
        assertEquals(50, titles.size)
        assertEquals(titles.size, titles.distinct().size)
        assertTrue(sections.all { it.titles.size >= 3 && it.titles.none(String::isBlank) })
    }

    @Test fun replacesLegacyLiveTileWithoutDisturbingOtherOrder() {
        assertEquals(listOf("tv", "kids", "search", "setup"),
            migrateHomeOrder(listOf("tv", "live", "search", "setup")))
        assertEquals(listOf("kids", "tv"), migrateHomeOrder(listOf("kids", "live", "tv")))
    }
}
