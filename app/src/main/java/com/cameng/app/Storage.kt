package com.cameng.app

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Fotos e vídeos ficam na pasta privada do app (Android/data/com.cameng.app/files/CamEng).
 * Essa pasta NÃO é lida pela Galeria nem pelo Google Fotos, então nada se mistura
 * com as fotos pessoais.
 *
 *  pendentes/ -> ainda não foram para o Drive
 *  enviados/  -> já estão no Drive (mantidos no celular também)
 */
object Storage {
    private const val ROOT = "CamEng"

    fun root(ctx: Context): File {
        val base = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        val dir = File(base, ROOT)
        dir.mkdirs()
        runCatching { File(dir, ".nomedia").createNewFile() }
        return dir
    }

    fun pendingDir(ctx: Context) = File(root(ctx), "pendentes").also { it.mkdirs() }
    fun uploadedDir(ctx: Context) = File(root(ctx), "enviados").also { it.mkdirs() }

    private fun isMedia(f: File) =
        f.isFile && !f.name.startsWith(".") && (f.name.endsWith(".jpg") || f.name.endsWith(".mp4"))

    fun pendingFiles(ctx: Context): List<File> =
        (pendingDir(ctx).listFiles()?.filter(::isMedia) ?: emptyList()).sortedBy { it.lastModified() }

    data class Item(val file: File, val uploaded: Boolean)

    fun allItems(ctx: Context): List<Item> {
        val pending = pendingDir(ctx).listFiles()?.filter(::isMedia)?.map { Item(it, false) } ?: emptyList()
        val sent = uploadedDir(ctx).listFiles()?.filter(::isMedia)?.map { Item(it, true) } ?: emptyList()
        return (pending + sent).sortedByDescending { it.file.lastModified() }
    }

    /**
     * Cria o nome final (ainda inexistente) para uma nova mídia, no formato
     * "horas-minutos-segundos - dia-mês-ano", ex.: "14-32-05 - 03-11-2026.jpg".
     * [suffix] é acrescentado depois da data (ex.: " (lento)" nos vídeos).
     */
    fun newFile(ctx: Context, ext: String, date: Date = Date(), suffix: String = ""): File {
        val stamp = SimpleDateFormat("HH-mm-ss - dd-MM-yyyy", Locale.US).format(date)
        var name = "$stamp$suffix.$ext"
        var i = 2
        while (File(pendingDir(ctx), name).exists() || File(uploadedDir(ctx), name).exists()) {
            name = "$stamp$suffix ($i).$ext"
            i++
        }
        return File(pendingDir(ctx), name)
    }

    /** Arquivo temporário usado enquanto a mídia está sendo gravada (não é enviado). */
    fun tempFor(target: File) = File(target.parentFile, ".${target.name}.tmp")

    fun markUploaded(ctx: Context, f: File) {
        val dest = File(uploadedDir(ctx), f.name)
        if (!f.renameTo(dest)) {
            f.copyTo(dest, overwrite = true)
            dest.setLastModified(f.lastModified())
            f.delete()
        }
    }

    fun mimeOf(f: File) = if (f.name.endsWith(".mp4")) "video/mp4" else "image/jpeg"

    fun dayFolderName(f: File): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(f.lastModified()))
}
