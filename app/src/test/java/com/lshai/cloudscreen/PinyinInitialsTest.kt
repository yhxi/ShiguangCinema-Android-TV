package com.lshai.cloudscreen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 拼音首字母表的回归测试。
 *
 * 电视上打中文全拼太痛苦，搜索靠「输入片名首字母 → 联想」这一条路；
 * 表里的字错了，用户输入 cjqc 联想不出《超级汽车》，而且没有任何报错。
 * 这里的样例都是精选片单里真实会用到的剧名。
 */
class PinyinInitialsTest {

    @Test
    fun `中文片名取首字母`() {
        assertEquals("cjqc", PinyinInitials.initialsOf("超级汽车"))
        assertEquals("xn", PinyinInitials.initialsOf("仙逆"))
        assertEquals("frxxc", PinyinInitials.initialsOf("凡人修仙传"))
        assertEquals("dpcq", PinyinInitials.initialsOf("斗破苍穹"))
        assertEquals("xzpq", PinyinInitials.initialsOf("小猪佩奇"))
        assertEquals("wwdldg", PinyinInitials.initialsOf("汪汪队立大功"))
    }

    @Test
    fun `字母与数字原样保留`() {
        assertEquals("cjbbjojo", PinyinInitials.initialsOf("超级宝贝JOJO"))
        assertTrue(PinyinInitials.initialsOf("abc").startsWith("abc"))
    }

    @Test
    fun `纯符号得到空串`() {
        assertEquals("", PinyinInitials.initialsOf("！·——"))
        assertEquals("", PinyinInitials.initialsOf(""))
    }

    @Test
    fun `表外的生僻字不抛异常`() {
        // GB2312 之外的字符（如 emoji、扩展 B 区汉字）应当被跳过而不是崩溃
        assertEquals("dq", PinyinInitials.initialsOf("地球🌍"))
    }
}
