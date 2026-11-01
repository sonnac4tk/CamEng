package com.cameng.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

object Notifications {
    const val CH_UPLOAD = "upload"
    const val CH_ALERT = "alert"
    const val ID_PROGRESS = 1001
    const val ID_LOGIN = 1002

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_UPLOAD, "Envio para o Drive", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERT, "Avisos", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    private fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0,
        Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    fun progress(ctx: Context, done: Int, total: Int): Notification =
        NotificationCompat.Builder(ctx, CH_UPLOAD)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Enviando para o Google Drive")
            .setContentText("$done de $total")
            .setProgress(total, done, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(ctx))
            .build()

    fun showNeedsLogin(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val n = NotificationCompat.Builder(ctx, CH_ALERT)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("CamEng: reconecte o Google Drive")
            .setContentText("Há fotos esperando envio. Toque para abrir o app e reconectar.")
            .setAutoCancel(true)
            .setContentIntent(openApp(ctx))
            .build()
        NotificationManagerCompat.from(ctx).notify(ID_LOGIN, n)
    }
}
