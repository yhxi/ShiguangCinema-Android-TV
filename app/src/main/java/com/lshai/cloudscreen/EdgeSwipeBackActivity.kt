package com.lshai.cloudscreen

import android.app.Activity
import android.content.res.Configuration
import android.view.MotionEvent
import kotlin.math.abs

/** 手机从屏幕左右边缘向内滑动时，复用当前页面的返回行为。 */
open class EdgeSwipeBackActivity : Activity() {
    private var startX = 0f
    private var startY = 0f
    private var edge = 0
    private var tracking = false
    private var captured = false
    private val isTelevision by lazy {
        resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION ||
            packageManager.hasSystemFeature("android.software.leanback")
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (isTelevision) return super.dispatchTouchEvent(event)
        val density = resources.displayMetrics.density
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                startY = event.y
                val width = window.decorView.width.toFloat()
                edge = when {
                    startX <= 24 * density -> 1
                    startX >= width - 24 * density -> -1
                    else -> 0
                }
                tracking = edge != 0
                captured = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> tracking = false
            MotionEvent.ACTION_MOVE -> if (tracking && !captured) {
                val dx = (event.x - startX) * edge
                val dy = abs(event.y - startY)
                if (dy > 24 * density && dy > abs(event.x - startX)) tracking = false
                else if (dx >= 56 * density && dx > dy * 1.5f) {
                    captured = true
                    val cancel = MotionEvent.obtain(event)
                    cancel.action = MotionEvent.ACTION_CANCEL
                    super.dispatchTouchEvent(cancel)
                    cancel.recycle()
                    return true
                }
            }
            MotionEvent.ACTION_UP -> {
                if (captured) {
                    captured = false
                    tracking = false
                    @Suppress("DEPRECATION")
                    onBackPressed()
                    return true
                }
                tracking = false
            }
            MotionEvent.ACTION_CANCEL -> {
                tracking = false
                if (captured) { captured = false; return true }
            }
        }
        return if (captured) true else super.dispatchTouchEvent(event)
    }
}
