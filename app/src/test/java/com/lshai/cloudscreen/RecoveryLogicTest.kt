package com.lshai.cloudscreen

import org.junit.Assert.*
import org.junit.Test

class RecoveryLogicTest {
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
