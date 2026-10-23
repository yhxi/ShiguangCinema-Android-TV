package com.lshai.cloudscreen

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 整季选集列表的本地缓存。
 *
 * 为什么需要：像《海贼王》这种上千集的剧，从历史记录进来时播放器只有一个播放地址，
 * 点「选集」得先把服务端搜索一遍、再逐个查详情，动辄十几秒。而这个列表其实一天里
 * 几乎不变（更新一次就是加几集）。
 *
 * 键：服务端 + 片源地址 + vodId
 *   —— 必须带片源地址：同名剧在不同采集站的集数和地址完全不同，混用会串剧。
 * 值：剧集播放地址数组 + 写入时间 + 首末集地址（首末集变了就说明整季换了版本）。
 *
 * 失效条件（任一命中就重新拉取，绝不拿旧数据硬套）：
 *   · 超过 7 天
 *   · 当前正在播的地址不在缓存列表里
 *   · 首集地址对不上
 */
class EpisodeCache(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("episode_cache", Context.MODE_PRIVATE)

    data class Entry(val urls: List<String>, val savedAt: Long) {
        val first: String? get() = urls.firstOrNull()
        val last: String? get() = urls.lastOrNull()
        fun isFresh(now: Long = System.currentTimeMillis()): Boolean = now - savedAt < TTL_MS
    }

    companion object {
        /** 7 天。采集站换地址一般也就这个节奏 */
        private const val TTL_MS = 7L * 24 * 60 * 60 * 1000
        /** 最多留 40 部，超了按最旧的淘汰 —— 一季上千条地址，不能无限堆 */
        private const val MAX_ENTRIES = 40
        private const val KEY_ENTRIES = "entries"
    }

    private fun keyOf(server: String, sourceUrl: String, vodId: String): String =
        server.trimEnd('/') + "|" + sourceUrl.trimEnd('/') + "|" + vodId

    /** 读缓存；过期或数据损坏都返回 null */
    fun get(server: String, sourceUrl: String, vodId: String): Entry? {
        if (vodId.isEmpty()) return null
        val root = readRoot() ?: return null
        val o = root.optJSONObject(keyOf(server, sourceUrl, vodId)) ?: return null
        val arr = o.optJSONArray("urls") ?: return null
        val urls = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            val u = arr.optString(i, "")
            if (u.isNotEmpty()) urls.add(u)
        }
        if (urls.isEmpty()) return null
        val entry = Entry(urls, o.optLong("ts", 0L))
        return if (entry.isFresh()) entry else null
    }

    fun put(server: String, sourceUrl: String, vodId: String, urls: List<String>) {
        if (vodId.isEmpty() || urls.isEmpty()) return
        // 空地址、或单集（多半是详情接口只回了半截）不写，别把坏数据固化下来
        if (urls.size <= 1) return
        val root = readRoot() ?: JSONObject()
        val now = System.currentTimeMillis()
        root.put(
            keyOf(server, sourceUrl, vodId),
            JSONObject().apply {
                put("urls", JSONArray(urls))
                put("ts", now)
                put("first", urls.first())
                put("last", urls.last())
            },
        )
        pruneInternal(root, now)
        sp.edit().putString(KEY_ENTRIES, root.toString()).apply()
    }

    fun clear() {
        sp.edit().remove(KEY_ENTRIES).apply()
    }

    fun size(): Int = readRoot()?.length() ?: 0

    private fun readRoot(): JSONObject? {
        val raw = sp.getString(KEY_ENTRIES, null) ?: return null
        return try {
            JSONObject(raw)
        } catch (e: Exception) {
            null
        }
    }

    /** 先砍过期，再按写入时间砍到 MAX_ENTRIES 以内 */
    private fun pruneInternal(root: JSONObject, now: Long) {
        val keys = root.keys().asSequence().toList()
        val alive = ArrayList<Pair<String, Long>>()
        for (k in keys) {
            val ts = root.optJSONObject(k)?.optLong("ts", 0L) ?: 0L
            if (now - ts >= TTL_MS) {
                root.remove(k)
            } else {
                alive.add(k to ts)
            }
        }
        if (alive.size <= MAX_ENTRIES) return
        alive.sortedBy { it.second }
            .take(alive.size - MAX_ENTRIES)
            .forEach { root.remove(it.first) }
    }
}
