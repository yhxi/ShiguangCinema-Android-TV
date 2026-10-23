package com.lshai.cloudscreen

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.widget.ProgressBar

/**
 * 播放进度条（按参考图重做）。
 *
 * 视觉：细轨道 + 青绿已播段 + 白色圆形拖动点，整体圆角。
 *   · 未播放：浅灰 #DCDCDC
 *   · 已播放：青绿 #2DD4BF
 *   · 拖动点：白色实心圆（半径 5dp），保证手机上手指好按
 *
 * 为什么不用 drawable：
 *   原来靠 layer-list 的 clip 画进度，画不出「圆形拖动点」，也没法让轨道两端真正圆头
 *   （clip 会把圆角裁掉一半）。改成自绘后一次到位，片头/片尾刻度也能叠在上面。
 *
 * 交互：控件本身不抢焦点，手机拖动由 PlayerActivity 的 OnTouchListener 处理，
 *      电视遥控的左右键在 Activity 层统一做 ±10s（长按 ±15s），互不冲突。
 */
class MarkedSeekBar(context: Context) : ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal) {

    /** 比例 0~1；负数表示没有标记 */
    var introFraction: Float = -1f
        set(v) {
            if (field == v) return
            field = v
            invalidate()
        }

    var outroFraction: Float = -1f
        set(v) {
            if (field == v) return
            field = v
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val barHeight = 3f * density
    private val thumbRadius = 5f * density

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFDCDCDC.toInt()
        strokeWidth = barHeight
        strokeCap = Paint.Cap.ROUND
        style = Paint.Style.STROKE
    }
    private val playedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF2DD4BF.toInt()
        strokeWidth = barHeight
        strokeCap = Paint.Cap.ROUND
        style = Paint.Style.STROKE
    }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.FILL
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 2f * density
        strokeCap = Paint.Cap.ROUND
    }

    /** 进度圆点实际可走的范围（两端各留出圆点半径，避免画到屏幕外） */
    private val barLeft: Float get() = paddingLeft + thumbRadius
    private val barRight: Float get() = (width - paddingRight - thumbRadius).coerceAtLeast(barLeft)

    private fun progressFraction(): Float {
        val m = max
        if (m <= 0) return 0f
        return (progress.toFloat() / m).coerceIn(0f, 1f)
    }

    /** 把触摸 x 换算成 0~1（轨道两端内缩了圆点半径，这里对应扣掉） */
    fun fractionAt(x: Float): Float {
        val l = barLeft
        val r = barRight
        if (r <= l) return 0f
        return ((x - l) / (r - l)).coerceIn(0f, 1f)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val cy = height / 2f
        val l = barLeft
        val r = barRight
        if (r <= l) return
        val x = l + (r - l) * progressFraction()

        // 未播放轨道（整条）
        canvas.drawLine(l, cy, r, cy, trackPaint)
        // 已播放（从左边到当前进度，圆头收尾）
        if (x > l) canvas.drawLine(l, cy, x, cy, playedPaint)

        // 片头 / 片尾刻度：不影响主进度，只是标一下位置
        if (introFraction in 0f..1f) {
            tickPaint.color = 0xE6FFFFFF.toInt()
            val tx = l + (r - l) * introFraction
            canvas.drawLine(tx, cy - barHeight, tx, cy + barHeight, tickPaint)
        }
        if (outroFraction in 0f..1f) {
            tickPaint.color = 0xFFEF9F27.toInt()
            val tx = l + (r - l) * outroFraction
            canvas.drawLine(tx, cy - barHeight, tx, cy + barHeight, tickPaint)
        }

        // 白色拖动点
        canvas.drawCircle(x, cy, thumbRadius, thumbPaint)
    }
}
