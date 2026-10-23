package com.lshai.cloudscreen

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.URL

/**
 * 检查 GitHub Releases 的最新版本。
 *
 * 只请求 `/releases/latest`（一次），拿 tag 名、更新说明和页面地址；
 * 将网络失败、GitHub 限流、HTTP 错误和无效响应分开返回，供手动检查显示有用提示。
 */
object UpdateChecker {

    private const val API = "https://api.github.com/repos/yhxi/ShiguangCinema-Android-TV/releases/latest"

    data class ReleaseInfo(
        val tag: String,
        val notes: String,
        val apkUrl: String,
        val releaseUrl: String,
    )

    sealed class CheckResult {
        data class Success(val release: ReleaseInfo) : CheckResult()
        data class Failure(val reason: FailureReason, val httpCode: Int? = null) : CheckResult()
    }

    enum class FailureReason { NETWORK, RATE_LIMITED, HTTP_ERROR, INVALID_RESPONSE }

    fun check(onResult: (CheckResult) -> Unit) {
        Thread {
            val result = try {
                val conn = URL(API).openConnection() as HttpURLConnection
                try {
                    conn.connectTimeout = 8000
                    conn.readTimeout = 8000
                    conn.setRequestProperty("User-Agent", "ShiguangCinema-Android-TV")
                    conn.setRequestProperty("Accept", "application/vnd.github+json")
                    val code = conn.responseCode
                    if (code == HttpURLConnection.HTTP_OK) {
                        val body = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                        val o = JSONObject(body)
                        val tag = o.optString("tag_name", "")
                        if (tag.isBlank()) {
                            CheckResult.Failure(FailureReason.INVALID_RESPONSE)
                        } else {
                            val apkUrl = o.optJSONArray("assets")?.let { assets ->
                                (0 until assets.length()).asSequence()
                                    .mapNotNull { assets.optJSONObject(it) }
                                    .firstOrNull { it.optString("name").endsWith(".apk", ignoreCase = true) }
                                    ?.optString("browser_download_url")
                            }.orEmpty()
                            val releaseUrl = o.optString("html_url", "")
                            if (releaseUrl.isBlank()) {
                                CheckResult.Failure(FailureReason.INVALID_RESPONSE)
                            } else {
                                CheckResult.Success(
                                    ReleaseInfo(tag, o.optString("body", ""), apkUrl, releaseUrl),
                                )
                            }
                        }
                    } else {
                        CheckResult.Failure(
                            if (code == 403 || code == 429) FailureReason.RATE_LIMITED
                            else FailureReason.HTTP_ERROR,
                            code,
                        )
                    }
                } finally {
                    conn.disconnect()
                }
            } catch (_: UnknownHostException) {
                CheckResult.Failure(FailureReason.NETWORK)
            } catch (_: SocketTimeoutException) {
                CheckResult.Failure(FailureReason.NETWORK)
            } catch (_: java.io.IOException) {
                CheckResult.Failure(FailureReason.NETWORK)
            } catch (_: Exception) {
                CheckResult.Failure(FailureReason.INVALID_RESPONSE)
            }
            Handler(Looper.getMainLooper()).post { onResult(result) }
        }.start()
    }

    /** 比较版本号：远端形如 v1.1.0，本地形如 1.0.0 */
    fun isNewer(tag: String?, current: String): Boolean {
        if (tag.isNullOrBlank()) return false
        val remote = parse(tag)
        val local = parse(current)
        for (i in 0 until maxOf(remote.size, local.size)) {
            val a = remote.getOrElse(i) { 0 }
            val b = local.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    private fun parse(v: String): List<Int> =
        v.trim().removePrefix("v").removePrefix("V")
            .substringBefore('-')          // 忽略 -beta / -rc 之类的预发布后缀
            .split('.')
            .mapNotNull { it.toIntOrNull() }
}
