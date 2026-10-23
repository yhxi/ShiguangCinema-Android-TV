package com.lshai.cloudscreen

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 采集站的客户端直连（仅标准苹果CMS）。
 *
 * 背景：现在所有搜索/详情都要先绕到 LibreTV 服务端（跑在 Cloudflare），
 * 国内设备 → Cloudflare → 采集站，一个来回就多两跳。而这些采集站本身
 * 就是标准 `?ac=detail&wd=` 接口，手机能连上就直接问，能省掉绕路。
 *
 * 适用条件（不满足就老老实实走服务端，避免行为不一致）：
 *   · https（明文 http 容易被运营商劫持，也绕不开部分平台的限制）
 *   · 路径里带 /api.php 或 /provide/vod —— 即标准苹果CMS
 *   · 不是成人源
 *
 * 注意：这里**不做**"全部走国内"的承诺。部分采集站域名本身解析到境外，
 * 直连只是少一跳，快不快还得看线路。
 */
object DirectCms {

    private const val UA = "okhttp/3.12.0"
    private const val CONNECT_TIMEOUT_MS = 3_500
    private const val READ_TIMEOUT_MS = 6_000

    fun isEligible(src: SourceConfig): Boolean {
        val u = src.url
        if (src.isAdult) return false
        if (!u.startsWith("https://", ignoreCase = true)) return false
        return u.contains("/api.php") || u.contains("/provide/vod")
    }

    /** 搜索。失败直接抛异常，由调用方决定是否回退服务端。 */
    fun search(src: SourceConfig, keyword: String): List<SearchItem> {
        val root = getJson(buildUrl(src.url, keyword))
        val list = root.optJSONArray("list") ?: return emptyList()
        val out = ArrayList<SearchItem>(list.length())
        for (i in 0 until list.length()) {
            val o = list.optJSONObject(i) ?: continue
            val id = o.optString("vod_id", "")
            val name = o.optString("vod_name", "")
            if (id.isEmpty() || name.isEmpty()) continue
            out.add(
                SearchItem(
                    sourceName = src.name,
                    vodId = id,
                    name = name,
                    pic = o.optString("vod_pic", ""),
                    typeName = o.optString("type_name", ""),
                    year = o.optString("vod_year", ""),
                    remarks = o.optString("vod_remarks", ""),
                    sourceUrl = src.url,
                ),
            )
        }
        return out
    }

    /** 详情。返回的 episodes 已是纯播放地址数组，和服务端 /api/detail 对齐。 */
    fun detail(src: SourceConfig, vodId: String): Detail {
        val root = getJson(buildUrl(src.url, null, vodId))
        val item = root.optJSONArray("list")?.optJSONObject(0)
            ?: throw IllegalStateException("详情为空")
        val urls = parsePlayUrls(item.optString("vod_play_url", ""))
        if (urls.isEmpty()) throw IllegalStateException("没有播放地址")
        return Detail(
            episodes = urls,
            info = VideoInfo(
                title = item.optString("vod_name", ""),
                cover = item.optString("vod_pic", ""),
                desc = item.optString("vod_content", ""),
                remarks = item.optString("vod_remarks", ""),
                year = item.optString("vod_year", ""),
                area = item.optString("vod_area", ""),
                actor = item.optString("vod_actor", ""),
            ),
        )
    }

    /**
     * vod_play_url 形如：
     *   第01集$https://x/1.m3u8#第02集$https://x/2.m3u8
     * 多线路时线路之间用 `$$$` 分隔（与 vod_play_from 一一对应），只取第一条线路。
     */
    fun parsePlayUrls(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        val firstLine = raw.split("$$$").firstOrNull().orEmpty()
        if (firstLine.isBlank()) return emptyList()
        val out = ArrayList<String>()
        for (part in firstLine.split("#")) {
            val url = part.substringAfter('$', "").trim()
            if (url.startsWith("http://") || url.startsWith("https://")) out.add(url)
        }
        return out
    }

    private fun buildUrl(base: String, keyword: String?, vodId: String? = null): String {
        val sep = if (base.contains("?")) "&" else "?"
        val b = base.trimEnd('/')
        return when {
            vodId != null -> "$b${sep}ac=detail&ids=${URLEncoder.encode(vodId, "UTF-8")}"
            keyword != null -> "$b${sep}ac=detail&wd=${URLEncoder.encode(keyword, "UTF-8")}"
            else -> b
        }
    }

    private fun getJson(url: String): JSONObject {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Accept", "application/json,text/plain,*/*")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            val raw = conn.inputStream.use { input ->
                val buf = ByteArrayOutputStream()
                input.copyTo(buf)
                buf.toByteArray()
            }
            val text = String(raw, Charsets.UTF_8).trim().removePrefix("\uFEFF")
            if (text.startsWith("<")) throw IllegalStateException("返回 HTML（反爬）")
            val start = text.indexOf('{')
            if (start < 0) throw IllegalStateException("非 JSON")
            return JSONObject(text.substring(start))
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    /** 供诊断使用：只测直连这一条路，拿到多少条搜索结果 */
    fun probe(src: SourceConfig, keyword: String, timeoutMs: Int = 5_000): Pair<Int, Long> {
        val t0 = System.currentTimeMillis()
        val count = runCatching { search(src, keyword).size }.getOrDefault(-1)
        return count to (System.currentTimeMillis() - t0)
    }
}
