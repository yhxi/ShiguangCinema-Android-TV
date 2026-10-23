package com.lshai.cloudscreen

/** 用户指定的一键登录预设。内置凭据可从 APK 提取，安装包仅供本人使用。 */
data class ServerPreset(val name: String, val url: String, val password: String = "")

object ServerPresets {
    // tvworker 排第一：它是默认服务端（BuildConfig.DEFAULT_URL 同步指向它）
    val ALL = listOf(
        ServerPreset("tvworker", "https://tv.20350720.xyz/", BuildConfig.TVWORKER_PASSWORD),
        ServerPreset("tvoracle", "https://tvoracle.20350720.xyz/", BuildConfig.TVORACLE_PASSWORD),
    )
}
