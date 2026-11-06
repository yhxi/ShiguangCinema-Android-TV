package com.lshai.cloudscreen

/**
 * 从搜索结果里挑出该片名的候选封面，按匹配紧密度排序（调用方按序尝试）：
 *   0 = 片名完全一致（归一化后）
 *   1 = 片名 + 第N季（同一部作品的分季）
 *   2 = 系列成员：原始片名形如「系列名 + 分隔符 + 副标题」，如《猪猪侠之光明守卫者》
 *       《开心超人联盟之星际联盟》《蓝猫淘气三千问之海洋世界》。儿童乐园条目本身是
 *       系列名，点进去搜的就是整个系列，系列成员的封面远好过没有封面。
 *       分隔符检查用**原始片名**（归一化会抹掉 · : 等分隔符信息），且副标题前必须有
 *       分隔符 —— 《小猪佩奇过大年》这种无分隔符的独立续作不会被误当成同系列。
 * Only use a cover for the requested title or one of its explicitly named variants. */
internal fun childrenCoverCandidates(title: String, entries: List<Pair<String, String>>): List<String> {
    val wanted = normalizeSeries(title)
    if (wanted.isEmpty()) return emptyList()
    return entries.mapNotNull { (name, raw) ->
        val normalizedName = normalizeSeries(name)
        val rank = when {
            normalizedName == wanted -> 0
            normalizedName.startsWith(wanted) &&
                normalizedName.removePrefix(wanted).matches(Regex("第[一二三四五六七八九十百0-9]+季")) -> 1
            normalizedName.startsWith(wanted) && wanted.length >= 2 &&
                name.trim().startsWith(title.trim()) &&
                name.trim().removePrefix(title.trim()).matches(Regex("[之・·:：\\-—].+")) -> 2
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
