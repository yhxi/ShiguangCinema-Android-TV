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

    @Test fun coverCandidatesPreferExactNamesAndIgnoreBadUrls() {
        val entries = listOf(
            "小猪佩奇 第三季" to "https://example.com/season.jpg",
            "小猪佩奇" to "//example.com/exact.jpg",
            "其他动画" to "https://example.com/wrong.jpg",
            "小猪佩奇" to "javascript:bad",
            "小猪佩奇" to "https://example.com/exact.jpg",
        )
        assertEquals(listOf("https://example.com/exact.jpg", "https://example.com/season.jpg"),
            childrenCoverCandidates("小猪佩奇", entries))
    }

    @Test fun coverCandidatesNormalizePunctuationAndDoNotMatchEmptyTitles() {
        assertEquals(listOf("https://example.com/a.jpg"), childrenCoverCandidates("小猪·佩奇",
            listOf("小猪佩奇" to "https://example.com/a.jpg")))
        assertTrue(childrenCoverCandidates("", listOf("任意" to "https://example.com/a.jpg")).isEmpty())
    }

    @Test fun filtersChildrenWithoutChangingCatalogOrder() {
        val titles = listOf("小猪佩奇", "大耳朵图图", "舒克和贝塔")
        assertEquals(titles, filterChildrenTitles(titles, 0, emptySet(), emptySet()))
        assertEquals(listOf("大耳朵图图"), filterChildrenTitles(titles, 1, setOf("大耳朵图图"), emptySet()))
        assertEquals(listOf("小猪佩奇", "舒克和贝塔"), filterChildrenTitles(titles, 2, emptySet(), setOf("舒克和贝塔", "小猪·佩奇")))
        assertTrue(filterChildrenTitles(titles, 1, emptySet(), emptySet()).isEmpty())
    }

    @Test fun replacesLegacyLiveTileWithoutDisturbingOtherOrder() {
        assertEquals(listOf("tv", "kids", "search", "setup"),
            migrateHomeOrder(listOf("tv", "live", "search", "setup")))
        assertEquals(listOf("kids", "tv"), migrateHomeOrder(listOf("kids", "live", "tv")))
    }
}
