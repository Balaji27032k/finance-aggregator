package com.personal.financecollector.export

import android.content.Context
import android.content.IntentSender
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object GmailAuth {
    private const val READ_ONLY = "https://www.googleapis.com/auth/gmail.readonly"

    val request: AuthorizationRequest = AuthorizationRequest.builder()
        .setRequestedScopes(listOf(Scope(READ_ONLY)))
        .build()

    sealed interface Result {
        data class Token(val accessToken: String) : Result
        data class NeedsConsent(val intentSender: IntentSender) : Result
        data class Failed(val reason: String) : Result
    }

    /** Returns an access token without any screen when consent was already given; otherwise the consent screen to show. */
    suspend fun authorize(context: Context): Result = try {
        val r = Identity.getAuthorizationClient(context).authorize(request).await()
        val token = r.accessToken
        when {
            r.hasResolution() -> Result.NeedsConsent(r.pendingIntent!!.intentSender)
            token != null -> Result.Token(token)
            else -> Result.Failed("no access token returned")
        }
    } catch (e: com.google.android.gms.common.api.ApiException) {
        Result.Failed("Google sign-in error ${e.statusCode}")
    } catch (e: Exception) {
        Result.Failed(e.javaClass.simpleName)
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
}

/** Gmail REST access. The access token is refreshed through [tokenProvider] when it is old or rejected. */
class GmailRestSource(
    private val http: OkHttpClient,
    private val tokenProvider: suspend () -> GmailAuth.Result,
    private val clock: () -> Long = System::currentTimeMillis,
) : MailSource {
    private var token: String? = null
    private var tokenAt = 0L

    private suspend fun accessToken(forceRefresh: Boolean = false): String {
        if (!forceRefresh && token != null && clock() - tokenAt < TOKEN_LIFETIME_MS) return token!!
        return when (val r = tokenProvider()) {
            is GmailAuth.Result.Token -> r.accessToken.also { token = it; tokenAt = clock() }
            is GmailAuth.Result.NeedsConsent -> throw GmailNeedsConsent()
            is GmailAuth.Result.Failed -> throw IOException(r.reason)
        }
    }

    override suspend fun list(query: String, pageToken: String?): MailPage {
        val url = "$BASE/messages".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("maxResults", "100")
            .apply { if (pageToken != null) addQueryParameter("pageToken", pageToken) }
            .build().toString()
        val body = fetch(url) ?: return MailPage(emptyList(), null, 0)
        val o = JSONObject(body)
        val ids = o.optJSONArray("messages")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("id") } }.orEmpty()
        return MailPage(ids, o.optString("nextPageToken").ifBlank { null }, o.optInt("resultSizeEstimate"))
    }

    override suspend fun get(id: String): String? = fetch("$BASE/messages/$id?format=full")

    /** Null for 404; retries rate limits and server errors a few times before giving up with an IOException. */
    private suspend fun fetch(url: String): String? = withContext(Dispatchers.IO) {
        var refreshed = false
        for (attempt in 0..MAX_RETRIES) {
            val request = Request.Builder().url(url).header("Authorization", "Bearer ${accessToken()}").build()
            val (code, text) = http.newCall(request).execute().use { it.code to it.body.string() }
            when {
                code == 200 -> return@withContext text
                code == 404 -> return@withContext null
                code == 401 && !refreshed -> {
                    refreshed = true
                    accessToken(forceRefresh = true)
                }
                code == 401 || (code == 403 && !text.contains("rateLimit", true) && !text.contains("quota", true)) ->
                    throw GmailNeedsConsent()
                code == 429 || code == 403 || code in 500..599 -> delay(2000L shl attempt)
                else -> throw IOException("Gmail HTTP $code")
            }
        }
        throw IOException("Gmail kept failing")
    }

    private companion object {
        const val BASE = "https://gmail.googleapis.com/gmail/v1/users/me"
        const val TOKEN_LIFETIME_MS = 40 * 60 * 1000L
        const val MAX_RETRIES = 4
    }
}
