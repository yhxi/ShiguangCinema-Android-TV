package com.lshai.cloudscreen

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 片名/简介清洗规则的回归测试。
 *
 * 这几条规则同时影响「搜索能不能命中」「历史记录能不能对上」「详情简介显示得干不干净」，
 * 改错一个正则不会报错、只会在真机上表现为「搜不到」或「标题带一堆垃圾」，
 * 所以用测试把它们钉住。
 */
class TextUtilsTest {

    @Test
    fun `stripHtml 去掉标签与实体并压掉多余空白`() {
        assertEquals("你好 世界", stripHtml("<p>你好</p><br/>世界"))
        assertEquals("a b", stripHtml("a&nbsp;b"))
        assertEquals("A&B", stripHtml("A&amp;B"))
        assertEquals("多 空格", stripHtml("  多  空格  "))
        assertEquals("", stripHtml(""))
    }

    @Test
    fun `cleanKeyword 去掉年份括号与标点`() {
        assertEquals("流浪地球2", cleanKeyword("流浪地球2（2023）"))
        assertEquals("流浪地球2", cleanKeyword("流浪地球2 (2023)"))
        assertEquals("流浪地球", cleanKeyword("[高清] 流浪地球"))
        assertEquals("海贼王 红发歌姬", cleanKeyword("海贼王：红发歌姬"))
        assertEquals("A B C", cleanKeyword("A.B-C"))
        assertEquals("", cleanKeyword("   "))
    }

    @Test
    fun `seriesNameOf 从播放标签里取出剧名`() {
        assertEquals("海贼王", seriesNameOf("海贼王 · 第 1135/1180 集"))
        assertEquals("海贼王", seriesNameOf("海贼王·第 3 集"))
        assertEquals("普通剧名", seriesNameOf("普通剧名"))
        assertEquals("", seriesNameOf(""))
    }

    @Test
    fun `normalizeSeries 忽略空格标点与大小写`() {
        // 采集站给同一部剧的标题常差在空格和分隔符上，归一化之后要能对上
        assertEquals(normalizeSeries("海贼王"), normalizeSeries(" 海贼王 "))
        assertEquals(normalizeSeries("abc"), normalizeSeries("A B C"))
        assertEquals("测试", normalizeSeries("（测试）"))
        assertEquals(normalizeSeries("第1集：测试"), normalizeSeries("第1集 测试"))
    }
}
