package com.personal.financecollector.admin

import com.personal.financecollector.sync.BackendApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

sealed interface AdminResult<out T> {
    data class Ok<T>(val value: T) : AdminResult<T>
    data object Unauthorized : AdminResult<Nothing>
    data class Failed(val message: String) : AdminResult<Nothing>
}

/** Talks to the server's read-only admin API. The session token is kept in memory only and is never written to disk. */
class AdminApi(private val client: OkHttpClient) {
    @Volatile
    private var session: String? = null

    val signedIn: Boolean get() = session != null

    fun forget() {
        session = null
    }

    suspend fun login(baseUrl: String, user: String, password: String): AdminResult<Unit> = withContext(Dispatchers.IO) {
        val base = BackendApi.normalizeBaseUrl(baseUrl) ?: return@withContext AdminResult.Failed("The server address must start with https://")
        val body = JSONObject().put("username", user).put("password", password).toString().toRequestBody(JSON)
        val request = Request.Builder().url("$base/api/admin/login").post(body).build()
        try {
            client.newCall(request).execute().use { response ->
                when (response.code) {
                    200 -> {
                        val token = response.headers("Set-Cookie").firstNotNullOfOrNull { cookieValue(it) }
                        if (token == null) {
                            AdminResult.Failed("The server did not start a session")
                        } else {
                            session = token
                            AdminResult.Ok(Unit)
                        }
                    }
                    401 -> AdminResult.Failed("Wrong user name or password")
                    429 -> AdminResult.Failed("Too many attempts. Wait a minute and try again")
                    503 -> AdminResult.Failed("Admin login is not set up on the server yet")
                    else -> AdminResult.Failed("The server answered HTTP ${response.code}")
                }
            }
        } catch (e: IOException) {
            AdminResult.Failed("Could not reach the server. It may be waking up; try again in a minute")
        }
    }

    suspend fun logout(baseUrl: String) {
        val token = session ?: return
        session = null
        val base = BackendApi.normalizeBaseUrl(baseUrl) ?: return
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url("$base/api/admin/logout")
                .header("Cookie", "$COOKIE=$token").post(ByteArray(0).toRequestBody()).build()
            try {
                client.newCall(request).execute().close()
            } catch (_: IOException) {
                // The session also expires on its own.
            }
        }
    }

    suspend fun get(baseUrl: String, path: String, query: Map<String, String?> = emptyMap()): AdminResult<String> =
        withContext(Dispatchers.IO) {
            val token = session ?: return@withContext AdminResult.Unauthorized
            val base = BackendApi.normalizeBaseUrl(baseUrl) ?: return@withContext AdminResult.Failed("The server address must start with https://")
            val url = "$base$path".toHttpUrlOrNull()?.newBuilder()?.apply {
                query.forEach { (k, v) -> if (!v.isNullOrBlank()) addQueryParameter(k, v) }
            }?.build() ?: return@withContext AdminResult.Failed("Bad server address")
            val request = Request.Builder().url(url).header("Cookie", "$COOKIE=$token").get().build()
            try {
                client.newCall(request).execute().use { response ->
                    when (response.code) {
                        200 -> AdminResult.Ok(response.body.string())
                        401, 403 -> {
                            session = null
                            AdminResult.Unauthorized
                        }
                        429 -> AdminResult.Failed("Too many requests. Wait a moment")
                        else -> AdminResult.Failed("The server answered HTTP ${response.code}")
                    }
                }
            } catch (e: IOException) {
                AdminResult.Failed("Could not reach the server. It may be waking up; try again in a minute")
            }
        }

    companion object {
        const val COOKIE = "fin_session"
        private val JSON = "application/json".toMediaType()

        fun cookieValue(setCookie: String): String? {
            val pair = setCookie.substringBefore(';')
            return if (pair.startsWith("$COOKIE=")) pair.substringAfter('=').takeIf { it.isNotEmpty() } else null
        }
    }
}
