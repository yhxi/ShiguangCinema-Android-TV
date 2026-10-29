package com.lshai.cloudscreen

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 极简海报图加载器（不引 Coil/Glide）。
 *
 * 四件事必须做对，否则电视盒子上会立刻出问题：
 *   1. 走站点的 /api/proxy —— 豆瓣封面有防盗链，直连 403
 *   2. inSampleSize 降采样到目标尺寸附近 —— 不降采样的话几十张原图能把老设备的内存打爆
 *   3. 在 IO 线程下载、主线程交付；ImageView 复用时要校验 URL，避免 GridView 滚动串图
 *   4. **内存 + 磁盘两级缓存，且同一张图只下载一次** —— 以前只有 48MB 内存缓存，
 *      进程一被杀就全部重下，冷启动时满屏灰格子一个一个变亮；而且 3 个下载线程各干各的，
 *      同一张海报被几个格子同时请求就重复下载几次。
 *
 * 磁盘缓存放在 cacheDir（系统空间不足时会自动清掉，不需要存储权限），
 * 上限 50MB，按 LRU（文件的 lastModified 当最后一次访问时间）淘汰。
 */
class ImageLoader(private val context: Context, api: Api) {

    private companion object {
        /** 请求宽度不超过这个值就按「小图」处理：RGB_565 + 只在列表/网格里出现 */
        const val SMALL_IMAGE_MAX_WIDTH = 400
    }

    /**
     * 换服务器时只换 BaseUrl 与 Cookie，**不重建 ImageLoader** ——
     * 重建会把内存与磁盘缓存一起丢掉，重新连接后满屏海报又要重下一遍。
     */
    @Volatile
    private var api: Api = api

    fun updateApi(newApi: Api) {
        api = newApi
    }

    // 按内存占用而非海报张数限制：竖屏双列海报分辨率更高，按张数缓存容易挤爆内存。
    // 上限再跟堆挂钩：电视盒子常见只有 192MB 堆，固定 48MB 就吃掉四分之一，容易 OOM。
    private val cacheLimitKb = minOf(48 * 1024, (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt())

    private val cache = object : LruCache<String, Bitmap>(cacheLimitKb) {
        override fun sizeOf(key: String, value: Bitmap): Int =
            (value.byteCount / 1024).coerceAtLeast(1)
    }
    // 3 个线程时，24 张海报要分 8 批发；每张即使只要 0.5 秒也要 4 秒才铺满。
    // 提到 6 个：弱网下再多会互相抢带宽，反而让单张更慢。
    private val pool = Executors.newFixedThreadPool(6)
    // 磁盘压缩单独排队，不占用下载线程；首屏后续海报无需等前一张落盘。
    private val diskPool = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var closed = false

    /** 正在下载的图：同一个 key 的后续请求排队等结果，不再各自发起下载 */
    private val inFlight = ConcurrentHashMap<String, MutableList<(Bitmap?) -> Unit>>()

    private val diskDir = File(context.cacheDir, "poster_cache")
    private val maxDiskBytes = 50L * 1024 * 1024
    private val diskLock = Any()
    private val generation = AtomicLong(0)
    private val diskBytes = AtomicLong(0)
    private val diskScanned = AtomicBoolean(false)

    fun load(url: String, reqWidth: Int, reqHeight: Int, onReady: (Bitmap?) -> Unit) {
        if (closed) return
        if (url.isEmpty()) {
            onReady(null)
            return
        }
        val requestGeneration = generation.get()
        // 内存缓存仍按尺寸分：列表要小图、详情页要大图，混用会把缓存挤爆
        val key = "$url@${reqWidth}x$reqHeight"
        cache.get(key)?.let {
            onReady(it)
            return
        }
        // 已在下载中：挂到回调列表后面，等同一批结果，避免重复请求同一张图
        synchronized(inFlight) {
            val waiting = inFlight[key]
            if (waiting != null) {
                waiting.add(onReady)
                return
            }
            inFlight[key] = mutableListOf(onReady)
        }
        pool.execute {
            val cached = readFromDisk(url, reqWidth, reqHeight)
            val bmp = cached ?: download(url, reqWidth, reqHeight)
            // 先交给界面显示，再在后台写磁盘。JPEG 压缩不应挡住首张海报出现。
            main.post { if (!closed && generation.get() == requestGeneration) deliver(key, bmp) }
            if (cached == null && bmp != null && !closed) {
                runCatching { diskPool.execute { synchronized(diskLock) { if (generation.get() == requestGeneration) writeToDisk(url, bmp, reqWidth) } } }
            }
        }
    }

    fun close() {
        closed = true
        pool.shutdownNow()
        diskPool.shutdown()
        synchronized(inFlight) { inFlight.clear() }
    }

    private fun deliver(key: String, bmp: Bitmap?) {
        if (bmp != null) cache.put(key, bmp)
        val waiting = synchronized(inFlight) { inFlight.remove(key) }
        waiting?.forEach { it(bmp) }
    }

    private fun download(url: String, reqWidth: Int, reqHeight: Int): Bitmap? {
        val bytes = api.imageBytes(url) ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleFor(bounds.outWidth, bounds.outHeight, reqWidth, reqHeight)
            inPreferredConfig = configFor(reqWidth)
        }
        return try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        } catch (e: OutOfMemoryError) {
            null
        }
    }

    /** 2 的幂降采样：两个方向都不能小于请求值 */
    private fun sampleFor(srcW: Int, srcH: Int, reqW: Int, reqH: Int): Int {
        var sample = 1
        while (srcW / (sample * 2) >= reqW && srcH / (sample * 2) >= reqH) {
            sample *= 2
        }
        return sample
    }

    /**
     * 列表/结果页的小海报用 RGB_565：海报是照片，肉眼差别很小，内存直接省一半，
     * 老盒子（堆只有 192MB）上少一半 GC 抖动。详情页的大封面仍用 ARGB_8888。
     */
    private fun configFor(reqWidth: Int): Bitmap.Config =
        if (reqWidth <= SMALL_IMAGE_MAX_WIDTH) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888

    /* ------------------------------------------------------------ 磁盘缓存 */

    /**
     * 磁盘上存的是**已经降采样过**的位图：下次进来直接解码小图，不用重新下载、也不用再缩一遍。
     * 用 JPEG(85) 而不是 PNG —— 海报是照片类内容，PNG 体积要大三四倍。
     *
     * 文件名只按 URL 算，**不带尺寸**：同一张海报在列表里是 240px、详情页要 600px，
     * 按尺寸分目录会留下两三份副本，50MB 的缓存实际只装得下一半的片子。
     * 读取时按本次请求再降采样（见 [readFromDisk]）。
     */
    private fun diskFile(url: String): File = File(diskDir, sha1(url) + ".img")

    /**
     * 已经确认「原图本身就这么大」的 URL。
     * 没有这个标记的话，每次进详情页都会因为「磁盘上那张比要的小」重新下一遍，
     * 而下回来的还是同样大小，永远收敛不了。
     */
    private val diskSmall = java.util.Collections.synchronizedSet(HashSet<String>())

    private fun readFromDisk(url: String, reqWidth: Int, reqHeight: Int): Bitmap? {
        val f = diskFile(url)
        if (!f.isFile || f.length() == 0L) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            // 文件坏了（写一半被杀、空间不足）就删掉，下次重新下载
            runCatching { f.delete() }
            return null
        }
        // 磁盘上是小图、这次要的是大图（先看了列表再进详情）：当作没有，重新下一张大的。
        // 但只重下一次 —— 原图就这么大的话，再下也没用。
        if (bounds.outWidth < reqWidth * 9 / 10 && url !in diskSmall) return null
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleFor(bounds.outWidth, bounds.outHeight, reqWidth, reqHeight)
            inPreferredConfig = configFor(reqWidth)
        }
        val bmp = try {
            BitmapFactory.decodeFile(f.absolutePath, opts)
        } catch (e: OutOfMemoryError) {
            null
        }
        if (bmp == null) {
            runCatching { f.delete() }
            return null
        }
        // 刷新访问时间，让 LRU 淘汰真正按"最近用过的"来
        runCatching { f.setLastModified(System.currentTimeMillis()) }
        return bmp
    }

    private fun writeToDisk(url: String, bmp: Bitmap, reqWidth: Int) {
        runCatching {
            if (!diskDir.exists()) diskDir.mkdirs()
            ensureDiskScanned()
            val target = diskFile(url)
            // 覆盖前先记下旧文件的宽度：新图没有更大，说明原图已经到顶
            val oldWidth = if (target.isFile) storedWidth(target) else 0
            if (oldWidth >= bmp.width && oldWidth > 0) {
                if (bmp.width < reqWidth * 9 / 10) diskSmall.add(url)
                return
            }
            val oldLen = if (target.isFile) target.length() else 0L
            val tmp = File(diskDir, target.name + ".tmp")
            val ok = tmp.outputStream().use { out ->
                bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }
            if (!ok) {
                tmp.delete()
                return
            }
            // 先写临时文件再改名：中途被杀也不会留下半张图
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) {
                tmp.delete()
                return
            }
            // 覆盖写要扣掉旧文件的体积，否则统计只增不减，缓存会被提前判满
            diskBytes.addAndGet(target.length() - oldLen)
            // 新图还是不够这次要的尺寸（原图就这么大），或者根本没比旧的大：
            // 记下来，以后别再为同一张图反复重下
            if (bmp.width <= oldWidth || bmp.width < reqWidth * 9 / 10) diskSmall.add(url)
            trimDiskIfNeeded()
        }
    }

    private fun storedWidth(f: File): Int {
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, b)
        return b.outWidth
    }

    /** 首次写入前扫一遍目录，得到当前占用；只扫一次 */
    private fun ensureDiskScanned() {
        if (diskScanned.compareAndSet(false, true)) {
            diskBytes.set(diskDir.listFiles()?.sumOf { it.length() } ?: 0L)
        }
    }

    /** 超过上限就从最久没访问的开始删，删到 80% 以下，避免每写一张都触发一轮淘汰 */
    private fun trimDiskIfNeeded() {
        if (diskBytes.get() <= maxDiskBytes) return
        val files = diskDir.listFiles()?.filter { it.isFile } ?: return
        val sorted = files.sortedBy { it.lastModified() }
        for (f in sorted) {
            if (diskBytes.get() <= maxDiskBytes * 8 / 10) break
            val len = f.length()
            if (runCatching { f.delete() }.getOrDefault(false)) diskBytes.addAndGet(-len)
        }
    }

    /* ---------------------------------------------------------- 缓存统计 */

    /** 清空内存与磁盘缓存，返回内存里清掉的张数（磁盘按文件数计入日志） */
    fun clearCache(): Int {
        generation.incrementAndGet()
        synchronized(inFlight) { inFlight.clear() }
        val n = cache.snapshot().size
        cache.evictAll()
        synchronized(diskLock) {
            runCatching { diskDir.deleteRecursively() }
            diskBytes.set(0)
            diskScanned.set(true)
            diskSmall.clear()
        }
        return n
    }

    fun cacheSize(): Int = cache.snapshot().size

    /** 磁盘缓存占用（字节），用于设置页展示 */
    fun diskSizeBytes(): Long {
        ensureDiskScanned()
        return diskBytes.get()
    }

    private fun sha1(text: String): String {
        val md = MessageDigest.getInstance("SHA-1")
        val bytes = md.digest(text.toByteArray())
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) sb.append(String.format("%02x", b))
        return sb.toString()
    }
}
