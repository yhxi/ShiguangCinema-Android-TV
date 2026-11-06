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
    var title: String = ""
        set(value) { field = value; invalidate() }
    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val backgrounds = intArrayOf(0xFFD9F1E7.toInt(), 0xFFE0EDF8.toInt(), 0xFFFFEACF.toInt(), 0xFFF0E4F5.toInt())
        val hills = intArrayOf(0xFFA3D7BF.toInt(), 0xFFB0CFDF.toInt(), 0xFFE8CBA3.toInt(), 0xFFD0B9DE.toInt())
        val palette = (title.hashCode() and Int.MAX_VALUE) % backgrounds.size
        canvas.drawColor(backgrounds[palette])
        paint.color = Color.rgb(255, 218, 119)
        canvas.drawCircle(w * .78f, h * .26f, h * .13f, paint)
        paint.color = hills[palette]
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
        if (title.isNotBlank()) {
            val label = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFF173B35.toInt(); textSize = h * .12f
                typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }
            val name = android.text.TextUtils.ellipsize(title, label, w * .9f, android.text.TextUtils.TruncateAt.END).toString()
            canvas.drawText(name, w * .5f, h * .93f, label)
        }
    }
}
