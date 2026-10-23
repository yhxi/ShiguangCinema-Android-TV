package com.lshai.cloudscreen

import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃日志落盘。
 *
 * 电视盒子出问题，用户能给的只有"它闪退了"，没有任何线索。
 * 这里在进程挂掉前把堆栈写到 `filesDir/crash.log`（最多保留最近 3 次、总量 64KB），
 * 设置页可以看、可以复制发出来。
 *
 * 注意：只记录，不上传。装的时候链住原来的 handler，保证系统的崩溃流程照常走完。
 */
object CrashLog {

    private const val FILE_NAME = "crash.log"
    private const val MAX_BYTES = 64 * 1024
    private const val MAX_ENTRIES = 3

    @Volatile
    private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(app, thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun write(context: Context, thread: Thread, error: Throwable) {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val entry = buildString {
            append("==== ").append(stamp).append(" ====\n")
            append("v").append(BuildConfig.VERSION_NAME)
                .append(" (").append(BuildConfig.VERSION_CODE).append(")")
                .append("  Android ").append(Build.VERSION.RELEASE)
                .append(" / API ").append(Build.VERSION.SDK_INT)
                .append("\n")
            append("device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append("\n")
            append("thread: ").append(thread.name).append("\n")
            append(android.util.Log.getStackTraceString(error)).append("\n\n")
        }
        val f = File(context.filesDir, FILE_NAME)
        // 只保留最近几次，防止日志本身把存储占满
        val old = if (f.isFile) runCatching { f.readText() }.getOrDefault("") else ""
        val merged = entry + old
        val capped = merged
            .split(Regex("(?m)^==== "))
            .filter { it.isNotBlank() }
            .take(MAX_ENTRIES)
            .joinToString(separator = "") { "==== $it" }
        f.writeText(if (capped.length > MAX_BYTES) capped.take(MAX_BYTES) else capped)
    }

    fun read(context: Context): String {
        val f = File(context.filesDir, FILE_NAME)
        if (!f.isFile) return ""
        return runCatching { f.readText() }.getOrDefault("")
    }

    fun clear(context: Context) {
        runCatching { File(context.filesDir, FILE_NAME).delete() }
    }
}
