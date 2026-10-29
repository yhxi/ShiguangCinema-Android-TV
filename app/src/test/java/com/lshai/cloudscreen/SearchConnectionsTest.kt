package com.lshai.cloudscreen

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchConnectionsTest {
    private class Connection : HttpURLConnection(URL("http://example.test/")) {
        var closed = false
        override fun disconnect() { closed = true }
        override fun usingProxy() = false
        override fun connect() = Unit
    }

    @Test fun scopeClosesItsOwnConnections() {
        val first = SearchConnections()
        val second = SearchConnections()
        val a = Connection()
        val b = Connection()
        first.run { SearchConnections.registerCurrent(a) }
        assertTrue(a.closed)
        second.run {
            SearchConnections.registerCurrent(b)
            first.cancel()
            assertFalse(b.closed)
        }
        assertTrue(b.closed)
    }

    @Test(expected = IOException::class) fun cancelledScopeRejectsNewConnections() {
        val group = SearchConnections()
        group.cancel()
        group.run { SearchConnections.registerCurrent(Connection()) }
    }
}
