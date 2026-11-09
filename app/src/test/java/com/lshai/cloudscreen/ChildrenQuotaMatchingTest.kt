package com.lshai.cloudscreen

import org.junit.Assert.*
import org.junit.Test

class ChildrenQuotaMatchingTest {
    private val catalog = ChildrenCatalog.preloadOrder().map(::normalizeSeries).toSet()

    @Test fun recognizesEveryCatalogTitleAndSeasonFromHistory() {
        ChildrenCatalog.preloadOrder().forEach {
            assertTrue(it, matchesChildQuotaTitle(it, catalog))
            assertTrue(it, matchesChildQuotaTitle("$it 第八季（普通话版） · 第 2/52 集", catalog))
        }
    }

    @Test fun manualTitlesCoverFutureSeasonsWithoutMatchingUnrelatedTitles() {
        val saved = setOf(childQuotaKey("自选动画 第二季"))
        assertTrue(matchesChildQuotaTitle("自选动画 第三季 中文版", saved))
        assertFalse(matchesChildQuotaTitle("自选动画大电影", saved))
        assertFalse(matchesChildQuotaTitle("", saved))
        assertFalse(matchesChildQuotaTitle("普通电视剧 第一季", catalog))
    }
}
