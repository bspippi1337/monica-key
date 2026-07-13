package no.blckswan.monicakey

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class RomanticBackdropView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        paint.shader = LinearGradient(
            0f,
            0f,
            width.toFloat(),
            height.toFloat(),
            intArrayOf(Color.rgb(7, 12, 10), Color.rgb(15, 35, 25), Color.rgb(7, 10, 9)),
            floatArrayOf(0f, 0.48f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.shader = null

        drawSoftGlow(canvas, width * 0.5f, height * 0.23f, width * 0.36f)
        drawDaisy(canvas, width * 0.06f, height * 0.08f, width * 0.17f, -0.2f)
        drawDaisy(canvas, width * 0.94f, height * 0.13f, width * 0.15f, 0.25f)
        drawDaisy(canvas, width * 0.03f, height * 0.78f, width * 0.13f, 0.1f)
        drawDaisy(canvas, width * 0.94f, height * 0.88f, width * 0.18f, -0.1f)
        drawHeartKey(canvas, width * 0.5f, height * 0.27f, width * 0.15f)
        drawRoute(canvas)
    }

    private fun drawSoftGlow(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        paint.shader = android.graphics.RadialGradient(
            cx,
            cy,
            radius,
            intArrayOf(Color.argb(95, 255, 180, 130), Color.TRANSPARENT),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, radius, paint)
        paint.shader = null
    }

    private fun drawDaisy(canvas: Canvas, cx: Float, cy: Float, radius: Float, rotation: Float) {
        paint.style = Paint.Style.FILL
        repeat(14) { i ->
            val angle = rotation + i * (2.0 * PI / 14.0)
            val px = cx + cos(angle).toFloat() * radius * 0.62f
            val py = cy + sin(angle).toFloat() * radius * 0.62f
            canvas.save()
            canvas.rotate(Math.toDegrees(angle).toFloat() + 90f, px, py)
            paint.color = if (i % 3 == 0) Color.rgb(255, 245, 225) else Color.rgb(255, 253, 244)
            canvas.drawOval(
                px - radius * 0.17f,
                py - radius * 0.46f,
                px + radius * 0.17f,
                py + radius * 0.18f,
                paint
            )
            canvas.restore()
        }
        paint.color = Color.rgb(229, 175, 46)
        canvas.drawCircle(cx, cy, radius * 0.29f, paint)
        paint.color = Color.argb(100, 80, 45, 8)
        repeat(18) { i ->
            val angle = i * (2.0 * PI / 18.0)
            canvas.drawCircle(
                cx + cos(angle).toFloat() * radius * 0.19f,
                cy + sin(angle).toFloat() * radius * 0.19f,
                radius * 0.025f,
                paint
            )
        }
    }

    private fun drawHeartKey(canvas: Canvas, cx: Float, cy: Float, size: Float) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = size * 0.065f
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        paint.color = Color.rgb(243, 199, 118)
        path.reset()
        path.moveTo(cx, cy + size * 0.22f)
        path.cubicTo(cx - size, cy - size * 0.45f, cx - size * 0.48f, cy - size, cx, cy - size * 0.45f)
        path.cubicTo(cx + size * 0.48f, cy - size, cx + size, cy - size * 0.45f, cx, cy + size * 0.22f)
        canvas.drawPath(path, paint)
        canvas.drawLine(cx, cy + size * 0.22f, cx, cy + size * 1.35f, paint)
        canvas.drawLine(cx, cy + size * 1.08f, cx + size * 0.42f, cy + size * 1.08f, paint)
        canvas.drawLine(cx + size * 0.30f, cy + size * 1.08f, cx + size * 0.30f, cy + size * 1.32f, paint)
        paint.style = Paint.Style.FILL
        canvas.drawCircle(cx, cy + size * 1.35f, size * 0.07f, paint)
    }

    private fun drawRoute(canvas: Canvas) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f * resources.displayMetrics.density
        paint.color = Color.argb(110, 255, 159, 173)
        paint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(8f, 12f), 0f)
        path.reset()
        path.moveTo(width * 0.15f, height * 0.56f)
        path.cubicTo(width * 0.30f, height * 0.49f, width * 0.39f, height * 0.64f, width * 0.50f, height * 0.58f)
        path.cubicTo(width * 0.63f, height * 0.51f, width * 0.75f, height * 0.65f, width * 0.88f, height * 0.55f)
        canvas.drawPath(path, paint)
        paint.pathEffect = null
        paint.style = Paint.Style.FILL
        drawMiniHeart(canvas, width * 0.50f, height * 0.58f, width * 0.025f)
    }

    private fun drawMiniHeart(canvas: Canvas, cx: Float, cy: Float, size: Float) {
        paint.color = Color.rgb(255, 159, 173)
        path.reset()
        path.moveTo(cx, cy + size)
        path.cubicTo(cx - size * 1.8f, cy - size * 0.2f, cx - size, cy - size * 1.7f, cx, cy - size * 0.7f)
        path.cubicTo(cx + size, cy - size * 1.7f, cx + size * 1.8f, cy - size * 0.2f, cx, cy + size)
        canvas.drawPath(path, paint)
    }
}
