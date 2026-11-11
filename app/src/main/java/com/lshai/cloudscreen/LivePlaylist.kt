package com.lshai.cloudscreen

/** Local M3U and name,url TXT playlists. Never execute playlist directives. */
internal fun parseLocalLivePlaylist(text: String): List<Channel> {
    val channels = linkedMapOf<String, Channel>()
    var name = ""
    var group = "其他"
    var logo = ""
    val attr = Regex("([a-z-]+)=\"([^\"]*)\"", RegexOption.IGNORE_CASE)
    fun add(label: String, url: String) {
        if (!url.startsWith("https://", true) && !url.startsWith("http://", true)) return
        if (url !in channels) channels[url] = Channel(label.ifBlank { "频道 ${channels.size + 1}" }, url, group, logo)
    }
    text.removePrefix("\uFEFF").lineSequence().map { it.trim() }.forEach { line ->
        when {
            line.startsWith("#EXTINF", true) -> {
                val attributes = attr.findAll(line).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                name = line.substringAfter(',', attributes["tvg-name"].orEmpty()).trim()
                group = attributes["group-title"] ?: "其他"
                logo = attributes["tvg-logo"].orEmpty()
            }
            line.startsWith("#EXTGRP:", true) -> group = line.substringAfter(':').ifBlank { "其他" }
            line.isBlank() || line.startsWith("#") -> Unit
            line.startsWith("http://", true) || line.startsWith("https://", true) -> { add(name, line); name = ""; logo = "" }
            ',' in line -> {
                val label = line.substringBefore(',').trim()
                val url = line.substringAfter(',').trim()
                if (url.equals("#genre#", true)) group = label.ifBlank { "其他" }
                else { logo = ""; add(label, url) }
            }
        }
    }
    return channels.values.toList()
}


internal fun defaultLiveChannelIndex(channels: List<Channel>): Int {
    if (channels.isEmpty()) return -1
    return channels.indexOfFirst { isCctvOne(it.name) }.takeIf { it >= 0 } ?: 0
}
internal fun isCctvOne(name: String): Boolean {
    val key = name.lowercase().trim().replace(Regex("[_—·-]"), "")
    return Regex("^(cctv|央视|中央)\\s*0?1(?![0-9])").containsMatchIn(key) ||
        key in setOf("中央一台", "央视一套", "央视综合", "中央电视台综合频道")
}
internal fun stepLiveChannel(index: Int, delta: Int, count: Int): Int =
    if (count <= 0) -1 else ((index + delta) % count + count) % count
