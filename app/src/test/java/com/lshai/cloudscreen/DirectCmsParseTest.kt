package com.lshai.cloudscreen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 采集站返回格式的解析回归测试。
 *
 * `vod_play_url` 是采集站给的一整串「集名$地址#集名$地址」，
 * 多线路之间还用 `$$$` 分隔。解析错了的表现是「点进去没有集数」或「集数错位」，
 * 而这两种都只能靠真机点一遍才发现，所以用测试固定住。
 */
class DirectCmsParseTest {

    @Test
    fun `空串与纯空白没有集数`() {
        assertTrue(DirectCms.parsePlayUrls("").isEmpty())
        assertTrue(DirectCms.parsePlayUrls("   ").isEmpty())
    }

    @Test
    fun `按井号拆集数并只保留 http 地址`() {
        val raw = "第01集\$https://a.com/1.m3u8#第02集\$https://a.com/2.m3u8"
        assertEquals(
            listOf("https://a.com/1.m3u8", "https://a.com/2.m3u8"),
            DirectCms.parsePlayUrls(raw),
        )
    }

    @Test
    fun `多线路只取第一条`() {
        val raw = "第01集\$https://a.com/1.m3u8\$\$\$第01集\$https://b.com/1.m3u8"
        assertEquals(listOf("https://a.com/1.m3u8"), DirectCms.parsePlayUrls(raw))
    }

    @Test
    fun `非 http 的条目被丢掉`() {
        val raw = "第01集\$ftp://a.com/1.m3u8#第02集\$https://a.com/2.m3u8"
        assertEquals(listOf("https://a.com/2.m3u8"), DirectCms.parsePlayUrls(raw))
    }

    @Test
    fun `没有集名前缀的裸地址不算集数`() {
        // 采集站约定是「集名$地址」，没有 `$` 的整串当作无效
        assertTrue(DirectCms.parsePlayUrls("https://a.com/1.m3u8").isEmpty())
    }

    @Test
    fun `集名里的空格不影响地址`() {
        val raw = " 第01集 \$ https://a.com/1.m3u8 "
        assertEquals(listOf("https://a.com/1.m3u8"), DirectCms.parsePlayUrls(raw))
    }
}
