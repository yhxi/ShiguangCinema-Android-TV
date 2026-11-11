package com.lshai.cloudscreen

internal fun quotaPlaybackActive(child: Boolean, isPlaying: Boolean): Boolean = child && isPlaying

/** A single elapsed interval is charged once, regardless of how many entry points are active. */
internal fun quotaUsage(sameDay: Boolean, used: Long, elapsed: Long, active: Boolean, sinceMidnight: Long): Long {
    val charge = if (active) elapsed.coerceAtLeast(0) else 0L
    return if (sameDay) used.coerceAtLeast(0) + charge else minOf(charge, sinceMidnight.coerceAtLeast(0))
}
