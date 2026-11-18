package com.lshai.cloudscreen

import kotlin.math.abs

internal data class SkipPoint(val position: Long, val duration: Long = 0L) {
    fun adjusted(delta: Long): SkipPoint =
        copy(position = (position + delta).coerceIn(1L, if (duration > 1L) duration - 1L else Long.MAX_VALUE))

    fun withReferenceDuration(reference: Long): SkipPoint =
        if (duration == 0L && reference > position) copy(duration = reference) else this
}

internal data class SkipMarks(val intro: SkipPoint? = null, val outro: SkipPoint? = null)

private val englishSeason = Regex("""(?i)\s*(?:\(\s*)?(?:season\s*|\bs(?=\d))([0-9]+)\s*\)?\s*$""")

internal fun skipMarksSeasonKey(title: String): String =
    sourceTitleKey(englishSeason.replace(seriesNameOf(title)) { "第${it.groupValues[1]}季" })

internal fun matchingSkipDuration(reference: Long, current: Long): Boolean =
    reference <= 0L || current <= 0L ||
        abs(current - reference) <= (reference / 10).coerceIn(30_000L, 90_000L)

internal fun safeIntroEnd(mark: SkipPoint, currentDuration: Long): Long? =
    mark.position.takeIf {
        it > 0L && matchingSkipDuration(mark.duration, currentDuration) &&
            (currentDuration <= 0L || it < currentDuration - 1_000L)
    }

/** Older marks without a duration keep their absolute position. */
internal fun safeOutroStart(legacyStart: Long, remaining: Long, referenceDuration: Long, currentDuration: Long): Long? {
    if (remaining <= 0 || referenceDuration <= 0) {
        return legacyStart.takeIf { it > 0 && (currentDuration <= 0 || it < currentDuration) }
    }
    if (currentDuration <= 0 || !matchingSkipDuration(referenceDuration, currentDuration)) return null
    val start = currentDuration - remaining
    return start.takeIf { it > 0 && it < currentDuration }
}

internal fun safeOutroStart(mark: SkipPoint, currentDuration: Long): Long? =
    safeOutroStart(mark.position, mark.duration - mark.position, mark.duration, currentDuration)

internal data class NextEpisodeStart(val position: Long, val awaitIntro: Boolean)
internal fun nextEpisodeStart(fromOutro: Boolean, resume: Long, threshold: Long, introEnabled: Boolean, introEnd: Long): NextEpisodeStart =
    NextEpisodeStart(if (!fromOutro && resume > threshold) resume else 0L, fromOutro && introEnabled && introEnd > 0L)
