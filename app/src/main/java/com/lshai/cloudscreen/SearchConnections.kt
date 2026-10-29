package com.lshai.cloudscreen

import java.io.IOException
import java.net.HttpURLConnection

/** Connections belong to a single search, so cancellation never closes a newer search. */
internal class SearchConnections {
    private val active = mutableSetOf<HttpURLConnection>()
    private var cancelled = false

    fun isCancelled(): Boolean = synchronized(this) { cancelled }

    fun register(conn: HttpURLConnection) = synchronized(this) {
        if (cancelled) throw IOException("Search cancelled")
        active.add(conn)
    }

    fun cancel() {
        val connections = synchronized(this) {
            cancelled = true
            active.toList().also { active.clear() }
        }
        if (connections.isNotEmpty()) Thread({
            connections.forEach { runCatching { it.disconnect() } }
        }, "search-cancel").apply { isDaemon = true; start() }
    }

    fun <T> run(block: () -> T): T {
        current.set(this)
        val connections = mutableListOf<HttpURLConnection>()
        currentConnections.set(connections)
        try { return block() } finally {
            current.remove()
            currentConnections.remove()
            synchronized(this) { active.removeAll(connections.toSet()) }
            connections.forEach { runCatching { it.disconnect() } }
        }
    }

    companion object {
        private val current = ThreadLocal<SearchConnections>()
        private val currentConnections = ThreadLocal<MutableList<HttpURLConnection>>()
        fun registerCurrent(conn: HttpURLConnection) {
            current.get()?.register(conn)
            currentConnections.get()?.add(conn)
        }
        fun currentCancelled(): Boolean = current.get()?.isCancelled() == true
    }
}
