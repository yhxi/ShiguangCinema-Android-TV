package com.lshai.cloudscreen

/**
 * 内置默认片源。
 *
 * 之前是每次启动去拉 qist 的 TVBox 配置、现场筛出 https CMS —— 慢、而且会带入
 * 未经验证的死源。这里直接固化一份 **2026-10-23 实测探活**的结果：
 *   · 前 10 个：https + 标准苹果CMS，搜索/详情实测能取到播放地址（App 内可直连，最快）
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
        // ---- 待验证（http，走服务端代理）----
        SourceConfig("电影天堂", "http://caiji.dyttzyapi.com/api.php/provide/vod"),
        SourceConfig("天涯", "http://tyyszy.com/api.php/provide/vod"),
        SourceConfig("非凡(ffzy)", "http://ffzy.tv/api.php/provide/vod"),
        SourceConfig("建安", "http://154.219.117.232:9981/jacloudapi.php/provide/vod"),
        SourceConfig("非凡(ffzy1)", "http://ffzy1.tv/api.php/provide/vod"),
        SourceConfig("非凡(ffzy5)", "http://ffzy5.tv/api.php/provide/vod"),
    )
}
