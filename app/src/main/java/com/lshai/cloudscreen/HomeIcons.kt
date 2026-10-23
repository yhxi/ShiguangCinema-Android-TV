package com.lshai.cloudscreen

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

/**
 * 首页入口图标：全部用 Canvas 画，不再依赖字体里的几何符号。
 *
 * 之前用的是 ▷ ▣ ✦ ⌕ ◉ ☆ ✚ ⚙ 这些符号，问题在于它们来自不同 Unicode 区块，
 * 各家字体的字面大小、笔画粗细、位置基线都不一样：真机上 `⌕`（搜索）和 `⚙`（设置）
 * 明显比 `▷`（电影）小一圈、笔画也细，摆在同一排里像是「没显示对」。
 * 有的设备还会把 `⚙` 当 emoji 渲染成彩色方块。
 *
 * 现在统一在 24×24 的坐标系里描边绘制，再按控件尺寸整体缩放 —— 八个图标
 * 笔画一致、视觉重心一致，也不再有缺字风险（这个项目之前 `⌂` 和 🔒 都踩过）。
 */
class HomeIconView(context: Context, private val kind: String) : View(context) {

    var tintColor: Int = 0xFF173B35.toInt()
        set(value) {
            field = value
            invalidate()
        }

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.WHITE
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }

    /** 24×24 设计稿里的路径，画的时候整体缩放到控件大小 */
    private val path = Path()

    override fun onDraw(canvas: Canvas) {
        stroke.color = tintColor
        fill.color = tintColor
        val size = minOf(width, height).toFloat()
        if (size <= 0f) return
        canvas.save()
        canvas.translate((width - size) / 2f, (height - size) / 2f)
        val scale = size / 24f
        canvas.scale(scale, scale)
        stroke.strokeWidth = 1.9f
        draw24(canvas)
        canvas.restore()
    }

    private fun draw24(canvas: Canvas) {
        path.reset()
        when (kind) {
            // 电影：播放三角
            "movie" -> {
                path.moveTo(6.5f, 4.5f)
                path.lineTo(19.5f, 12f)
                path.lineTo(6.5f, 19.5f)
                path.close()
                canvas.drawPath(path, stroke)
            }
            // 剧集：屏幕 + 里面一个实心小三角
            "tv" -> {
                canvas.drawRoundRect(3.5f, 5f, 20.5f, 19f, 2.6f, 2.6f, stroke)
                path.reset()
                path.moveTo(10f, 9f)
                path.lineTo(15.5f, 12f)
                path.lineTo(10f, 15f)
                path.close()
                canvas.drawPath(path, fill)
            }
            // 综艺：四角星
            "show" -> {
                path.moveTo(12f, 3.2f)
                path.quadTo(13.4f, 10.6f, 20.8f, 12f)
                path.quadTo(13.4f, 13.4f, 12f, 20.8f)
                path.quadTo(10.6f, 13.4f, 3.2f, 12f)
                path.quadTo(10.6f, 10.6f, 12f, 3.2f)
                path.close()
                canvas.drawPath(path, stroke)
            }
            // 搜索：放大镜
            "search" -> {
                canvas.drawCircle(10.5f, 10.5f, 6.3f, stroke)
                canvas.drawLine(15.2f, 15.2f, 20f, 20f, stroke)
            }
            // 直播：同心圆（外圈 + 实心点）
            "live" -> {
                canvas.drawCircle(12f, 12f, 8f, stroke)
                canvas.drawCircle(12f, 12f, 2.8f, fill)
            }
            // 继续：时钟（接着上次的进度往下看）
            "history" -> {
                canvas.drawCircle(12f, 12f, 8f, stroke)
                canvas.drawLine(12f, 12f, 12f, 7.6f, stroke)
                canvas.drawLine(12f, 12f, 15.6f, 13.8f, stroke)
            }
            // 片源：加号
            "sources" -> {
                canvas.drawLine(12f, 5f, 12f, 19f, stroke)
                canvas.drawLine(5f, 12f, 19f, 12f, stroke)
            }
            // 追剧：书签
            "fav" -> {
                path.moveTo(7f, 4.5f)
                path.lineTo(17f, 4.5f)
                path.lineTo(17f, 20f)
                path.lineTo(12f, 16f)
                path.lineTo(7f, 20f)
                path.close()
                canvas.drawPath(path, stroke)
            }
            // 设置：齿轮（圆 + 八颗均匀的齿）
            "setup" -> {
                canvas.drawCircle(12f, 12f, 5.2f, stroke)
                for (i in 0 until 8) {
                    val a = Math.toRadians(i * 45.0)
                    val c = cos(a).toFloat()
                    val s = sin(a).toFloat()
                    canvas.drawLine(12 + c * 7.2f, 12 + s * 7.2f, 12 + c * 9.6f, 12 + s * 9.6f, stroke)
                }
            }
            else -> canvas.drawCircle(12f, 12f, 2.6f, fill)
        }
    }
}
