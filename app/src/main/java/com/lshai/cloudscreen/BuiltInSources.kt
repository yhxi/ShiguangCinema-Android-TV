package com.lshai.cloudscreen

/**
 * 内置默认片源。
 *
 * 之前是每次启动去拉 qist 的 TVBox 配置、现场筛出 https CMS —— 慢、而且会带入
 * 未经验证的死源。这里固化采集接口清单（原 16 个保留，2026-11-09 新增 4 个）：
 *   · 前 14 个：https + 标准苹果CMS（App 内可直连）；新增 4 个已验证搜索、详情与 HLS 清单可读取，未做实机播放验证
 *   · 后 6 个：http 源，本机网络被拦未能实测，保留但走服务端代理
 *
 * 更新方式：tools/source-health 巡检后重新生成，替换这份清单再打包。
 */
object BuiltInSources {

    val ALL: List<SourceConfig> = listOf(
        // ---- 已验证可播（https，支持客户端直连）----
        SourceConfig("虎牙", "https://www.huyaapi.com/api.php/provide/vod/from/hym3u8"),
        SourceConfig("火狐", "https://hhzyapi.com/api.php/provide/vod"),
        SourceConfig("暴风", "https://bfzyapi.com/api.php/provide/vod"),
        SourceConfig("量子", "https://cj.lziapi.com/api.php/provide/vod"),
        SourceConfig("如意", "https://cj.rycjapi.com/api.php/provide/vod"),
        SourceConfig("360采集", "https://360zy.com/api.php/provide/vod"),
        SourceConfig("百度", "https://api.apibdzy.com/api.php/provide/vod"),
        SourceConfig("尤酷", "https://api.ukuapi.com/api.php/provide/vod"),
        SourceConfig("淘片", "https://taopianapi.com/cjapi/mc/vod/json.html"),
        SourceConfig("极速", "https://jszyapi.com/api.php/provide/vod"),
        // ---- 2026-11-09 新增：搜索、详情及 HLS 清单已验证 ----
        SourceConfig("厂长", "https://api.changzhang.tv/api.php/provide/vod/"),
        SourceConfig("红牛", "https://www.hongniuzy2.com/api.php/provide/vod"),
        SourceConfig("金鹰", "https://jinyingzy.com/api.php/provide/vod"),
        SourceConfig("猫眼", "https://api.maoyanapi.top/api.php/provide/vod"),
        // ---- 待验证（http，走服务端代理）----
        SourceConfig("电影天堂", "http://caiji.dyttzyapi.com/api.php/provide/vod"),
        SourceConfig("天涯", "http://tyyszy.com/api.php/provide/vod"),
        SourceConfig("非凡(ffzy)", "http://ffzy.tv/api.php/provide/vod"),
        SourceConfig("建安", "http://154.219.117.232:9981/jacloudapi.php/provide/vod"),
        SourceConfig("非凡(ffzy1)", "http://ffzy1.tv/api.php/provide/vod"),
        SourceConfig("非凡(ffzy5)", "http://ffzy5.tv/api.php/provide/vod"),
    )
}


/** Return a merged copy without changing custom sources, order, or disabled flags. */
internal fun supplementBuiltInSources(current: List<SourceConfig>): List<SourceConfig> {
    val additions = BuiltInSources.ALL.filter { it.name in setOf("厂长", "红牛", "金鹰", "猫眼") }
    val oldUrls = BuiltInSources.ALL.filterNot { it in additions }.map { it.url.trimEnd('/') }.toSet()
    if (current.none { it.url.trimEnd('/') in oldUrls }) return current
    val seen = current.map { it.url.trimEnd('/') }.toMutableSet()
    return current + additions.filter { seen.add(it.url.trimEnd('/')) }
}
