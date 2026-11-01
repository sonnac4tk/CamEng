package com.cameng.app

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ThumbnailUtils
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.work.WorkManager
import com.cameng.app.databinding.ActivityGalleryBinding
import com.cameng.app.databinding.ItemMediaBinding
import com.cameng.app.drive.UploadScheduler
import java.io.File
import java.util.concurrent.Executors

/** Fotos e vídeos do trabalho (só deste app), com status de envio ao Drive. */
class GalleryActivity : ComponentActivity() {

    private lateinit var b: ActivityGalleryBinding
    private val loader = Executors.newFixedThreadPool(2)
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private var items: List<Storage.Item> = emptyList()
    private val adapter = Adapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityGalleryBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.grid.layoutManager = GridLayoutManager(this, 3)
        b.grid.adapter = adapter
        b.uploadNow.setOnClickListener {
            if (!Prefs(this).driveConnected) {
                Toast.makeText(this, "Conecte o Google Drive na tela da câmera primeiro.", Toast.LENGTH_LONG).show()
            } else {
                UploadScheduler.enqueueNow(this)
                Toast.makeText(this, "Envio iniciado (quando houver internet).", Toast.LENGTH_SHORT).show()
            }
        }
        WorkManager.getInstance(this).getWorkInfosForUniqueWorkLiveData(UploadScheduler.NOW)
            .observe(this) { reload() }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    override fun onDestroy() {
        super.onDestroy()
        loader.shutdownNow()
    }

    private fun reload() {
        items = Storage.allItems(this)
        val pending = items.count { !it.uploaded }
        b.summary.text = "${items.size} arquivo(s) · " +
            if (pending == 0) "tudo no Drive ✔" else "$pending aguardando envio"
        b.empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        adapter.notifyDataSetChanged()
    }

    private fun uriFor(f: File) = FileProvider.getUriForFile(this, "$packageName.files", f)

    private fun open(item: Storage.Item) {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uriFor(item.file), Storage.mimeOf(item.file))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { startActivity(intent) }
            .onFailure { Toast.makeText(this, "Nenhum app para abrir este arquivo.", Toast.LENGTH_SHORT).show() }
    }

    private fun share(item: Storage.Item) {
        val intent = Intent(Intent.ACTION_SEND)
            .setType(Storage.mimeOf(item.file))
            .putExtra(Intent.EXTRA_STREAM, uriFor(item.file))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(intent, "Compartilhar"))
    }

    private fun confirmDelete(item: Storage.Item) {
        val warn = if (item.uploaded) "A cópia no Google Drive continua lá."
        else "ATENÇÃO: este arquivo ainda NÃO foi enviado ao Drive e será perdido."
        AlertDialog.Builder(this)
            .setTitle("Apagar ${item.file.name}?")
            .setMessage(warn)
            .setPositiveButton("Apagar") { _, _ -> item.file.delete(); reload() }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun loadThumb(f: File): Bitmap? = runCatching {
        if (f.name.endsWith(".mp4")) {
            if (Build.VERSION.SDK_INT >= 29) ThumbnailUtils.createVideoThumbnail(f, Size(320, 320), null)
            else @Suppress("DEPRECATION") ThumbnailUtils.createVideoThumbnail(f.absolutePath, MediaStore.Images.Thumbnails.MINI_KIND)
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.absolutePath, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= 320 && bounds.outHeight / (sample * 2) >= 320) sample *= 2
            BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }.getOrNull()

    inner class Holder(val v: ItemMediaBinding) : RecyclerView.ViewHolder(v.root)

    inner class Adapter : RecyclerView.Adapter<Holder>() {
        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val v = ItemMediaBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            v.root.layoutParams.height = parent.width / 3
            return Holder(v)
        }

        override fun onBindViewHolder(h: Holder, position: Int) {
            val item = items[position]
            val f = item.file
            h.v.status.text = if (item.uploaded) "☁ enviado" else "⏳ pendente"
            h.v.videoBadge.visibility = if (f.name.endsWith(".mp4")) View.VISIBLE else View.GONE
            h.itemView.setOnClickListener { open(item) }
            h.itemView.setOnLongClickListener {
                AlertDialog.Builder(this@GalleryActivity)
                    .setTitle(f.name)
                    .setItems(arrayOf("Abrir", "Compartilhar", "Apagar")) { _, which ->
                        when (which) {
                            0 -> open(item)
                            1 -> share(item)
                            2 -> confirmDelete(item)
                        }
                    }
                    .show()
                true
            }

            val key = f.name
            h.v.thumb.tag = key
            val cached = cache.get(key)
            if (cached != null) {
                h.v.thumb.setImageBitmap(cached)
                return
            }
            h.v.thumb.setImageDrawable(null)
            loader.execute {
                val bmp = loadThumb(f) ?: return@execute
                cache.put(key, bmp)
                runOnUiThread { if (h.v.thumb.tag == key) h.v.thumb.setImageBitmap(bmp) }
            }
        }
    }
}
