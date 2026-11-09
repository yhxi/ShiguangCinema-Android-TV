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

    @Test fun builtInVariantsShareQuotaAcrossEntryPoints() {
        listOf("小猪佩奇 第一季", "小猪佩奇第二季番外篇", "小猪佩奇（番外篇）", "小猪佩奇 第三季 特别篇 国语版", "小猪佩奇大电影").forEach {
            assertTrue(it, matchesChildQuotaTitle(it, catalog))
        }
        assertFalse(matchesChildQuotaTitle("解说小猪佩奇", catalog))
        assertFalse(matchesChildQuotaTitle("小猪佩奇幕后访谈", catalog))
        assertFalse(matchesChildQuotaTitle("普通电视剧第二季番外篇", catalog))
    }

    @Test fun manualTitlesCoverFutureSeasonsWithoutMatchingUnrelatedTitles() {
        val saved = setOf(childQuotaKey("自选动画 第二季"))
        assertTrue(matchesChildQuotaTitle("自选动画 第三季 中文版", saved))
        assertTrue(matchesChildQuotaTitle("自选动画大电影", saved))
        assertFalse(matchesChildQuotaTitle("自选动画幕后访谈", saved))
        assertFalse(matchesChildQuotaTitle("", saved))
        assertFalse(matchesChildQuotaTitle("普通电视剧 第一季", catalog))
    }
}
