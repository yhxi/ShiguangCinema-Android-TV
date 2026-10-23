package com.lshai.cloudscreen

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** App 本地观看历史条目。完全本地，不与网页版/其它设备同步。 */
/**
 * 一条观看记录。
 *
 * 后四个字段是「从历史继续看时能不能恢复选集」的关键：
 * 以前只存当前这一集的 playUrl 和一句标题（"海贼王 · 第 1135/1180 集"），
 * 于是从历史进去播放器只有 1 个地址，点「选集」就提示"当前片源只有一集"。
 * 现在记下剧名 / 采集站 / vodId / 集号，播放器可以回查整季列表。
 */
data class HistoryEntry(
    val title: String,
    val playUrl: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
    val cover: String = "",
    /** 干净剧名，不含「 · 第 x/y 集」 */
    val seriesTitle: String = "",
    /** 采集站 vodId，能直接查详情 */
    val vodId: String = "",
    /** 采集站接口地址 */
    val sourceUrl: String = "",
    val sourceName: String = "",
    /** 在整季里的下标（从 0 开始）；-1 表示不知道 */
    val episodeIndex: Int = -1,
)

/** 本地配置：服务器、会话 Cookie、片源列表。全部存在应用私有 SharedPreferences。 */
class Prefs(ctx: Context) {

    data class ServerProfile(val url: String, val password: String, val cookie: String)

    private val sp = ctx.applicationContext.getSharedPreferences("cloudscreen", Context.MODE_PRIVATE)
    private fun scoped(key: String): String = "$key:${serverUrl.hashCode()}"

    private fun saveEncrypted(key: String, value: String) {
        val encrypted = runCatching { Secret.encrypt(value) }.getOrNull()
        val editor = sp.edit()
        if (encrypted == null) editor.remove(key) else editor.putString(key, encrypted)
        editor.apply()
    }

    var serverUrl: String
        get() = sp.getString("server_url", "") ?: ""
        set(v) = sp.edit().putString("server_url", v.trim().trimEnd('/')).apply()

    /** 访问密码：落盘前用 AndroidKeyStore 的 AES-GCM 加密（兼容旧的明文值） */
    var password: String
        get() = Secret.decrypt(sp.getString("password", "") ?: "")
        set(v) = saveEncrypted("password", v)

    /** 会话 Cookie：同上是敏感凭据，加密存 */
    var cookie: String
        get() = Secret.decrypt(sp.getString("cookie", "") ?: "")
        set(v) = saveEncrypted("cookie", v)

    fun savedServers(): List<ServerProfile> = try {
        val arr = JSONArray(Secret.decrypt(sp.getString("server_profiles", "[]") ?: "[]"))
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val url = o.optString("url", "")
            if (url.isBlank()) null else ServerProfile(
                url, o.optString("password", ""), o.optString("cookie", ""))
        }
    } catch (_: Exception) {
        emptyList()
    }

    fun rememberServer(url: String, password: String, cookie: String): Boolean {
        val normalized = url.trim().trimEnd('/')
        return try {
            val profiles = savedServers().filterNot { it.url == normalized }.toMutableList()
            profiles.add(0, ServerProfile(normalized, password, cookie))
            val arr = JSONArray()
            profiles.take(8).forEach { profile ->
                arr.put(JSONObject().apply {
                    put("url", profile.url)
                    put("password", profile.password)
                    put("cookie", profile.cookie)
                })
            }
            val encryptedProfiles = Secret.encrypt(arr.toString())
            val encryptedPassword = Secret.encrypt(password)
            val encryptedCookie = Secret.encrypt(cookie)
            sp.edit()
                .putString("server_profiles", encryptedProfiles)
                .putString("password", encryptedPassword)
                .putString("cookie", encryptedCookie)
                .commit()
        } catch (_: Exception) {
            // If secure storage fails, clear the credential slots rather than leaving
            // legacy/plaintext values behind or silently weakening protection.
            sp.edit()
                .remove("server_profiles")
                .remove("password")
                .remove("cookie")
                .apply()
            false
        }
    }

    fun sources(): MutableList<SourceConfig> {
        val text = sp.getString(scoped("sources"), "[]") ?: "[]"
        val out = ArrayList<SourceConfig>()
        try {
            val arr = JSONArray(text)
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { o -> SourceConfig.fromJson(o)?.let { out.add(it) } }
            }
        } catch (_: Exception) {
        }
        return out
    }

    fun saveSources(list: List<SourceConfig>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        sp.edit().putString(scoped("sources"), arr.toString()).apply()
    }

    fun addSources(newOnes: List<SourceConfig>): Int {
        val current = sources()
        val urlSet = current.map { it.url }.toHashSet()
        var added = 0
        for (s in newOnes) {
            if (urlSet.add(s.url)) {
                current.add(s)
                added++
            }
        }
        saveSources(current)
        return added
    }

    /** 续播进度：按 m3u8 地址记（换线路就是另一条记录，符合预期）。 */
    fun progressOf(playUrl: String): Long = sp.getLong("pos:" + playUrl.hashCode(), 0L)

    fun saveProgress(playUrl: String, positionMs: Long) {
        if (positionMs <= 5_000L) return // 开头几秒不值得记
        sp.edit().putLong("pos:" + playUrl.hashCode(), positionMs).apply()
    }

    fun clearProgress(playUrl: String) {
        sp.edit().remove("pos:" + playUrl.hashCode()).apply()
    }

    /*
     * 跨源进度：除了「按播放地址记」，再按「剧名 + 集号」记一份。
     *
     * 采集站换源就是换 URL，原来那份 pos 记录对不上，于是换源之后同一集又得从头拖 ——
     * 但换源恰恰是因为原源卡了/失效了，这个场景很常见。剧名 + 集号 是跨源唯一稳定的坐标
     * （片头片尾标记也是这么存的，见 skipKey）。
     *
     * 剧名先做一遍归一化：不同采集站返回的标题经常差在空格、全角括号、`·` 分隔符上
     * （「海贼王」/「海贼王 · 更新至 1000 集」），不归一化就白白匹配不上。
     */
    private fun normalizeSeries(series: String): String =
        series.replace(Regex("[\\s·・\\-—_()（）\\[\\]【】:：,，.。]"), "").lowercase()

    private fun episodeKey(series: String, index: Int): String {
        val s = normalizeSeries(series)
        if (s.isEmpty() || index < 0) return ""
        return "ep:" + (s + "#" + index).hashCode()
    }

    fun episodeProgressOf(series: String, index: Int): Long {
        val k = episodeKey(series, index)
        return if (k.isEmpty()) 0L else sp.getLong(k, 0L)
    }

    fun saveEpisodeProgress(series: String, index: Int, positionMs: Long) {
        val k = episodeKey(series, index)
        if (k.isEmpty() || positionMs <= 5_000L) return
        sp.edit().putLong(k, positionMs).apply()
    }

    fun clearEpisodeProgress(series: String, index: Int) {
        val k = episodeKey(series, index)
        if (k.isNotEmpty()) sp.edit().remove(k).apply()
    }

    /* --------------------------------------------------------------- 直播源 */

    fun liveUrls(): MutableList<String> {
        val text = sp.getString(scoped("live_urls"), "") ?: ""
        return if (text.isEmpty()) ArrayList() else text.split("\n").toMutableList()
    }

    fun saveLiveUrls(list: List<String>) {
        sp.edit().putString(scoped("live_urls"), list.joinToString("\n")).apply()
    }

    var activeLiveUrl: String
        get() = sp.getString(scoped("active_live"), "") ?: ""
        set(v) = sp.edit().putString(scoped("active_live"), v).apply()

    /* ------------------------------------------------------------- 搜索历史 */

    fun searchHistory(): MutableList<String> {
        val text = sp.getString("search_history", "") ?: ""
        return if (text.isEmpty()) ArrayList() else text.split("\n").toMutableList()
    }

    fun addSearchHistory(keyword: String) {
        val kw = keyword.trim()
        if (kw.isEmpty()) return
        val list = searchHistory()
        list.remove(kw)
        list.add(0, kw)
        while (list.size > 12) list.removeAt(list.size - 1)
        sp.edit().putString("search_history", list.joinToString("\n")).apply()
    }

    fun removeSearchHistory(keyword: String) {
        val list = searchHistory()
        if (list.remove(keyword)) {
            sp.edit().putString("search_history", list.joinToString("\n")).apply()
        }
    }

    fun clearSearchHistory() {
        sp.edit().remove("search_history").apply()
    }

    /* ------------------------------------------------------------- 观看历史 */

    /**
     * 「同一部剧」的判定键。
     *
     * 优先用播放器传下来的干净剧名（`seriesTitle`），没有就把标题里的「 · 第 x 集」砍掉。
     * 归一化之后比较，`海贼王` / `海贼王·` / `海贼 王` 算同一部。
     */
    private fun seriesKeyOf(e: HistoryEntry): String {
        val name = e.seriesTitle.ifEmpty {
            e.title.substringBefore(" · 第").substringBefore("·第")
        }.trim()
        return if (name.isEmpty()) "" else normalizeSeries(name)
    }

    fun history(): MutableList<HistoryEntry> {
        val raw = parseHistory()
        // 老版本只按播放地址去重，同一部剧每看一集就多一条 —— 「最近观看」里会出现
        // 海贼王 1134 / 1133 / 1132 三张并排的卡。这里按剧收成一条（保留最新的那条，
        // 列表本身已经是时间倒序），只在真有重复时写回一次，避免每次读都落盘。
        val seen = HashSet<String>()
        val kept = ArrayList<HistoryEntry>(raw.size)
        var dropped = false
        for (e in raw) {
            val k = seriesKeyOf(e)
            if (k.isNotEmpty() && !seen.add(k)) {
                dropped = true
                continue
            }
            kept.add(e)
        }
        if (dropped) writeHistory(kept)
        return kept
    }

    private fun parseHistory(): MutableList<HistoryEntry> {
        val text = sp.getString("history", "[]") ?: "[]"
        val out = ArrayList<HistoryEntry>()
        try {
            val arr = JSONArray(text)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val url = o.optString("playUrl", "")
                if (url.isEmpty()) continue
                out.add(
                    HistoryEntry(
                        title = o.optString("title", ""),
                        playUrl = url,
                        positionMs = o.optLong("positionMs", 0),
                        durationMs = o.optLong("durationMs", 0),
                        updatedAt = o.optLong("updatedAt", 0),
                        cover = o.optString("cover", ""),
                        seriesTitle = o.optString("seriesTitle", ""),
                        vodId = o.optString("vodId", ""),
                        sourceUrl = o.optString("sourceUrl", ""),
                        sourceName = o.optString("sourceName", ""),
                        episodeIndex = o.optInt("episodeIndex", -1),
                    ),
                )
            }
        } catch (_: Exception) {
        }
        return out
    }

    /** 历史记录的 JSON 表示。三处写回共用，避免加字段时漏掉某一个。 */
    private fun historyJson(e: HistoryEntry): JSONObject = JSONObject()
        .put("title", e.title)
        .put("playUrl", e.playUrl)
        .put("positionMs", e.positionMs)
        .put("durationMs", e.durationMs)
        .put("updatedAt", e.updatedAt)
        .put("cover", e.cover)
        .put("seriesTitle", e.seriesTitle)
        .put("vodId", e.vodId)
        .put("sourceUrl", e.sourceUrl)
        .put("sourceName", e.sourceName)
        .put("episodeIndex", e.episodeIndex)

    /**
     * 写入/更新一条历史。
     *
     * **按「剧」去重，不只按播放地址**：同一部剧换一集就是换 URL，只按地址去重的话
     * 「最近观看」会变成同一部剧的好几张卡（海贼王 1134 / 1133 / 1132 并排）。
     * 现在同一部剧只保留最近播放的那一集 —— 「最近观看」的语义本来就是「接着看」。
     * 想回看更早的集，从详情页的选集面板进。
     * 最近观看排最前，最多 40 条。
     */
    fun upsertHistory(entry: HistoryEntry) {
        val all = history()
        // 从历史页进入播放时拿不到封面（传的是旧记录里的空值），
        // 这里保留已有封面，避免"点一次历史，海报就没了"
        val existing = all.firstOrNull { it.playUrl == entry.playUrl }
        // 逐字段兜底：旧记录可能没有剧名/vodId，播放器这次也没带上，
        // 那就沿用已有值 —— 否则"从历史进去看一集"，选集能力反而被这次播放抹掉了
        val merged = if (existing != null) {
            entry.copy(
                cover = entry.cover.ifEmpty { existing.cover },
                title = entry.title.ifEmpty { existing.title },
                seriesTitle = entry.seriesTitle.ifEmpty { existing.seriesTitle },
                vodId = entry.vodId.ifEmpty { existing.vodId },
                sourceUrl = entry.sourceUrl.ifEmpty { existing.sourceUrl },
                sourceName = entry.sourceName.ifEmpty { existing.sourceName },
                episodeIndex = if (entry.episodeIndex >= 0) entry.episodeIndex else existing.episodeIndex,
            )
        } else {
            entry
        }
        val key = seriesKeyOf(merged)
        val list = all.filterNot {
            it.playUrl == merged.playUrl || (key.isNotEmpty() && seriesKeyOf(it) == key)
        }.toMutableList()
        list.add(0, merged)
        while (list.size > 40) list.removeAt(list.size - 1)
        writeHistory(list)
    }

    private fun writeHistory(list: List<HistoryEntry>) {
        val arr = JSONArray()
        list.forEach { e ->
            arr.put(historyJson(e))
        }
        sp.edit().putString("history", arr.toString()).apply()
    }

    fun removeHistory(playUrl: String) {
        writeHistory(history().filterNot { it.playUrl == playUrl })
    }

    fun clearHistory() {
        sp.edit().remove("history").apply()
    }

    /* ---------------------------------------------------------------- 追剧 */

    private fun favoriteJson(f: Favorite): JSONObject = JSONObject()
        .put("title", f.title)
        .put("cover", f.cover)
        .put("sourceName", f.sourceName)
        .put("vodId", f.vodId)
        .put("sourceUrl", f.sourceUrl)
        .put("episodeCount", f.episodeCount)
        .put("checkedAt", f.checkedAt)
        .put("newEpisodes", f.newEpisodes)

    fun favorites(): MutableList<Favorite> {
        val raw = sp.getString("favorites", null) ?: return mutableListOf()
        return runCatching {
            val arr = JSONArray(raw)
            val out = mutableListOf<Favorite>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val title = o.optString("title", "")
                if (title.isEmpty()) continue
                out.add(
                    Favorite(
                        title = title,
                        cover = o.optString("cover", ""),
                        sourceName = o.optString("sourceName", ""),
                        vodId = o.optString("vodId", ""),
                        sourceUrl = o.optString("sourceUrl", ""),
                        episodeCount = o.optInt("episodeCount", 0),
                        checkedAt = o.optLong("checkedAt", 0L),
                        newEpisodes = o.optInt("newEpisodes", 0),
                    ),
                )
            }
            out
        }.getOrDefault(mutableListOf())
    }

    private fun saveFavorites(list: List<Favorite>) {
        val arr = JSONArray()
        list.forEach { arr.put(favoriteJson(it)) }
        sp.edit().putString("favorites", arr.toString()).apply()
    }

    fun isFavorite(title: String): Boolean =
        title.isNotEmpty() && favorites().any { it.title == title }

    fun favoriteOf(title: String): Favorite? = favorites().firstOrNull { it.title == title }

    fun addFavorite(f: Favorite) {
        val list = favorites().filterNot { it.title == f.title }.toMutableList()
        list.add(0, f)
        while (list.size > 100) list.removeAt(list.size - 1)
        saveFavorites(list)
    }

    fun removeFavorite(title: String) {
        saveFavorites(favorites().filterNot { it.title == title })
    }

    /**
     * 检查更新后回写：集数变多了就记下新增数量并把基准抬到新集数，
     * 这样同一次更新只会提示一次。
     */
    fun markFavoriteChecked(title: String, episodes: Int, now: Long = System.currentTimeMillis()) {
        val list = favorites()
        val idx = list.indexOfFirst { it.title == title }
        if (idx < 0) return
        val f = list[idx]
        val grown = if (f.episodeCount > 0 && episodes > f.episodeCount) episodes - f.episodeCount else 0
        list[idx] = f.copy(
            episodeCount = if (episodes > 0) episodes else f.episodeCount,
            checkedAt = now,
            newEpisodes = grown,
        )
        saveFavorites(list)
    }

    /** 首页入口上要显示「有新集」的数量 */
    fun favoriteUpdateCount(): Int = favorites().count { it.newEpisodes > 0 }

    /* ---------------------------------------------------------------- 外挂字幕 */

    /**
     * 每一集单独记一份外挂字幕。
     *
     * 不按「剧名」记：同一部剧每集的 SRT 是不同文件，串了会直接对不上画面。
     */
    fun subtitleOf(playUrl: String): String = sp.getString("sub:" + playUrl.hashCode(), "").orEmpty()

    /** 返回给用户看的文件名（和 uri 一起存，取的时候不用再查一次 ContentResolver） */
    fun subtitleNameOf(playUrl: String): String =
        sp.getString("subname:" + playUrl.hashCode(), "").orEmpty()

    /** 字幕格式（SubRip / SSA / WebVTT），挂载时嗅探一次就存下来，避免每次播放都读文件头 */
    fun subtitleMimeOf(playUrl: String): String =
        sp.getString("submime:" + playUrl.hashCode(), "").orEmpty()

    fun saveSubtitle(playUrl: String, uri: String, name: String, mime: String) {
        sp.edit()
            .putString("sub:" + playUrl.hashCode(), uri)
            .putString("subname:" + playUrl.hashCode(), name)
            .putString("submime:" + playUrl.hashCode(), mime)
            .apply()
    }

    fun clearSubtitle(playUrl: String) {
        sp.edit()
            .remove("sub:" + playUrl.hashCode())
            .remove("subname:" + playUrl.hashCode())
            .remove("submime:" + playUrl.hashCode())
            .apply()
    }

    /* ------------------------------------------------------------- 播放设置 */

    /** 快进/快退步长（秒） */
    var seekStepSeconds: Int
        get() = sp.getInt("seek_step", 10)
        set(v) = sp.edit().putInt("seek_step", v).apply()

    /** 一集放完是否自动播下一集 */
    var autoNext: Boolean
        get() = sp.getBoolean("auto_next", true)
        set(v) = sp.edit().putBoolean("auto_next", v).apply()

    /**
     * 后台播放：开启后按返回键退出播放页，音频继续播；
     * 关闭时退出即停止播放并释放播放器内存。
     */
    var backgroundPlay: Boolean
        get() = sp.getBoolean("background_play", false)
        set(v) = sp.edit().putBoolean("background_play", v).apply()

    /** 是否记住播放进度并在下次续播 */
    var resumeEnabled: Boolean
        get() = sp.getBoolean("resume_enabled", true)
        set(v) = sp.edit().putBoolean("resume_enabled", v).apply()

    /** 进度超过这个秒数才续播（避免片头就被"续"到几秒处） */
    var resumeThresholdSeconds: Int
        get() = sp.getInt("resume_threshold", 5)
        set(v) = sp.edit().putInt("resume_threshold", v).apply()

    /** 播放倍速（1.0 = 原速） */
    var playbackSpeed: Float
        get() = sp.getFloat("playback_speed", 1f)
        set(v) = sp.edit().putFloat("playback_speed", v).apply()

    /**
     * 播放页亮度（0~1）。-1 = 跟随系统。
     * `window.attributes.screenBrightness` 只对当前窗口有效，不记下来的话
     * 每次进播放页都要重新拉一遍亮度条。
     */
    var playerBrightness: Float
        get() = sp.getFloat("player_brightness", -1f)
        set(v) = sp.edit().putFloat("player_brightness", v).apply()

    /** 解码方式：0 自动 / 1 强制软解 / 2 强制硬解（下次打开播放器生效） */
    var decodeMode: Int
        get() = sp.getInt("decode_mode", 0)
        set(v) = sp.edit().putInt("decode_mode", v).apply()

    /**
     * 「这部剧硬解不了，以后都走软解」的按剧标记。
     *
     * 解码失败的自动降级不能写全局 [decodeMode]：那会把**所有**片源都锁成软解，
     * 老设备上整体变卡，而用户完全不知道发生了什么。这里按剧名记，只影响那一部。
     */
    fun softDecodeFor(key: String): Boolean =
        key.isNotEmpty() && sp.getBoolean("soft_decode:" + key.hashCode(), false)

    fun setSoftDecodeFor(key: String, value: Boolean) {
        if (key.isEmpty()) return
        if (value) sp.edit().putBoolean("soft_decode:" + key.hashCode(), true).apply()
        else sp.edit().remove("soft_decode:" + key.hashCode()).apply()
    }

    /** 播放磁盘缓存上限（MB），默认 2048 */
    var cacheLimitMb: Int
        get() = sp.getInt("cache_limit_mb", 2048)
        set(v) = sp.edit().putInt("cache_limit_mb", v).apply()

    /**
     * 起播缓冲档位：0 快速 / 1 均衡 / 2 稳定。
     * 直接决定 ExoPlayer 的 minBufferMs——越小起播越快，弱网越容易卡。
     * 公共采集站的 m3u8 首片经常要 1~3 秒，默认给"均衡"。
     */
    var bufferMode: Int
        get() = sp.getInt("buffer_mode", 1).coerceIn(0, 2)
        set(v) = sp.edit().putInt("buffer_mode", v.coerceIn(0, 2)).apply()

    /** 首页推荐取哪个榜单："" = 最新+热门混合，"最新"，"热门" */
    var recommendTag: String
        get() = sp.getString("recommend_tag", "") ?: ""
        set(v) = sp.edit().putString("recommend_tag", v).apply()

    /* --------------------------------------------------------- 片头 / 片尾 */

    /**
     * 片头片尾跳过。
     *
     * 采集站给的 m3u8 没有章节信息，任何"自动识别片头"都是在猜，长期不可靠。
     * 所以这里用「用户标一次，整部剧复用」：在某一集里把当前位置记成片头结束/片尾开始，
     * 之后同一部剧的每一集都按这个秒数跳（动漫/美剧每集结构基本一致）。
     * 键用剧名，换源不影响 —— 不同源的正片时长差异通常只有几秒。
     */
    var skipIntroEnabled: Boolean
        get() = sp.getBoolean("skip_intro_on", true)
        set(v) = sp.edit().putBoolean("skip_intro_on", v).apply()

    var skipOutroEnabled: Boolean
        get() = sp.getBoolean("skip_outro_on", true)
        set(v) = sp.edit().putBoolean("skip_outro_on", v).apply()

    fun introEndOf(key: String): Long =
        if (key.isEmpty()) 0L else sp.getLong("intro_end:" + key.hashCode(), 0L)

    fun saveIntroEnd(key: String, ms: Long) {
        if (key.isEmpty()) return
        sp.edit().putLong("intro_end:" + key.hashCode(), ms.coerceAtLeast(0L)).apply()
    }

    fun outroStartOf(key: String): Long =
        if (key.isEmpty()) 0L else sp.getLong("outro_start:" + key.hashCode(), 0L)

    fun saveOutroStart(key: String, ms: Long) {
        if (key.isEmpty()) return
        sp.edit().putLong("outro_start:" + key.hashCode(), ms.coerceAtLeast(0L)).apply()
    }

    fun clearSkipMarks(key: String) {
        if (key.isEmpty()) return
        sp.edit()
            .remove("intro_end:" + key.hashCode())
            .remove("outro_start:" + key.hashCode())
            .apply()
    }

    /* ------------------------------------------------------- 首页推荐缓存 */

    /**
     * 首页推荐落盘。启动时先用上次的海报墙渲染，网络回来再替换 ——
     * 冷启动/断网/服务端慢的时候，首页不再是空白。
     */
    fun saveRecommendCache(mediaType: String, items: List<DoubanItem>) {
        if (items.isEmpty()) return
        val arr = JSONArray()
        items.take(120).forEach { d ->
            arr.put(
                JSONObject().put("title", d.title)
                    .put("cover", d.cover)
                    .put("rating", d.rating),
            )
        }
        sp.edit()
            .putString("recommend_cache", JSONObject().put("type", mediaType).put("items", arr).toString())
            .putLong("recommend_cache_at", System.currentTimeMillis())
            .apply()
    }

    /** 返回 (分类, 列表)；没有缓存返回 null */
    fun loadRecommendCache(): Pair<String, List<DoubanItem>>? {
        val text = sp.getString("recommend_cache", "") ?: ""
        if (text.isEmpty()) return null
        return try {
            val o = JSONObject(text)
            val arr = o.optJSONArray("items") ?: return null
            val list = ArrayList<DoubanItem>(arr.length())
            for (i in 0 until arr.length()) {
                val d = arr.optJSONObject(i) ?: continue
                val title = d.optString("title", "")
                if (title.isEmpty()) continue
                list.add(DoubanItem(title, d.optString("cover", ""), d.optString("rating", "")))
            }
            if (list.isEmpty()) null else o.optString("type", "tv") to list
        } catch (_: Exception) {
            null
        }
    }

    /** 首页右上角更新提示开关（关闭后只在设置页页脚显示红点） */
    var updateBannerEnabled: Boolean
        get() = sp.getBoolean("update_banner", true)
        set(v) = sp.edit().putBoolean("update_banner", v).apply()

    /** 已经看过详情的版本 tag，用于把胶囊从"未读"变成普通状态 */
    var updateBannerSeen: String
        get() = sp.getString("update_banner_seen", "") ?: ""
        set(v) = sp.edit().putString("update_banner_seen", v).apply()

    /** 最新版本的更新说明与页面地址（首页胶囊点开时展示） */
    var latestNotes: String
        get() = sp.getString("latest_notes", "") ?: ""
        set(v) = sp.edit().putString("latest_notes", v).apply()

    var latestUrl: String
        get() = sp.getString("latest_url", "") ?: ""
        set(v) = sp.edit().putString("latest_url", v).apply()

    var latestReleaseUrl: String
        get() = sp.getString("latest_release_url", "") ?: ""
        set(v) = sp.edit().putString("latest_release_url", v).apply()

    var updateDownloadId: Long
        get() = sp.getLong("update_download_id", -1L)
        set(v) = sp.edit().putLong("update_download_id", v).apply()

    var pendingUpdateInstallId: Long
        get() = sp.getLong("pending_update_install_id", -1L)
        set(v) = sp.edit().putLong("pending_update_install_id", v).apply()
    /** 远端最新 tag（空表示已是最新或还没检查过） */
    var latestTag: String
        get() = sp.getString("latest_tag", "") ?: ""
        set(v) = sp.edit().putString("latest_tag", v).apply()

    /** 上次检查更新的时间戳，避免每次启动都请求 */
    var lastUpdateCheck: Long
        get() = sp.getLong("last_update_check", 0L)
        set(v) = sp.edit().putLong("last_update_check", v).apply()
    /** 播放进度存在 pos:<hash> 键里，这里统计并整体清掉 */
    fun progressCount(): Int = sp.all.keys.count { it.startsWith("pos:") }

    fun clearAllProgress() {
        val editor = sp.edit()
        sp.all.keys.filter { it.startsWith("pos:") }.forEach { editor.remove(it) }
        editor.apply()
    }
    /** 首页顶部按钮顺序（id 列表） */
    fun homeBarOrder(): MutableList<String> {
        val text = sp.getString("home_bar_order", "") ?: ""
        if (text.isEmpty()) return HomeBar.DEFAULT.toMutableList()
        return text.split(',').filter { it.isNotEmpty() }.toMutableList()
    }

    fun saveHomeBarOrder(list: List<String>) {
        sp.edit().putString("home_bar_order", list.joinToString(",")).apply()
    }

    /** 记录某个源最近一次连通性测试结果（列表直接展示，不用每次重测） */
    fun saveSourceTest(url: String, ok: Boolean, ms: Long) {
        val k = "st_ok:" + url.hashCode()
        sp.edit()
            .putBoolean(k, ok)
            .putLong("st_ms:" + url.hashCode(), ms)
            .putLong("st_at:" + url.hashCode(), System.currentTimeMillis())
            .apply()
    }

    /** 最近一次测试结果：null 表示没测过 */
    fun sourceTest(url: String): Pair<Boolean, Long>? {
        val k = "st_ok:" + url.hashCode()
        if (!sp.contains(k)) return null
        return sp.getBoolean(k, false) to sp.getLong("st_ms:" + url.hashCode(), 0L)
    }

    /** 上次检测这条源的时间戳（毫秒）；没测过返回 0 */
    fun sourceTestAt(url: String): Long = sp.getLong("st_at:" + url.hashCode(), 0L)
    /** 首页顶部被隐藏的按钮 id */
    fun homeBarHidden(): Set<String> {
        val text = sp.getString("home_bar_hidden", "") ?: ""
        return if (text.isEmpty()) emptySet() else text.split(',').filter { it.isNotEmpty() }.toSet()
    }

    fun saveHomeBarHidden(ids: Set<String>) {
        sp.edit().putString("home_bar_hidden", ids.joinToString(",")).apply()
    }

    /** 给某条历史补封面（历史页自动补图用） */
    fun updateHistoryCover(playUrl: String, cover: String) {
        val list = history()
        val idx = list.indexOfFirst { it.playUrl == playUrl }
        if (idx < 0) return
        list[idx] = list[idx].copy(cover = cover)
        val arr = JSONArray()
        list.forEach { e ->
            arr.put(historyJson(e))
        }
        sp.edit().putString("history", arr.toString()).apply()
    }
}

object HomeBar {
    val DEFAULT = listOf("tv", "live", "search", "fav", "setup")

    fun label(id: String): String = when (id) {
        "movie" -> "电影"
        "tv" -> "剧集"
        "show" -> "综艺"
        "search" -> "搜索"
        "live" -> "直播"
        "history" -> "继续"
        "fav" -> "追剧"
        "sources" -> "片源"
        "setup" -> "设置"
        else -> id
    }
}

/**
 * 追剧条目。
 *
 * [episodeCount] 是收藏那一刻这部剧的总集数；下次检查时发现服务端更多了，
 * 就说明更新了 —— 纯本地比对，不需要服务端推送。
 */
data class Favorite(
    val title: String,
    val cover: String,
    val sourceName: String,
    val vodId: String,
    val sourceUrl: String,
    val episodeCount: Int,
    /** 上次检查更新的时间（毫秒） */
    val checkedAt: Long = 0L,
    /** 检查出来的新增集数 */
    val newEpisodes: Int = 0,
)
