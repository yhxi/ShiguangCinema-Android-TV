package com.lshai.cloudscreen

import android.view.Choreographer

/** Debug-only sampling of frame callback intervals; not a GPU frame-time measurement. */
internal class UiFrameMonitor(private val intervalNanos: Long) : Choreographer.FrameCallback {
    private var active = false
    private var previous = 0L
    private var frames = 0L
    private var slow = 0L
    private var pages = 0L

    fun start() {
        if (active) return
        active = true
        previous = 0L
        Choreographer.getInstance().postFrameCallback(this)
    }
    fun stop() {
        active = false
        Choreographer.getInstance().removeFrameCallback(this)
        previous = 0L
    }
    fun recordPage() { pages++ }
    fun reset() { frames = 0; slow = 0; pages = 0; previous = 0 }
    fun summary(): String = "页面绘制：$pages 次\n帧回调：$frames 次\n较长帧间隔：$slow 次\n\n仅供比较，长帧间隔不等同于 GPU 渲染耗时。"
    override fun doFrame(frameTimeNanos: Long) {
        if (!active) return
        if (previous != 0L) {
            val delta = frameTimeNanos - previous
            if (delta > intervalNanos * 3 / 2 && delta < 1_000_000_000L) slow++
            frames++
        }
        previous = frameTimeNanos
        Choreographer.getInstance().postFrameCallback(this)
    }
}
