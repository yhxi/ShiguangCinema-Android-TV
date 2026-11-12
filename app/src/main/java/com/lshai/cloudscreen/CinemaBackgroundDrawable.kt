package com.lshai.cloudscreen

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable

/** Restrained forest-green backdrop shared by the non-children screens. */
internal class CinemaBackgroundDrawable : Drawable() {
    private val paint = Paint(Paint.DITHER_FLAG)
    override fun onBoundsChange(bounds: Rect) {
        paint.shader = LinearGradient(0f, bounds.top.toFloat(), bounds.right.toFloat(), bounds.bottom.coerceAtLeast(bounds.top + 1).toFloat(),
            intArrayOf(0xFF102622.toInt(), 0xFF0A1D1A.toInt(), 0xFF152B25.toInt()), null, Shader.TileMode.CLAMP)
    }
    override fun draw(canvas: Canvas) { canvas.drawRect(bounds, paint) }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter; invalidateSelf() }
    @Suppress("DEPRECATION") override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
