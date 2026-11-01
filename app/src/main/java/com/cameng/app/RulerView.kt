package com.cameng.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.PointF
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Régua arrastável sobre a imagem da câmera.
 * - Arraste uma ponta para mudar tamanho/ângulo (encaixa na horizontal/vertical).
 * - Arraste o meio para mover a régua inteira.
 */
class RulerView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class Mode { OFF, VIRTUAL, REAL_AUTO, REAL_CALIBRATED }

    var mode: Mode = Mode.OFF
        set(v) { field = v; invalidate() }

    /** Proporção largura/altura da imagem dentro da tela (foto 3:4 em pé). */
    var contentAspect: Float = 3f / 4f
        set(v) { field = v; requestLayout(); invalidate() }

    /** mm por pixel da tela estimado pela distância de foco. */
    var autoMmPerPx: Float? = null
        set(v) { field = v; if (mode == Mode.REAL_AUTO) invalidate() }

    /** mm por pixel da tela obtido por calibração com objeto de referência. */
    var calibratedMmPerPx: Float? = null
        set(v) { field = v; if (mode == Mode.REAL_CALIBRATED) invalidate() }

    var divisions: Int = 10
        set(v) { field = v; invalidate() }

    val start = PointF()
    val end = PointF()
    private var placed = false
    private val dp = resources.displayMetrics.density

    private enum class Target { NONE, START, END, WHOLE }
    private var target = Target.NONE
    private var lastX = 0f
    private var lastY = 0f

    fun currentMmPerPx(): Float? = when (mode) {
        Mode.REAL_AUTO -> autoMmPerPx
        Mode.REAL_CALIBRATED -> calibratedMmPerPx
        else -> null
    }

    fun lengthPx(): Float = hypot(end.x - start.x, end.y - start.y)

    fun contentRect(): RectF = contentRect(width.toFloat(), height.toFloat(), contentAspect)

    companion object {
        /** Área onde a imagem aparece com scaleType FIT_CENTER. */
        fun contentRect(w: Float, h: Float, aspect: Float): RectF {
            return if (w / h > aspect) {
                val cw = h * aspect
                RectF((w - cw) / 2f, 0f, (w + cw) / 2f, h)
            } else {
                val ch = w / aspect
                RectF(0f, (h - ch) / 2f, w, (h + ch) / 2f)
            }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (!placed && w > 0 && h > 0) {
            val r = contentRect()
            start.set(r.left + r.width() * 0.2f, r.top + r.height() * 0.65f)
            end.set(r.left + r.width() * 0.8f, r.top + r.height() * 0.65f)
            placed = true
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        RulerRenderer.draw(
            canvas, start.x, start.y, end.x, end.y, mode, currentMmPerPx(), divisions, dp,
            estimated = mode == Mode.REAL_AUTO, drawHandles = true
        )
    }

    private fun distToSegment(px: Float, py: Float): Float {
        val dx = end.x - start.x
        val dy = end.y - start.y
        val l2 = dx * dx + dy * dy
        if (l2 == 0f) return hypot(px - start.x, py - start.y)
        val t = (((px - start.x) * dx + (py - start.y) * dy) / l2).coerceIn(0f, 1f)
        return hypot(px - (start.x + t * dx), py - (start.y + t * dy))
    }

    private fun clamp(p: PointF) {
        val r = contentRect()
        p.x = p.x.coerceIn(r.left, r.right)
        p.y = p.y.coerceIn(r.top, r.bottom)
    }

    /** Encaixa a ponta móvel na horizontal/vertical quando estiver quase alinhada. */
    private fun snap(moving: PointF, fixed: PointF) {
        val dx = moving.x - fixed.x
        val dy = moving.y - fixed.y
        val len = hypot(dx, dy)
        val ang = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble()))
        for (target in doubleArrayOf(-180.0, -90.0, 0.0, 90.0, 180.0)) {
            if (abs(ang - target) < 3.0) {
                val rad = Math.toRadians(target)
                moving.x = fixed.x + (len * cos(rad)).toFloat()
                moving.y = fixed.y + (len * sin(rad)).toFloat()
                return
            }
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (mode == Mode.OFF) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val tol = 32f * dp
                target = when {
                    hypot(e.x - start.x, e.y - start.y) < tol -> Target.START
                    hypot(e.x - end.x, e.y - end.y) < tol -> Target.END
                    distToSegment(e.x, e.y) < 24f * dp -> Target.WHOLE
                    else -> Target.NONE
                }
                if (target == Target.NONE) return false
                lastX = e.x; lastY = e.y
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.x - lastX
                val dy = e.y - lastY
                lastX = e.x; lastY = e.y
                when (target) {
                    Target.START -> { start.offset(dx, dy); clamp(start); snap(start, end); clamp(start) }
                    Target.END -> { end.offset(dx, dy); clamp(end); snap(end, start); clamp(end) }
                    Target.WHOLE -> {
                        val r = contentRect()
                        val mdx = dx.coerceIn(r.left - minOf(start.x, end.x), r.right - maxOf(start.x, end.x))
                        val mdy = dy.coerceIn(r.top - minOf(start.y, end.y), r.bottom - maxOf(start.y, end.y))
                        start.offset(mdx, mdy); end.offset(mdx, mdy)
                    }
                    Target.NONE -> return false
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                target = Target.NONE
                return true
            }
        }
        return target != Target.NONE
    }

    /** Fotografia do estado atual da régua, para queimar na foto. */
    fun snapshot(): RulerSnapshot? {
        if (mode == Mode.OFF) return null
        return RulerSnapshot(
            sx = start.x, sy = start.y, ex = end.x, ey = end.y,
            mode = mode, mmPerPx = currentMmPerPx(), divisions = divisions,
            viewW = width.toFloat(), viewH = height.toFloat(), unit = dp,
        )
    }
}

data class RulerSnapshot(
    val sx: Float, val sy: Float, val ex: Float, val ey: Float,
    val mode: RulerView.Mode, val mmPerPx: Float?, val divisions: Int,
    val viewW: Float, val viewH: Float, val unit: Float,
)
