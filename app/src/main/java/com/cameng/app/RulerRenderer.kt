package com.cameng.app

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.round

/**
 * Desenha a régua. É usado tanto na tela quanto ao "queimar" a régua na foto,
 * garantindo que a foto fique igual ao que foi visto.
 */
object RulerRenderer {
    private val PT_BR = Locale("pt", "BR")
    private val MINOR_STEPS_MM = floatArrayOf(1f, 2f, 5f, 10f, 20f, 50f, 100f, 200f, 500f, 1000f, 2000f, 5000f)

    fun formatLength(mm: Float): String = when {
        mm < 10f -> String.format(PT_BR, "%.1f mm", mm)
        mm < 1000f -> String.format(PT_BR, "%.1f cm", mm / 10f)
        else -> String.format(PT_BR, "%.2f m", mm / 1000f)
    }

    private fun cmLabel(mm: Float): String {
        val cm = mm / 10f
        return if (abs(cm - round(cm)) < 1e-3) round(cm).toInt().toString()
        else String.format(PT_BR, "%.1f", cm)
    }

    private fun isMultiple(a: Float, b: Float): Boolean {
        val r = a / b
        return abs(r - round(r)) < 1e-3
    }

    /**
     * @param unit tamanho de 1dp em pixels da tela (as espessuras e textos escalam com ele)
     * @param mmPerPx milímetros por pixel (null = sem escala real)
     */
    fun draw(
        c: Canvas,
        sx: Float, sy: Float, ex: Float, ey: Float,
        mode: RulerView.Mode,
        mmPerPx: Float?,
        divisions: Int,
        unit: Float,
        estimated: Boolean,
        drawHandles: Boolean,
    ) {
        if (mode == RulerView.Mode.OFF) return
        var x0 = sx; var y0 = sy; var x1 = ex; var y1 = ey
        var angle = Math.toDegrees(atan2((y1 - y0).toDouble(), (x1 - x0).toDouble())).toFloat()
        if (angle > 90f || angle < -90f) { // mantém os números sempre "de pé"
            x0 = ex; y0 = ey; x1 = sx; y1 = sy
            angle = Math.toDegrees(atan2((y1 - y0).toDouble(), (x1 - x0).toDouble())).toFloat()
        }
        val len = hypot(x1 - x0, y1 - y0)
        if (len < 2f) return

        val h = 34f * unit
        val top = -h / 2f
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(220, 255, 236, 140) }
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 1.5f * unit
        }
        val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; strokeWidth = 1.2f * unit }
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK; textSize = 10.5f * unit; textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        val labelY = h / 2f - 4f * unit

        c.save()
        c.translate(x0, y0)
        c.rotate(angle)
        c.drawRect(0f, top, len, -top, bg)
        c.drawRect(0f, top, len, -top, border)

        val isReal = mode == RulerView.Mode.REAL_AUTO || mode == RulerView.Mode.REAL_CALIBRATED
        if (!isReal) {
            val n = divisions.coerceIn(1, 100)
            val sub = 2
            for (i in 0..n * sub) {
                val x = len * i / (n * sub)
                val major = i % sub == 0
                c.drawLine(x, top, x, top + h * (if (major) 0.55f else 0.3f), tick)
                if (major && (n <= 20 || (i / sub) % 5 == 0)) c.drawText("${i / sub}", x, labelY, label)
            }
        } else if (mmPerPx == null || mmPerPx <= 0f) {
            c.drawText("sem escala", len / 2f, 4f * unit, label)
        } else {
            val pxPerMm = 1f / mmPerPx
            val minor = MINOR_STEPS_MM.firstOrNull { it * pxPerMm >= 5f * unit } ?: MINOR_STEPS_MM.last()
            val lead = minor / 10f.pow(floor(log10(minor)))
            val major = minor * (if (abs(lead - 2f) < 0.01f) 5f else 10f)
            val half = if (abs(lead - 1f) < 0.01f) minor * 5f else -1f
            val totalMm = len * mmPerPx
            var k = 0
            while (k < 5000) {
                val mm = k * minor
                if (mm > totalMm + 1e-3f) break
                val x = mm * pxPerMm
                val isMajor = isMultiple(mm, major)
                val isHalf = half > 0 && isMultiple(mm, half)
                val th = when {
                    isMajor -> 0.55f
                    isHalf -> 0.42f
                    else -> 0.28f
                } * h
                c.drawLine(x, top, x, top + th, tick)
                if (isMajor) c.drawText(if (k == 0) "0 cm" else cmLabel(mm), if (k == 0) x + 12f * unit else x, labelY, label)
                k++
            }

            // Comprimento total acima da régua
            val txt = (if (estimated) "≈ " else "") + formatLength(totalMm)
            val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE; textSize = 13f * unit; textAlign = Paint.Align.CENTER; isFakeBoldText = true
            }
            val tw = tp.measureText(txt)
            val by = top - 6f * unit
            val pill = RectF(len / 2f - tw / 2f - 6f * unit, by - 17f * unit, len / 2f + tw / 2f + 6f * unit, by)
            c.drawRoundRect(pill, 8f * unit, 8f * unit, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(170, 0, 0, 0) })
            c.drawText(txt, len / 2f, by - 4.5f * unit, tp)
        }
        c.restore()

        if (drawHandles) {
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(230, 255, 193, 7) }
            val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 2f * unit
            }
            for ((x, y) in listOf(sx to sy, ex to ey)) {
                c.drawCircle(x, y, 11f * unit, fill)
                c.drawCircle(x, y, 11f * unit, ring)
            }
        }
    }
}
