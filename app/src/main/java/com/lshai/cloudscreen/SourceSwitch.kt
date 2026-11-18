package com.lshai.cloudscreen

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select

internal enum class SourcePriority { PREFERRED, NORMAL, RECENT_FAILURE }

internal fun sourcePriority(source: String, preferred: String, failedAt: Long, now: Long): SourcePriority =
    when {
        failedAt > 0 && now - failedAt in 0 until 10 * 60_000L -> SourcePriority.RECENT_FAILURE
        source == preferred -> SourcePriority.PREFERRED
        else -> SourcePriority.NORMAL
    }

/** Give regular sources a short head start, then use whichever tier has a candidate ready. */
@OptIn(ExperimentalCoroutinesApi::class)
internal suspend fun <T : Any> firstPlayableSource(
    regular: ReceiveChannel<T>,
    backup: ReceiveChannel<T>,
    headStartMs: Long,
    tryPlayback: suspend (T) -> Boolean,
): T? {
    val initial = select<ChannelResult<T>?> {
        regular.onReceiveCatching { it }
        onTimeout(headStartMs) { null }
    }
    val first = initial?.getOrNull()
    if (first != null && tryPlayback(first)) return first

    var regularOpen = initial?.isClosed != true
    var backupOpen = true
    while (regularOpen || backupOpen) {
        // select checks ready clauses in declaration order. A slow regular source
        // never blocks an already-ready backup after the initial wait.
        val playable = select<T?> {
            if (regularOpen) regular.onReceiveCatching { result ->
                if (result.isClosed) regularOpen = false
                val candidate = result.getOrNull()
                if (candidate != null && tryPlayback(candidate)) candidate else null
            }
            if (backupOpen) backup.onReceiveCatching { result ->
                if (result.isClosed) backupOpen = false
                val candidate = result.getOrNull()
                if (candidate != null && tryPlayback(candidate)) candidate else null
            }
        }
        if (playable != null) return playable
    }
    return null
}

private val episodeLabelPattern = Regex(
    """(?:第\s*(\d+)\s*[集话話期]|(?:EP?\s*)?(\d+))""",
    RegexOption.IGNORE_CASE,
)

internal fun sourceEpisodeNumber(label: String): Int {
    val match = episodeLabelPattern.matchEntire(label.trim()) ?: return 0
    return match.groupValues[1].ifEmpty { match.groupValues[2] }.toIntOrNull() ?: 0
}

internal fun matchingSourceEpisode(detail: Detail, episode: Int): Int? {
    val numbers = detail.episodeLabels.map(::sourceEpisodeNumber)
    // Prefer explicit numbering when a source omits or reorders episodes.
    if (numbers.any { it > 0 }) return numbers.indices.singleOrNull { numbers[it] == episode }
    return (episode - 1).takeIf { it in detail.episodes.indices }
}
