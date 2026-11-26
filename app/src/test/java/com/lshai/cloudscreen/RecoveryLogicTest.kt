package com.lshai.cloudscreen

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class RecoveryLogicTest {
    @Test fun viewingBackupSelectsNewestRecordAndKeepsRemakesSeparate() {
        fun entry(url: String, year: String, position: Long, updated: Long) = JSONObject()
            .put("playUrl", url).put("seriesTitle", "同名作品").put("year", year)
            .put("positionMs", position).put("updatedAt", updated)
        val local = entry("https://a.example/12", "2000", 720_000, 100)
        val imported = entry("https://a.example/12", "2000", 2_520_000, 200)
        val remake = entry("https://a.example/remake", "2020", 90_000, 50)
        assertEquals(listOf(imported, remake), mergeViewingHistory(listOf(local, remake), listOf(imported)))
        assertEquals(2_520_000L, mergeViewingHistory(listOf(local), listOf(imported)).single().getLong("positionMs"))
        assertEquals(listOf(imported), mergeViewingHistory(listOf(imported), listOf(local)))
    }

    @Test fun liveLinePreferenceKeepsEveryFallbackInOriginalOrder() {
        val order = preferredLiveLineOrder(4, 2)
        assertEquals(listOf(2, 0, 1, 3), order)
        assertEquals(0, order[nextLiveLine(0, order.size, online = true)!!])
        assertNull(nextLiveLine(order.lastIndex, order.size, online = true))
        assertEquals(listOf(0, 1, 2, 3), preferredLiveLineOrder(4, -1))
    }

    @Test fun defersCacheRecreationUntilAllPlayersClose() {
        val state = PlaybackCacheState()
        state.opened(); state.opened()
        assertFalse(state.requestRecreate())
        assertFalse(state.closed())
        assertFalse(state.consumeDeferred())
        assertTrue(state.closed())
        // A new player can arrive before asynchronous maintenance runs.
        state.opened()
        assertFalse(state.consumeDeferred())
        assertTrue(state.closed())
        assertTrue(state.consumeDeferred())
        assertFalse(state.consumeDeferred())
        assertTrue(state.requestRecreate())
    }

    @Test fun nextPlayerCanSafelyApplyDeferredCacheChange() {
        val state = PlaybackCacheState()
        state.opened()
        assertFalse(state.requestRecreate())
        assertTrue(state.closed())
        state.opened()
        assertFalse(state.consumeDeferred())
        assertTrue(state.consumeBeforeUse())
        assertFalse(state.consumeBeforeUse())
        assertFalse(state.closed())
    }

    @Test fun unreadableOrMalformedProfilesAreNeverTreatedAsEmpty() {
        assertFalse(serverProfilesReadable("enc:bad", ""))
        assertFalse(serverProfilesReadable("bad", "bad"))
        assertTrue(serverProfilesReadable("enc:valid", "[]"))
        assertTrue(serverProfilesReadable("[]", "[]"))
    }

    @Test fun imageProxyRunsAfterInvalidDirectData() {
        var proxyCalls = 0
        val result = loadImageWithFallback(
            direct = { byteArrayOf(0) }, proxy = { proxyCalls++; byteArrayOf(1) },
            decode = { if (it[0].toInt() == 1) "image" else null },
        )
        assertEquals("image", result)
        assertEquals(1, proxyCalls)
        val first = loadImageWithFallback(direct = { byteArrayOf(1) },
            proxy = { proxyCalls++; byteArrayOf(0) }, decode = { if (it[0].toInt() == 1) "image" else null })
        assertEquals("image", first)
        assertEquals(1, proxyCalls)
    }

    @Test fun failedEmptySearchIsNotCached() {
        assertFalse(shouldCacheSearchResult(0, 2))
        assertFalse(shouldCacheSearchResult(0, 0))
        assertTrue(shouldCacheSearchResult(1, 2))
    }
}
