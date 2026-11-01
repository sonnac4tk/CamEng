package com.cameng.app.drive

import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.tasks.await

object DriveAuth {
    /** Acesso apenas aos arquivos/pastas criados pelo próprio app (não enxerga o resto do Drive). */
    const val SCOPE_DRIVE_FILE = "https://www.googleapis.com/auth/drive.file"

    fun request(): AuthorizationRequest =
        AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(SCOPE_DRIVE_FILE)))
            .build()

    /**
     * Obtém um token sem mostrar tela. Retorna null se o usuário precisar autorizar de novo
     * (nesse caso o app avisa por notificação).
     */
    suspend fun tokenSilently(ctx: Context): String? {
        val result = Identity.getAuthorizationClient(ctx).authorize(request()).await()
        return if (result.hasResolution()) null else result.accessToken
    }
}
