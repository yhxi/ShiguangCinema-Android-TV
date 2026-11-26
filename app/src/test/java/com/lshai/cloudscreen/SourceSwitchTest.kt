package com.lshai.cloudscreen

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SourceSwitchTest {
    private val info = VideoInfo("剧名", "", "", "", "", "", "")
    private val urls = listOf("https://example.com/a.m3u8", "https://example.com/b.m3u8")

    @Test
    fun historySingleUrlDoesNotBecomeEpisodeOne() {
        assertEquals(11, playbackEpisodeIndex(0, 0, true, 0, "剧名 · 第 12/24 集"))
        assertEquals(11, playbackEpisodeIndex(0, 0, true, 11, "剧名"))
        assertEquals(-1, playbackEpisodeIndex(0, 0, true, -1, "剧名"))
        assertEquals(11, playbackEpisodeIndex(0, 12, false, -1, "剧名"))
    }

    @Test
    fun separatesRemakesAndAsksWhenMetadataIsMissing() {
        val original = SearchItem("甲", "1", "剧名 第二季", "", "国产剧", "2010年", "", "https://a.example")
        val remake = original.copy(year = "2020")
        assertNotEquals(original.workKey(), remake.workKey())
        assertEquals(WorkMatch.DIFFERENT, workMatch("剧名第2季", "2010", "电视剧", remake))
        assertEquals(WorkMatch.DIFFERENT, workMatch("剧名第2季", "2010", "电视剧", original.copy(typeName = "电影")))
        assertEquals(WorkMatch.UNCERTAIN, workMatch("剧名第2季", "2010", "国产剧", original.copy(year = "")))
        assertEquals(WorkMatch.VERIFIED, workMatch("剧名第2季", "2010", "电视剧", original))
    }

    @Test
    fun childSearchSkipsAdultAndDisabledSources() {
        val regular = SourceConfig("普通", "https://a.example")
        val adult = SourceConfig("成人", "https://b.example", isAdult = true)
        val disabled = SourceConfig("停用", "https://c.example", enabled = false)
        assertEquals(listOf(regular), enabledSearchSources(listOf(regular, adult, disabled, regular), child = true))
        assertEquals(listOf(regular, adult), enabledSearchSources(listOf(regular, adult, disabled), child = false))
    }

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

    @Test
    fun readyBackupDoesNotWaitForAnOpenRegularQueue() = runBlocking {
        val regular = Channel<String>()
        val backup = Channel<String>(1).apply { trySend("ready") }
        assertEquals("ready", withTimeout(1_000L) {
            firstPlayableSource(regular, backup, 20L) { true }
        })
        regular.cancel()
        backup.cancel()
    }

    @Test
    fun failedRegularCandidateAndClosedQueueStillAllowBackup() = runBlocking {
        val regular = Channel<String>(1).apply { trySend("fails"); close() }
        val backup = Channel<String>(1).apply { trySend("plays"); close() }
        val tried = mutableListOf<String>()
        val winner = withTimeout(1_000L) {
            firstPlayableSource(regular, backup, 20L) {
                tried.add(it)
                it == "plays"
            }
        }
        assertEquals(listOf("fails", "plays"), tried)
        assertEquals("plays", winner)
    }
}
