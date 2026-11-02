package com.lshai.cloudscreen

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/** Friendly built-in illustration shown while a real poster is unavailable. */
internal class ChildrenArtworkView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        canvas.drawColor(Color.rgb(217, 241, 231))
        paint.color = Color.rgb(255, 218, 119)
        canvas.drawCircle(w * .78f, h * .26f, h * .13f, paint)
        paint.color = Color.rgb(163, 215, 191)
        val hill = Path().apply {
            moveTo(0f, h); lineTo(0f, h * .74f)
            quadTo(w * .25f, h * .35f, w * .52f, h * .79f)
            quadTo(w * .75f, h * .47f, w, h * .75f)
            lineTo(w, h); close()
        }
        canvas.drawPath(hill, paint)
        paint.color = Color.rgb(52, 151, 137)
        canvas.drawCircle(w * .34f, h * .48f, h * .18f, paint)
        paint.color = Color.WHITE
        canvas.drawCircle(w * .29f, h * .45f, h * .025f, paint)
        canvas.drawCircle(w * .39f, h * .45f, h * .025f, paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = (h * .018f).coerceAtLeast(1f)
        canvas.drawArc(w * .29f, h * .45f, w * .39f, h * .56f, 10f, 160f, false, paint)
        paint.style = Paint.Style.FILL
    }
}
