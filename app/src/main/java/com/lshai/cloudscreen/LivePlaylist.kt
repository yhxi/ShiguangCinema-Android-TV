package com.lshai.cloudscreen

/** Local M3U and name,url TXT playlists. Never execute playlist directives. */
internal fun parseLocalLivePlaylist(text: String): List<Channel> {
    return inspectLocalLivePlaylist(text).channels
}

internal data class LivePlaylistReport(val channels: List<Channel>, val skipped: Int, val unsupportedDirectives: Int)

internal fun inspectLocalLivePlaylist(text: String): LivePlaylistReport {
    val channels = linkedMapOf<String, Channel>()
    var skipped = 0
    var unsupportedDirectives = 0
    var name = ""
    var group = "其他"
    var logo = ""
    val headers = linkedMapOf<String, String>()
    val attr = Regex("([a-z-]+)=\"([^\"]*)\"", RegexOption.IGNORE_CASE)
    fun add(label: String, url: String) {
        if (!url.startsWith("https://", true) && !url.startsWith("http://", true)) { skipped++; return }
        val key = url + "\u0000" + headers.entries.sortedBy { it.key }.joinToString("|") { "${it.key}=${it.value}" }
        if (key !in channels) channels[key] = Channel(label.ifBlank { "频道 ${channels.size + 1}" }, url, group, logo, headers.toMap())
    }
    text.removePrefix("\uFEFF").lineSequence().map { it.trim() }.forEach { line ->
        when {
            line.startsWith("#EXTINF", true) -> {
                val attributes = attr.findAll(line).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                var quoted = false
                val comma = line.indexOfFirst { ch ->
                    if (ch == '"') quoted = !quoted
                    ch == ',' && !quoted
                }
                name = (if (comma >= 0) line.substring(comma + 1) else attributes["tvg-name"].orEmpty()).trim()
                group = attributes["group-title"] ?: "其他"
                logo = attributes["tvg-logo"].orEmpty()
                headers.clear()
            }
            line.startsWith("#EXTGRP:", true) -> group = line.substringAfter(':').ifBlank { "其他" }
            line.startsWith("#EXTVLCOPT:", true) -> {
                val option = line.substringAfter(':')
                val header = when (option.substringBefore('=').lowercase()) {
                    "http-user-agent" -> "User-Agent"
                    "http-referrer" -> "Referer"
                    "http-origin" -> "Origin"
                    else -> null
                }
                val value = option.substringAfter('=', "").trim()
                if (header == null || value.isEmpty() || value.length > 512) unsupportedDirectives++
                else headers[header] = value
            }
            line.startsWith("#KODIPROP", true) || line.startsWith("#EXTHTTP", true) -> unsupportedDirectives++
            line.isBlank() || line.startsWith("#") -> Unit
            line.startsWith("http://", true) || line.startsWith("https://", true) -> { add(name, line); name = ""; logo = ""; headers.clear() }
            ',' in line -> {
                val label = line.substringBefore(',').trim()
                val url = line.substringAfter(',').trim()
                if (url.equals("#genre#", true)) group = label.ifBlank { "其他" }
                else { logo = ""; headers.clear(); add(label, url) }
            }
            else -> skipped++
        }
    }
    return LivePlaylistReport(channels.values.toList(), skipped, unsupportedDirectives)
}


internal fun defaultLiveChannelIndex(channels: List<Channel>): Int {
    if (channels.isEmpty()) return -1
    return channels.indexOfFirst { isCctvOne(it.name) }.takeIf { it >= 0 } ?: 0
}
internal fun isCctvOne(name: String): Boolean {
    val key = name.lowercase().trim().replace(Regex("[_—·-]"), "")
    return Regex("^((?:央视)?cctv|央视|中央)\\s*0?1(?![0-9])").containsMatchIn(key) ||
        key in setOf("中央一台", "央视一套", "央视综合", "中央电视台综合频道")
}
internal fun stepLiveChannel(index: Int, delta: Int, count: Int): Int =
    if (count <= 0) -1 else ((index + delta) % count + count) % count


/** CCTV numbered channels first, then local channels, then remaining entries; stable within groups. */
internal fun sortedLiveChannels(channels: List<Channel>): List<Channel> {
    val cctv = Regex("^(?:(?:央视)?cctv|央视|中央)\\s*0?([0-9]{1,2})(?![0-9])", RegexOption.IGNORE_CASE)
    val local = Regex("卫视|地方|省台|市台|北京|上海|天津|重庆|河北|山西|辽宁|吉林|黑龙江|江苏|浙江|安徽|福建|江西|山东|河南|湖北|湖南|广东|广西|海南|四川|贵州|云南|陕西|甘肃|青海|宁夏|新疆|内蒙古|西藏|深圳|厦门|青岛|大连|宁波")
    fun rank(c: Channel): Pair<Int, Int> {
        val name = c.name.trim().replace(Regex("[_—·-]"), "")
        if (Regex("^(?:央视)?cctv[48]k$", RegexOption.IGNORE_CASE).matches(name.replace(Regex("\\s+"), ""))) return 0 to 1000
        val match = cctv.find(name)
        if (match != null) {
            val number = match.groupValues[1].toIntOrNull() ?: 99
            return 0 to (number * 2 + if (name.substring(match.range.last + 1).trimStart().startsWith("+")) 1 else 0)
        }
        if (isCctvOne(c.name)) return 0 to 2
        if (name.startsWith("CCTV", true) || name.startsWith("央视")) return 0 to 1000
        return if (local.containsMatchIn(c.name) || local.containsMatchIn(c.group)) 1 to 0 else 2 to 0
    }
    val ranked = channels.mapIndexed { index, channel -> Triple(index, channel, rank(channel)) }
    return ranked.sortedWith(compareBy({ it.third.first }, { it.third.second }, { it.first })).map { it.second }
}

internal fun liveChannelKey(name: String): String {
    val original = name.trim().lowercase().replace(Regex("[\\s_—·-]"), "")
    if (original in setOf("cctv4k", "cctv8k", "央视cctv4k", "央视cctv8k")) return original
    val key = original.replace(Regex("(超高清|高清|标清|fhd|uhd|hd|4k|1080p|720p)+$"), "")
    return if (key in setOf("cctv1综合", "cctv01综合", "央视cctv1", "央视cctv1综合", "cctv01", "央视综合", "中央一台", "央视一套", "中央电视台综合频道")) "cctv1" else key
}
