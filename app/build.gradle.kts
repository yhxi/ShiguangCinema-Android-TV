import java.util.Properties

plugins {
    id("com.android.application")
}

// 正式签名：密钥库与口令放在仓库根目录的 keystore.properties（已在 .gitignore 中）
// 找不到就不配置签名，assembleRelease 会退化成未签名包，但不会让构建失败。
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val hasSigning = keystoreProps.getProperty("storeFile")?.let {
    rootProject.file(it).exists()
} == true

// One-click server presets are supplied only by this machine's ignored file.
// Builds without it still work; protected presets then require a password.
val presetProps = Properties().apply {
    val f = rootProject.file("server-presets.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun javaString(value: String): String = "\"" + value
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")
    .replace("\n", "\\n") + "\""

android {
    namespace = "com.lshai.cloudscreen"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.lshai.cloudscreen"
        // 23 = Android 6.0：Media3 的下限之上，老盒子都能装
        minSdk = 23
        targetSdk = 37
        versionCode = 31
        versionName = "1.0.2"
    }

    signingConfigs {
        if (hasSigning) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            buildConfigField("boolean", "TEST_BUILD", "false")
            // Public Releases must never contain the local one-click credentials.
            buildConfigField("String", "TVWORKER_PASSWORD", "\"\"")
            buildConfigField("String", "TVORACLE_PASSWORD", "\"\"")
            // R8 混淆 + 压缩：dex 从 8.6MB 缩到 ~2MB，APK 4.8MB → 2.5MB 左右。
            // 项目无反射调用，media3 / zxing / coroutines 自带 consumer rules；
            // AndroidManifest 登记的四大组件 R8 自动 keep。
            optimization {
                enable = true
            }
            // 资源收缩与 R8 一起开：无引用的图片/布局等不再打进 APK
            isShrinkResources = true
            if (hasSigning) signingConfig = signingConfigs.getByName("release")
        }
        debug {
            buildConfigField("boolean", "TEST_BUILD", "true")
            buildConfigField("String", "TVWORKER_PASSWORD", javaString(presetProps.getProperty("cloudscreen.tvworkerPassword", "")))
            buildConfigField("String", "TVORACLE_PASSWORD", javaString(presetProps.getProperty("cloudscreen.tvoraclePassword", "")))
            // 调试包也用同一把密钥签名：以后从测试版换正式版不用卸载重装
            if (hasSigning) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        buildConfigField("String", "DEFAULT_URL", "\"https://tv.20350720.xyz/\"")
        buildConfigField("String", "DEFAULT_PASSWORD", "\"\"")
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    // 原生播放：ExoPlayer 对 HLS 的容错远好于 MediaPlayer，H.265 取决于 SoC 硬解
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    // 二维码生成（纯 Java，仅 core，不含 Android 集成包）
    implementation("com.google.zxing:core:3.5.3")
    // 首页手机端用 RecyclerView 做「头部 + 海报网格」的单层列表：
    // ScrollView 套一个撑高的 GridView 会让 GridView 一次布局所有行、复用失效。
    implementation("androidx.recyclerview:recyclerview:1.3.0")
    // recyclerview 1.3.0 声明的是 annotation:1.1.0 / core:1.7.0，本机离线仓库里只有
    // 更高的 1.6.0 / 1.9.0，显式抬高才能离线解析（API 上向后兼容）
    implementation("androidx.annotation:annotation:1.6.0")
    implementation("androidx.core:core:1.9.0")
    implementation("androidx.collection:collection:1.2.0")
}
