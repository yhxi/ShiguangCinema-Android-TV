package com.lshai.cloudscreen

import org.json.JSONObject

internal fun mergeViewingHistory(local: List<JSONObject>, incoming: List<JSONObject>): List<JSONObject> {
    val seenUrls = mutableSetOf<String>()
    val seenWorks = mutableSetOf<String>()
    return (local + incoming).sortedByDescending { it.optLong("updatedAt") }.filter { entry ->
        val url = entry.optString("playUrl")
        val title = entry.optString("seriesTitle").ifBlank {
            entry.optString("title").substringBefore(" · 第").substringBefore("·第")
        }
        val work = normalizeSeries(workProgressSeries(title, entry.optString("year")))
        if (url in seenUrls || (work.isNotEmpty() && work in seenWorks)) return@filter false
        seenUrls.add(url)
        if (work.isNotEmpty()) seenWorks.add(work)
        true
    }.take(20)
}
