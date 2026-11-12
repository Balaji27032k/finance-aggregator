package com.personal.financecollector.profile

import com.personal.financecollector.sync.BackendApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

sealed interface ProfileOutcome {
    data class Ok(val profile: MoneyProfile) : ProfileOutcome
    data object Unauthorized : ProfileOutcome
    data class Failed(val reason: String) : ProfileOutcome
}

/** Reads and saves the person's profile on the server; the server is the single copy. */
class ProfileApi(private val client: OkHttpClient) {

    private val json = "application/json".toMediaType()

    suspend fun get(baseUrl: String, token: String): ProfileOutcome = call(baseUrl, token) { it.get() }

    suspend fun put(baseUrl: String, token: String, profile: MoneyProfile): ProfileOutcome =
        call(baseUrl, token) { it.put(ProfileJson.toJson(profile).toRequestBody(json)) }

    private suspend fun call(baseUrl: String, token: String, method: (Request.Builder) -> Request.Builder): ProfileOutcome =
        withContext(Dispatchers.IO) {
            val url = BackendApi.normalizeBaseUrl(baseUrl) ?: return@withContext ProfileOutcome.Failed("the server address is not https")
            val request = method(Request.Builder().url("$url/api/v1/profile").header("Authorization", "Bearer $token")).build()
            val patient = client.newBuilder().readTimeout(100, java.util.concurrent.TimeUnit.SECONDS).callTimeout(110, java.util.concurrent.TimeUnit.SECONDS).build()
            try {
                patient.newCall(request).execute().use { response ->
                    when (val code = response.code) {
                        200 -> ProfileJson.parse(response.body.string())?.let { ProfileOutcome.Ok(it) } ?: ProfileOutcome.Failed("unreadable answer")
                        401, 403 -> ProfileOutcome.Unauthorized
                        400 -> ProfileOutcome.Failed("the server did not accept one of the values")
                        else -> ProfileOutcome.Failed("HTTP $code")
                    }
                }
            } catch (e: IOException) {
                ProfileOutcome.Failed("could not reach the server (${e.javaClass.simpleName})")
            }
        }
}
