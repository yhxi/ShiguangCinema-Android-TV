package com.lshai.cloudscreen

/** Built-in IPTV playlists, kept separate from on-demand CMS sources. */
object BuiltInLiveSources {
    const val GUOVIN = "https://gh-proxy.com/https://raw.githubusercontent.com/Guovin/iptv-api/gd/output/result.m3u"
    const val VBSKYC = "https://ghproxy.net/https://raw.githubusercontent.com/vbskycn/iptv/master/tv/iptv4.m3u"

    val ALL: List<String> = listOf(GUOVIN, VBSKYC)

    fun label(url: String): String = when (url) {
        GUOVIN -> "Guovin IPTV"
        VBSKYC -> "IPTV4 备用源"
        else -> url
    }
}
