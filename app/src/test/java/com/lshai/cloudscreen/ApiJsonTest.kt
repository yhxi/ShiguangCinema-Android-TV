package com.lshai.cloudscreen

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 服务端响应解析的回归测试。
 *
 * `parseSearch` / `parseDouban` 的字段名全是「跟服务端约定俗成」的：
 * 服务端改个字段名，界面上只会表现为「搜索没有结果」或「首页空白」，
 * 没有任何报错。这些测试把字段兜底链钉住，改字段名时第一时间就会红。
 */
class ApiJsonTest {

    @Test
    fun `搜索解析出条目与失败源数`() {
        val root = JSONObject(
            """
            {"list":[
                {"sourceName":"源A","vodId":"1","name":"流浪地球","pic":"http://p/1.jpg",
                 "typeName":"科幻","year":"2023","remarks":"HD","sourceUrl":"https://a/api.php"},
                {"sourceName":"源B","vodId":"9","name":"流浪地球2","sourceUrl":"https://b/api.php"}
             ],
             "failures":["https://c/api.php","https://d/api.php"]}
            """.trimIndent(),
        )
        val r = parseSearch(root)
        assertEquals(2, r.items.size)
        assertEquals("流浪地球", r.items[0].name)
        assertEquals("HD", r.items[0].remarks)
        assertEquals(2, r.failures)
    }

    @Test
    fun `搜索没有 list 字段时返回空`() {
        val r = parseSearch(JSONObject("{}"))
        assertTrue(r.items.isEmpty())
        assertEquals(0, r.failures)
    }

    @Test
    fun `搜索条目里混入非对象会被跳过`() {
        val root = JSONObject("""{"list":[{"vodId":"1","name":"A"},"垃圾",null]}""")
        assertEquals(1, parseSearch(root).items.size)
    }

    @Test
    fun `豆瓣解析兼容 items 与旧字段名`() {
        val items = parseDouban(
            JSONObject("""{"items":[{"title":"沙丘","cover":"http://c/1.jpg","rating":"7.9"}]}"""),
        )
        assertEquals(listOf(DoubanItem("沙丘", "http://c/1.jpg", "7.9")), items)
    }

    @Test
    fun `豆瓣解析兼容 list 与 subjects 字段`() {
        assertTrue(parseDouban(JSONObject("""{"list":[{"title":"A"}]}""")).isNotEmpty())
        assertTrue(parseDouban(JSONObject("""{"subjects":[{"title":"B"}]}""")).isNotEmpty())
    }

    @Test
    fun `豆瓣字段名逐级兜底`() {
        val o = parseDouban(
            JSONObject("""{"items":[{"name":"老片","pic":"http://p/1.jpg","rate":"8.1"}]}"""),
        ).first()
        assertEquals("老片", o.title)
        assertEquals("http://p/1.jpg", o.cover)
        assertEquals("8.1", o.rating)
    }

    @Test
    fun `豆瓣空响应返回空列表`() {
        assertTrue(parseDouban(JSONObject("{}")).isEmpty())
    }
}
