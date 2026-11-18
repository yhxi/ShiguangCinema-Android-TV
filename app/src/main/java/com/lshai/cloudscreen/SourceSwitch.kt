package com.lshai.cloudscreen

internal enum class SourcePriority { PREFERRED, NORMAL, RECENT_FAILURE }

internal fun sourcePriority(source: String, preferred: String, failedAt: Long, now: Long): SourcePriority =
    when {
        failedAt > 0 && now - failedAt in 0 until 10 * 60_000L -> SourcePriority.RECENT_FAILURE
        source == preferred -> SourcePriority.PREFERRED
        else -> SourcePriority.NORMAL
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
