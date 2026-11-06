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


/** Prefer sufficient resolution and a shape that needs less cropping. No text recognition.
 *  清晰度权重 0.85：卡片本身是 CENTER_CROP，竖版海报裁切后观感没问题，
 *  清晰度才是「看着糊」的主因；比例只作轻微惩罚。 */
internal fun childrenCoverQuality(w: Int, h: Int, targetW: Int, targetH: Int): Double {
    if (w <= 0 || h <= 0 || targetW <= 0 || targetH <= 0) return 0.0
    val clarity = minOf(w.toDouble() / targetW, h.toDouble() / targetH, 1.0)
    val ratio = (w.toDouble() / h) / (targetW.toDouble() / targetH)
    val visible = minOf(ratio, 1.0 / ratio)
    return clarity * .85 + visible * .15
}
