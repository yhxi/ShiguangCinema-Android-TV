package com.lshai.cloudscreen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChildrenCatalogTest {
    @Test fun catalogContainsOneHundredDistinctTitlesInThreeSections() {
        val sections = ChildrenCatalog.sections
        assertEquals(3, sections.size)
        val titles = sections.flatMap { it.titles }
        assertEquals(100, titles.size)
        assertEquals(titles.size, titles.distinct().size)
        assertTrue(sections.all { it.titles.size >= 3 && it.titles.none(String::isBlank) })
    }

    @Test fun preloadPrioritizesVisibleCardsAndIncludesEveryTitle() {
        val order = ChildrenCatalog.preloadOrder()
        assertEquals(100, order.size)
        assertEquals(ChildrenCatalog.sections.flatMap { it.titles.take(3) }, order.take(9))
        assertEquals(ChildrenCatalog.sections.flatMap { it.titles }.toSet(), order.toSet())
    }

    @Test fun replacesLegacyLiveTileWithoutDisturbingOtherOrder() {
        assertEquals(listOf("tv", "kids", "search", "setup"),
            migrateHomeOrder(listOf("tv", "live", "search", "setup")))
        assertEquals(listOf("kids", "tv"), migrateHomeOrder(listOf("kids", "live", "tv")))
    }
}
