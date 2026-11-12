package com.lshai.cloudscreen

import kotlin.math.abs

/** Older marks without a duration keep their absolute position. */
internal fun safeOutroStart(legacyStart: Long, remaining: Long, referenceDuration: Long, currentDuration: Long): Long? {
    if (remaining <= 0 || referenceDuration <= 0) return legacyStart.takeIf { it > 0 }
    if (currentDuration <= 0 || abs(currentDuration - referenceDuration) > maxOf(30_000L, referenceDuration / 4)) return null
    val start = currentDuration - remaining
    return start.takeIf { it > 0 && it < currentDuration - 5_000L }
}
