package com.lshai.cloudscreen

import org.junit.Assert.*
import org.junit.Test

class LivePlaylistTest {
    @Test fun m3uPreservesNamesGroupsLogosAndDeduplicates() {
        val text = "\uFEFF#EXTM3U\n#EXTINF:-1 group-title=\"少儿\" tvg-logo=\"https://example.com/a.png\",少儿频道\nhttps://example.com/a.m3u8\n#EXTINF:-1,重复\nhttps://example.com/a.m3u8"
        val channels = parseLocalLivePlaylist(text)
        assertEquals(1, channels.size)
        assertEquals("少儿频道", channels[0].name)
        assertEquals("少儿", channels[0].group)
        assertEquals("https://example.com/a.png", channels[0].logo)
    }
    @Test fun txtSupportsGroupsAndRejectsUnsupportedSchemes() {
        val channels = parseLocalLivePlaylist("新闻,#genre#\n新闻频道,https://example.com/news.m3u8\n坏地址,file:///data/private\n组播,udp://224.1.1.1:1234\n另一频道,http://example.com/tv")
        assertEquals(listOf("新闻频道", "另一频道"), channels.map { it.name })
        assertTrue(channels.all { it.group == "新闻" })
    }
    @Test fun malformedInputIsNotAcceptedAsAChannel() {
        assertTrue(parseLocalLivePlaylist("#EXTM3U\n#EXTINF:-1,缺地址").isEmpty())
        assertTrue(parseLocalLivePlaylist("不是列表").isEmpty())
    }

    @Test fun cctvOneIsSelectedWithoutConfusingCctvTenOrEleven() {
        val channels = listOf("CCTV10", "CCTV11", "CCTV-1 综合 HD", "地方台").map {
            Channel(it, "https://example.com/$it", "", "")
        }
        assertEquals(2, defaultLiveChannelIndex(channels))
        assertFalse(isCctvOne("CCTV10综合"))
        assertFalse(isCctvOne("CCTV11"))
        assertTrue(isCctvOne("央视1套"))
        assertTrue(isCctvOne("CCTV-1 1080P"))
        assertTrue(isCctvOne("CCTV 1 4K"))
        assertEquals(0, defaultLiveChannelIndex(channels.take(2)))
        assertEquals(-1, defaultLiveChannelIndex(emptyList()))
    }
    @Test fun remoteChannelNavigationWrapsAtBothEnds() {
        assertEquals(2, stepLiveChannel(0, -1, 3))
        assertEquals(0, stepLiveChannel(2, 1, 3))
        assertEquals(0, stepLiveChannel(0, 1, 1))
        assertEquals(-1, stepLiveChannel(0, 1, 0))
    }
}
