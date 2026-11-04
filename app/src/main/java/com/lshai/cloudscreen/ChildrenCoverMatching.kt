package com.lshai.cloudscreen

/** Only use a cover for the requested title or one of its explicitly named variants. */
internal fun childrenCoverCandidates(title: String, entries: List<Pair<String, String>>): List<String> {
    val wanted = normalizeSeries(title)
    if (wanted.isEmpty()) return emptyList()
    return entries.mapNotNull { (name, raw) ->
        val normalizedName = normalizeSeries(name)
        val rank = when {
            normalizedName == wanted -> 0
            // Only accept an explicit season suffix. A mere prefix can be a different
            // film or spin-off (e.g. 小猪佩奇过大年 vs 小猪佩奇).
            normalizedName.startsWith(wanted) &&
                normalizedName.removePrefix(wanted).matches(Regex("第[一二三四五六七八九十百0-9]+季")) -> 1
            else -> return@mapNotNull null
        }
        val url = raw.trim().let { if (it.startsWith("//")) "https:$it" else it }
        if (!url.startsWith("https://", true) && !url.startsWith("http://", true)) return@mapNotNull null
        rank to url
    }.sortedBy { it.first }.map { it.second }.distinct()
}
