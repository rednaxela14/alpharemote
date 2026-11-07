package org.staacks.alpharemote.ui.bracketing

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/** Drehrad: 270 Grad, logarithmisch von minValue bis maxValue (Sekunden Fokusfahrt pro Schritt). */
class DialView(context: Context) : View(context) {
    var minValue = 0.02f
    var maxValue = 3.0f
    var onChange: ((Float) -> Unit)? = null
    private var angle = 0f // -135..135
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    val value: Float
        get() = ((minValue * (maxValue / minValue).pow((angle + 135f) / 270f)) * 100f).roundToInt() / 100f

    init { setAngle(-20f) }

    private fun setAngle(a: Float) {
        angle = a.coerceIn(-135f, 135f)
        invalidate()
        onChange?.invoke(value)
    }

    override fun onMeasure(w: Int, h: Int) {
        val s = MeasureSpec.getSize(w).coerceAtMost((300 * resources.displayMetrics.density).toInt())
        setMeasuredDimension(s, s)
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f; val r = width / 2f - 8f
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 6f; paint.color = 0xFF555555.toInt()
        for (i in 0..18) {
            val a = (-135 + i * 15) * PI / 180
            c.drawLine(cx + sin(a).toFloat() * r * 0.88f, cy - cos(a).toFloat() * r * 0.88f,
                cx + sin(a).toFloat() * r, cy - cos(a).toFloat() * r, paint)
        }
        paint.style = Paint.Style.FILL; paint.color = 0xFF2A2D33.toInt()
        c.drawCircle(cx, cy, r * 0.72f, paint)
        val a = angle * PI / 180
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 14f; paint.color = 0xFFFF7A1A.toInt()
        paint.strokeCap = Paint.Cap.ROUND
        c.drawLine(cx + sin(a).toFloat() * r * 0.35f, cy - cos(a).toFloat() * r * 0.35f,
            cx + sin(a).toFloat() * r * 0.66f, cy - cos(a).toFloat() * r * 0.66f, paint)
        paint.style = Paint.Style.FILL; paint.color = 0xFFFFFFFF.toInt(); paint.textAlign = Paint.Align.CENTER
        paint.textSize = r * 0.28f
        c.drawText("%.2f s".format(value), cx, cy + r * 0.1f, paint)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> parent.requestDisallowInterceptTouchEvent(true)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> parent.requestDisallowInterceptTouchEvent(false)
        }
        val dx = e.x - width / 2f; val dy = e.y - height / 2f
        setAngle((atan2(dx, -dy) * 180 / PI).toFloat())
        return true
    }
}
