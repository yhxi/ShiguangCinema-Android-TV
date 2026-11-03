package com.lshai.cloudscreen

/** Called under PlaybackCache's lock. */
internal class PlaybackCacheState {
    var activePlayers = 0
        private set
    var recreatePending = false
        private set

    private var replaceBeforeUse = false

    fun opened() {
        if (activePlayers == 0 && recreatePending) replaceBeforeUse = true
        activePlayers++
    }
    fun closed(): Boolean {
        activePlayers = (activePlayers - 1).coerceAtLeast(0)
        return activePlayers == 0 && recreatePending
    }
    fun requestRecreate(): Boolean {
        if (activePlayers > 0) { recreatePending = true; replaceBeforeUse = false; return false }
        recreatePending = false
        replaceBeforeUse = false
        return true
    }
    fun consumeDeferred(): Boolean {
        if (activePlayers != 0 || !recreatePending) return false
        recreatePending = false
        replaceBeforeUse = false
        return true
    }
    /** A new session began after the last old player closed, but has not taken the cache yet. */
    fun consumeBeforeUse(): Boolean {
        if (!recreatePending || !replaceBeforeUse) return false
        recreatePending = false
        replaceBeforeUse = false
        return true
    }
    fun cleared() { recreatePending = false; replaceBeforeUse = false }
}
