package com.cameng.app.drive

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Cliente mínimo da API REST do Google Drive v3. Todas as chamadas são bloqueantes. */
class DriveClient(private val token: String) {

    class HttpError(val code: Int, body: String) : IOException("HTTP $code: ${body.take(300)}")

    companion object {
        private const val API = "https://www.googleapis.com/drive/v3"
        private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3/files"
        const val FOLDER_MIME = "application/vnd.google-apps.folder"
        private val JSON = "application/json; charset=UTF-8".toMediaType()

        private val http: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.MINUTES)
            .writeTimeout(15, TimeUnit.MINUTES)
            .build()
    }

    private fun base(url: String) = Request.Builder().url(url).header("Authorization", "Bearer $token")

    private fun exec(request: Request): String =
        http.newCall(request).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw HttpError(resp.code, body)
            body
        }

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("'", "\\'")

    fun aboutEmail(): String? {
        val body = exec(base("$API/about?fields=user(emailAddress)").get().build())
        return JSONObject(body).optJSONObject("user")?.optString("emailAddress")?.ifBlank { null }
    }

    fun findChild(name: String, parentId: String?, folder: Boolean): JSONObject? {
        val q = buildString {
            append("name = '").append(esc(name)).append("' and trashed = false")
            if (parentId != null) append(" and '").append(esc(parentId)).append("' in parents")
            if (folder) append(" and mimeType = '").append(FOLDER_MIME).append("'")
        }
        val url = "$API/files".toHttpUrl().newBuilder()
            .addQueryParameter("q", q)
            .addQueryParameter("spaces", "drive")
            .addQueryParameter("fields", "files(id,name,size)")
            .build()
        val files = JSONObject(exec(base(url.toString()).get().build())).optJSONArray("files")
        return if (files != null && files.length() > 0) files.getJSONObject(0) else null
    }

    fun folderUsable(id: String): Boolean = try {
        val o = JSONObject(exec(base("$API/files/$id?fields=id,trashed").get().build()))
        !o.optBoolean("trashed", false)
    } catch (e: HttpError) {
        if (e.code == 404) false else throw e
    }

    fun createFolder(name: String, parentId: String?): String {
        val meta = JSONObject().put("name", name).put("mimeType", FOLDER_MIME)
        if (parentId != null) meta.put("parents", JSONArray().put(parentId))
        val body = exec(base("$API/files?fields=id").post(meta.toString().toRequestBody(JSON)).build())
        return JSONObject(body).getString("id")
    }

    fun ensureFolder(name: String, parentId: String?): String =
        findChild(name, parentId, folder = true)?.getString("id") ?: createFolder(name, parentId)

    /** Upload "resumable" (adequado para vídeos grandes). Retorna o id do arquivo no Drive. */
    fun upload(file: File, mime: String, parentId: String): String {
        val meta = JSONObject()
            .put("name", file.name)
            .put("parents", JSONArray().put(parentId))
        val init = base("$UPLOAD?uploadType=resumable&fields=id")
            .header("X-Upload-Content-Type", mime)
            .header("X-Upload-Content-Length", file.length().toString())
            .post(meta.toString().toRequestBody(JSON))
            .build()
        val location = http.newCall(init).execute().use { resp ->
            if (!resp.isSuccessful) throw HttpError(resp.code, resp.body?.string().orEmpty())
            resp.header("Location") ?: throw IOException("Drive não retornou URL de upload")
        }
        val put = base(location).put(file.asRequestBody(mime.toMediaType())).build()
        return JSONObject(exec(put)).getString("id")
    }
}
