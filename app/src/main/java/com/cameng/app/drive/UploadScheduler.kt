package com.cameng.app.drive

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object UploadScheduler {
    const val NOW = "upload-now"
    private const val PERIODIC = "upload-periodic"

    // Qualquer internet: Wi-Fi ou dados móveis.
    private val constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    fun enqueueNow(ctx: Context) {
        val req = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        // KEEP: se já existe um envio na fila, ele vai pegar os arquivos novos também.
        WorkManager.getInstance(ctx).enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP, req)
    }

    fun schedulePeriodic(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<UploadWorker>(1, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req)
    }
}
