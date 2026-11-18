package com.lshai.cloudscreen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SourceSwitchTest {
    private val info = VideoInfo("剧名", "", "", "", "", "", "")
    private val urls = listOf("https://example.com/a.m3u8", "https://example.com/b.m3u8")

    @Test
    fun usesEpisodeLabelsWhenListStartsLateOrHasMissingEpisodes() {
        val detail = Detail(urls, info, listOf("第08集", "EP10"))
        assertEquals(0, matchingSourceEpisode(detail, 8))
        assertEquals(1, matchingSourceEpisode(detail, 10))
        assertNull(matchingSourceEpisode(detail, 1))
        assertNull(matchingSourceEpisode(detail, 9))
    }

    @Test
    fun usesListOrderWhenTheSourceProvidesNoEpisodeNumbers() {
        val detail = Detail(urls, info, listOf("超清", "高清"))
        assertEquals(1, matchingSourceEpisode(detail, 2))
        assertNull(matchingSourceEpisode(detail, 3))
    }

    @Test
    fun skipsAmbiguousDuplicateEpisodeNumbers() {
        val detail = Detail(urls, info, listOf("01", "第1话"))
        assertNull(matchingSourceEpisode(detail, 1))
    }

    @Test
    fun recentlyFailedSourcesWaitBehindOtherSourcesEvenIfPreviouslyPreferred() {
        val now = 1_000_000L
        assertEquals(SourcePriority.PREFERRED, sourcePriority("a", "a", 0L, now))
        assertEquals(SourcePriority.NORMAL, sourcePriority("b", "a", 0L, now))
        assertEquals(SourcePriority.RECENT_FAILURE, sourcePriority("a", "a", now - 5_000L, now))
        assertEquals(SourcePriority.PREFERRED, sourcePriority("a", "a", now - 600_000L, now))
    }
}
