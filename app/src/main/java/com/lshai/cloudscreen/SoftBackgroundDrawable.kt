package com.lshai.cloudscreen

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import java.util.Random

/** Static, low-contrast paper texture over a warm-white to sage gradient. */
internal class SoftBackgroundDrawable : Drawable() {
    private val base = Paint(Paint.DITHER_FLAG)
    private val grain = Paint().apply {
        shader = BitmapShader(texture, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }

    override fun onBoundsChange(bounds: Rect) {
        base.shader = LinearGradient(
            0f, bounds.top.toFloat(), 0f, bounds.bottom.coerceAtLeast(bounds.top + 1).toFloat(),
            intArrayOf(0xFFFAF7EF.toInt(), 0xFFF0F3EA.toInt(), 0xFFE3ECE3.toInt()),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP,
        )
    }

    override fun draw(canvas: Canvas) {
        canvas.drawRect(bounds, base)
        canvas.drawRect(bounds, grain)
    }

    override fun setAlpha(alpha: Int) {
        base.alpha = alpha
        grain.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        base.colorFilter = colorFilter
        grain.colorFilter = colorFilter
        invalidateSelf()
    }

    @Suppress("DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    companion object {
        // A single tiny tile is shared across page changes; no animation or per-frame allocation.
        private val texture by lazy {
            val random = Random(31)
            val pixels = IntArray(64 * 64) {
                (random.nextInt(5) shl 24) or if (random.nextBoolean()) 0xFFFFFF else 0
            }
            Bitmap.createBitmap(pixels, 64, 64, Bitmap.Config.ARGB_8888)
        }
    }
}
