package com.cameng.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/** Gira a foto, "queima" régua, data/hora e comentário e salva o JPEG. */
object PhotoProcessor {

    data class Request(
        val date: Date,
        val comment: String,
        val ruler: RulerSnapshot?,
        /** Rotação do celular na hora da foto (0, 90, 180, 270) vinda do OrientationEventListener. */
        val deviceOrientation: Int,
    )

    fun process(ctx: Context, captured: Bitmap, rotationDegrees: Int, req: Request): File {
        // 1) Deixa a imagem como aparecia na tela (em pé).
        var bmp = rotated(captured, rotationDegrees)

        // 2) Régua, desenhada nas mesmas coordenadas da tela.
        req.ruler?.let { r ->
            val rect = RulerView.contentRect(r.viewW, r.viewH, bmp.width.toFloat() / bmp.height)
            val k = bmp.width / rect.width()
            val c = Canvas(bmp)
            c.save()
            c.scale(k, k)
            c.translate(-rect.left, -rect.top)
            RulerRenderer.draw(
                c, r.sx, r.sy, r.ex, r.ey, r.mode, r.mmPerPx, r.divisions, r.unit,
                estimated = r.mode == RulerView.Mode.REAL_AUTO, drawHandles = false
            )
            c.restore()
        }

        // 3) Gira conforme o celular estava (em pé / deitado).
        bmp = rotated(bmp, req.deviceOrientation)

        // 4) Data/hora e comentário no canto inferior direito.
        val dateText = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale("pt", "BR")).format(req.date)
        val lines = buildList {
            if (req.comment.isNotBlank()) add(req.comment.trim())
            add(dateText)
        }
        stamp(bmp, lines)

        // 5) Salva (primeiro em .tmp para o envio nunca pegar arquivo pela metade).
        val target = Storage.newFile(ctx, "jpg", req.date)
        val tmp = Storage.tempFor(target)
        FileOutputStream(tmp).use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        bmp.recycle()
        writeExif(tmp, req)
        tmp.setLastModified(req.date.time)
        if (!tmp.renameTo(target)) throw IllegalStateException("Não foi possível salvar a foto")
        return target
    }

    private fun writeExif(file: File, req: Request) {
        runCatching {
            val exif = ExifInterface(file)
            val fmt = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
            val d = fmt.format(req.date)
            exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, d)
            exif.setAttribute(ExifInterface.TAG_DATETIME, d)
            exif.setAttribute(ExifInterface.TAG_SOFTWARE, "CamEng")
            if (req.comment.isNotBlank()) exif.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, req.comment.trim())
            exif.saveAttributes()
        }
    }

    /** Rotaciona criando um bitmap novo e mutável (libera o original). */
    fun rotated(src: Bitmap, degrees: Int): Bitmap {
        val deg = ((degrees % 360) + 360) % 360
        if (deg == 0) {
            if (src.isMutable && src.config == Bitmap.Config.ARGB_8888) return src
            val copy = src.copy(Bitmap.Config.ARGB_8888, true)
            src.recycle()
            return copy
        }
        val swap = deg % 180 != 0
        val w = if (swap) src.height else src.width
        val h = if (swap) src.width else src.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val m = Matrix().apply {
            postTranslate(-src.width / 2f, -src.height / 2f)
            postRotate(deg.toFloat())
            postTranslate(w / 2f, h / 2f)
        }
        Canvas(out).drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG))
        src.recycle()
        return out
    }

    private fun stamp(bmp: Bitmap, lines: List<String>) {
        val w = bmp.width
        val h = bmp.height
        val base = min(w, h).toFloat()
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = base * 0.032f
            typeface = Typeface.DEFAULT_BOLD
            setShadowLayer(base * 0.003f, 0f, 0f, Color.BLACK)
        }
        val maxW = (w * 0.75f).toInt()
        val text = lines.joinToString("\n")
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, tp, maxW)
            .setAlignment(Layout.Alignment.ALIGN_OPPOSITE)
            .build()
        var lineW = 0f
        for (i in 0 until layout.lineCount) lineW = max(lineW, layout.getLineWidth(i))

        val pad = base * 0.015f
        val margin = base * 0.02f
        val right = w - margin
        val bottom = h - margin
        val left = right - lineW - 2 * pad
        val top = bottom - layout.height - 2 * pad

        val c = Canvas(bmp)
        c.drawRoundRect(left, top, right, bottom, pad, pad, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(140, 0, 0, 0)
        })
        c.save()
        c.translate(right - pad - maxW, top + pad)
        layout.draw(c)
        c.restore()
    }
}
