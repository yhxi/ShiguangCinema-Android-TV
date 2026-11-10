package com.lshai.cloudscreen

/** Recognize season, extra and language variants without loose substring matching. */
private val childVariantSuffix = Regex("(第[一二三四五六七八九十百零两0-9]+[季部辑]|season[0-9]+|番外篇|番外|特别篇|特别版|特別篇|特別版|剧场版|劇場版|大电影|电影版|动画版|上篇|下篇|上集|下集|中文版|英文版|普通话版|国语版|英语版|粤语版|中文|国语|普通话|英语|粤语|全集|完整版)+$")

internal fun childQuotaKey(title: String): String = normalizeSeries(seriesNameOf(title)).replace(childVariantSuffix, "")

private val childSeriesAliases = mapOf("大耳朵图图彩色世界" to "大耳朵图图")
private val childSubtitleSeparator = Regex("^[之・·:：—-].+")

internal fun matchesChildQuotaTitle(title: String, storedKeys: Set<String>): Boolean {
    val key = childQuotaKey(title)
    if (key.isEmpty()) return false
    val raw = seriesNameOf(title).replace(Regex("\\s+"), "").lowercase()
    return storedKeys.any {
        val base = childQuotaKey(it)
        base.isNotEmpty() && (key == base || childSeriesAliases[key] == base ||
            (raw.startsWith(base) && childSubtitleSeparator.matches(raw.removePrefix(base))))
    }
}
