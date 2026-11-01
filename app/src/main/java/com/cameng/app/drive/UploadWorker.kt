package com.cameng.app.drive

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.cameng.app.Notifications
import com.cameng.app.Prefs
import com.cameng.app.Storage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Envia tudo que está em "pendentes" para o Drive, em  CamEng/AAAA-MM-DD/arquivo.
 * Roda sozinho quando há internet (WorkManager), inclusive com o app fechado.
 */
class UploadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    companion object {
        const val ROOT_FOLDER = "CamEng"
        private const val TAG = "UploadWorker"
    }

    private val ctx get() = applicationContext

    override suspend fun doWork(): Result {
        val prefs = Prefs(ctx)
        if (!prefs.driveConnected) return Result.success()
        if (Storage.pendingFiles(ctx).isEmpty()) return Result.success()

        val token = try {
            DriveAuth.tokenSilently(ctx)
        } catch (e: Exception) {
            Log.w(TAG, "Falha ao obter token", e)
            return Result.retry()
        }
        if (token == null) {
            Notifications.showNeedsLogin(ctx)
            return Result.success() // os arquivos continuam pendentes até reconectar
        }
        return withContext(Dispatchers.IO) { uploadAll(prefs, DriveClient(token)) }
    }

    private suspend fun uploadAll(prefs: Prefs, drive: DriveClient): Result {
        val dayFolders = HashMap<String, String>()
        var failures = 0
        var done = 0

        try {
            val rootId = prefs.driveRootFolderId?.takeIf { drive.folderUsable(it) }
                ?: drive.ensureFolder(ROOT_FOLDER, null).also { prefs.driveRootFolderId = it }

            // Repete enquanto aparecerem fotos novas durante o envio.
            val tried = HashSet<String>()
            while (true) {
                val batch = Storage.pendingFiles(ctx).filter { tried.add(it.name) }
                if (batch.isEmpty()) break
                val total = done + batch.size
                for (f in batch) {
                    showProgress(done, total)
                    try {
                        uploadOne(drive, f, rootId, dayFolders)
                        Storage.markUploaded(ctx, f)
                    } catch (e: DriveClient.HttpError) {
                        Log.w(TAG, "Erro enviando ${f.name}", e)
                        if (e.code == 401 || e.code == 403) return Result.retry()
                        failures++
                    } catch (e: IOException) {
                        Log.w(TAG, "Erro de rede enviando ${f.name}", e)
                        failures++
                    }
                    done++
                }
            }
        } catch (e: IOException) {
            Log.w(TAG, "Erro preparando pastas", e)
            return Result.retry()
        }
        return if (failures > 0) Result.retry() else Result.success()
    }

    private fun uploadOne(drive: DriveClient, f: File, rootId: String, dayFolders: MutableMap<String, String>) {
        if (!f.exists()) return
        val day = Storage.dayFolderName(f)
        val folderId = dayFolders.getOrPut(day) { drive.ensureFolder(day, rootId) }
        // Evita duplicar se um envio anterior terminou mas o app não chegou a marcar.
        val existing = drive.findChild(f.name, folderId, folder = false)
        if (existing != null && existing.optString("size") == f.length().toString()) return
        drive.upload(f, Storage.mimeOf(f), folderId)
    }

    private suspend fun showProgress(done: Int, total: Int) {
        val n = Notifications.progress(ctx, done, total)
        val info = if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(Notifications.ID_PROGRESS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(Notifications.ID_PROGRESS, n)
        }
        try {
            setForeground(info)
        } catch (e: Exception) {
            // Android pode recusar iniciar serviço em primeiro plano estando em segundo plano;
            // o envio continua normalmente sem a notificação.
        }
    }
}
