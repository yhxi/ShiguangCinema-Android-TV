package com.lshai.cloudscreen

import android.content.Context
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.database.StandaloneDatabaseProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 播放磁盘缓存（HLS 分片级）。
 *
 * 为什么需要它：ExoPlayer 的内存缓冲上限只有几十秒，看剧时每次往回拖、或者退出再进，
 * 都要重新下载分片。这里把分片落到磁盘，看过的部分再次播放直接命中缓存。
 *
 * 约束：SimpleCache 在一个进程里只能有一个实例（内部有文件锁），所以做成单例。
 */
object PlaybackCache {

    /** 默认上限 2GB；实际值可在设置里改（Prefs.cacheLimitMb） */
    private const val DEFAULT_MAX_MB = 2048

    /** 缓存占用的复用窗口：30 秒内重复打开设置页不必重新遍历目录 */
    private const val SIZE_TTL_MS = 30_000L

    @Volatile
    private var instance: SimpleCache? = null

    /** 上次算到的占用；-1 = 未知 */
    @Volatile
    private var cachedSize = -1L
    @Volatile
    private var cachedAtMs = 0L

    /** 存活中的播放器个数。>0 时绝不能 release 缓存实例，否则播放器下次读分片会崩。 */
    private val activePlayers = java.util.concurrent.atomic.AtomicInteger(0)

    fun playerOpened() {
        activePlayers.incrementAndGet()
    }

    fun playerClosed() {
        activePlayers.decrementAndGet()
    }

    fun isInUse(): Boolean = activePlayers.get() > 0

    fun get(context: Context): SimpleCache {
        instance?.let { return it }
        synchronized(this) {
            instance?.let { return it }
            val dir = File(context.cacheDir, "media_cache")
            if (!dir.exists()) dir.mkdirs()
            val limitMb = runCatching { Prefs(context).cacheLimitMb }.getOrDefault(DEFAULT_MAX_MB)
            val cache = SimpleCache(
                dir,
                LeastRecentlyUsedCacheEvictor(limitMb * 1024L * 1024L),
                StandaloneDatabaseProvider(context.applicationContext),
            )
            instance = cache
            return cache
        }
    }

    /** 上限变更后重建缓存实例（淘汰策略只在创建时生效） */
    fun recreate(context: Context) {
        synchronized(this) {
            runCatching { instance?.release() }
            instance = null
        }
        get(context)
    }

    /**
     * 上次算到的占用（字节）；-1 表示还没算过。
     *
     * 界面上先用它垫一下，真正遍历目录放到 [sizeBytesAsync] 里做 ——
     * 上限 2GB 的 HLS 缓存可能有几万个分片文件，同步遍历会把主线程卡住。
     */
    fun knownSizeBytes(): Long = cachedSize

    /**
     * 后台重算缓存占用。30 秒内复用上次结果，避免反复遍历同一个目录。
     * 只读文件长度、不读内容，正常情况下几百毫秒内完成。
     */
    suspend fun sizeBytesAsync(context: Context): Long = withContext(Dispatchers.IO) {
        val now = android.os.SystemClock.elapsedRealtime()
        val known = cachedSize
        if (known >= 0 && now - cachedAtMs < SIZE_TTL_MS) return@withContext known
        val dir = File(context.cacheDir, "media_cache")
        val bytes = if (!dir.exists()) {
            0L
        } else {
            runCatching { dir.walkTopDown().filter { it.isFile }.sumOf { it.length() } }
                .getOrDefault(0L)
        }
        cachedSize = bytes
        cachedAtMs = now
        bytes
    }

    /**
     * 清空缓存：先关掉实例（否则目录被占用），再删目录。
     *
     * 有播放器活着时**不清**：SimpleCache 被 release 之后，播放器再读分片会直接抛异常。
     * 返回 false 表示这次跳过了，由调用方提示用户稍后再试。
     */
    fun clear(context: Context): Boolean {
        synchronized(this) {
            if (activePlayers.get() > 0) return false
            runCatching {
                instance?.release()
            }
            instance = null
            val dir = File(context.cacheDir, "media_cache")
            runCatching { dir.deleteRecursively() }
            cachedSize = 0L
            cachedAtMs = android.os.SystemClock.elapsedRealtime()
            return true
        }
    }
}
