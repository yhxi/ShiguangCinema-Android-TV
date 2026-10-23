# 时光影院

面向 Android 手机与 Android TV 的原生影视客户端。此仓库独立维护和发布；应用需连接用户自行配置的 LibreTV 服务端与内容源。

## 功能

- 遥控器焦点导航、搜索、片源管理、直播与选集
- Media3 播放、续播、自动连播和磁盘缓存
- 本地观看历史、追剧、网络诊断与扫码导入片源
- 同一个 APK 支持手机和电视，最低 Android 6.0

## 构建

需要 JDK 25、Android SDK API 37。请在本机的 local.properties 配置 SDK 路径，然后运行 gradlew.bat :app:assembleDebug。

一键登录的预设密码仅从仓库根目录的 server-presets.properties 读取。该文件已被 Git 忽略；缺少它时仍可构建，但受密码保护的预设需要手动输入。文件格式：

    cloudscreen.tvworkerPassword=填写本机密码
    cloudscreen.tvoraclePassword=填写本机密码

签名所需的 keystore.properties 与 keystore/ 也只保存在本机，不提交到仓库。发给家人的 APK 仍包含一键登录密码，请只在信任的人之间分享。

详细功能和版本记录见 README-CLOUDSCREEN.md。

## 许可与来源

代码遵循 GPL-3.0，许可证见 LICENSE。项目包含源自 DpadTV 的代码，相关归属见 THIRD-PARTY.md；时光影院的版本发布和更新渠道独立于上游。
