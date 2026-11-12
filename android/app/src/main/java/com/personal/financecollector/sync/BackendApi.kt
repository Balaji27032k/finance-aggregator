package com.personal.financecollector.sync

import com.personal.financecollector.collector.ParsedTransaction
import com.personal.financecollector.collector.TransactionParser
import com.personal.financecollector.database.ParserStatus
import com.personal.financecollector.database.RawFinancialEventEntity
import com.personal.financecollector.ui.SplitPart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.time.Instant

data class ItemResult(val localEventId: String, val status: String, val reason: String?)

sealed interface BatchOutcome {
    data class Acked(val results: List<ItemResult>) : BatchOutcome
    data object Unauthorized : BatchOutcome
    data class Transient(val reason: String) : BatchOutcome
    data class Rejected(val reason: String) : BatchOutcome
}

sealed interface RegisterOutcome {
    data class Registered(val deviceId: Long, val token: String) : RegisterOutcome
    data object BadPairingCode : RegisterOutcome
    data class Failed(val reason: String) : RegisterOutcome
}

interface UploadApi {
    suspend fun uploadBatch(baseUrl: String, token: String, events: List<RawFinancialEventEntity>): BatchOutcome
}

/** One correction to send: a null category and label clear an earlier one. */
data class LabelPush(val localEventId: String, val category: String?, val label: String?, val splits: List<SplitPart> = emptyList())

sealed interface LabelOutcome {
    /** [missing] are events the server does not have yet; they are sent again after they upload. */
    data class Done(val missing: Set<String>) : LabelOutcome
    data object Unauthorized : LabelOutcome
    data class Failed(val reason: String) : LabelOutcome
}

class BackendApi(private val client: OkHttpClient) : UploadApi {

    suspend fun register(baseUrl: String, pairingCode: String, deviceName: String, appVersion: String): RegisterOutcome =
        withContext(Dispatchers.IO) {
            val url = normalizeBaseUrl(baseUrl) ?: return@withContext RegisterOutcome.Failed("The URL must start with https://")
            val body = JSONObject()
                .put("pairingCode", pairingCode)
                .put("deviceName", deviceName)
                .put("appVersion", appVersion)
                .toString().toRequestBody(JSON)
            val request = Request.Builder().url("$url/api/v1/devices/register").post(body).build()
            try {
                client.newCall(request).execute().use { response ->
                    when {
                        response.code == 201 -> parseRegistration(response.body.string())
                            ?: RegisterOutcome.Failed("Unexpected response from the backend")
                        response.code == 401 -> RegisterOutcome.BadPairingCode
                        response.code == 429 -> RegisterOutcome.Failed("Too many attempts, wait a minute and retry")
                        else -> RegisterOutcome.Failed("The backend answered HTTP ${response.code}")
                    }
                }
            } catch (e: IOException) {
                RegisterOutcome.Failed("Could not reach the backend (${e.javaClass.simpleName})")
            }
        }

    override suspend fun uploadBatch(baseUrl: String, token: String, events: List<RawFinancialEventEntity>): BatchOutcome =
        withContext(Dispatchers.IO) {
            val url = normalizeBaseUrl(baseUrl) ?: return@withContext BatchOutcome.Rejected("backend URL is not https")
            val request = Request.Builder()
                .url("$url/api/v1/events/batch")
                .header("Authorization", "Bearer $token")
                .post(buildBatchJson(events).toRequestBody(JSON))
                .build()
            try {
                client.newCall(request).execute().use { response ->
                    when (val code = response.code) {
                        200 -> parseBatchResults(response.body.string())?.let { BatchOutcome.Acked(it) }
                            ?: BatchOutcome.Transient("unreadable response")
                        401, 403 -> BatchOutcome.Unauthorized
                        408, 429 -> BatchOutcome.Transient("HTTP $code")
                        in 500..599 -> BatchOutcome.Transient("HTTP $code")
                        else -> BatchOutcome.Rejected("HTTP $code")
                    }
                }
            } catch (e: IOException) {
                BatchOutcome.Transient(e.javaClass.simpleName)
            }
        }

    suspend fun pushLabels(baseUrl: String, token: String, items: List<LabelPush>): LabelOutcome =
        withContext(Dispatchers.IO) {
            val url = normalizeBaseUrl(baseUrl) ?: return@withContext LabelOutcome.Failed("backend URL is not https")
            val array = JSONArray()
            items.forEach {
                val parts = JSONArray()
                it.splits.forEach { s -> parts.put(JSONObject().put("category", s.category).put("amount", s.amount.toPlainString())) }
                array.put(
                    JSONObject().put("localEventId", it.localEventId)
                        .put("category", it.category ?: JSONObject.NULL).put("label", it.label ?: JSONObject.NULL)
                        .put("splits", if (it.splits.isEmpty()) JSONObject.NULL else parts),
                )
            }
            val request = Request.Builder()
                .url("$url/api/v1/transactions/labels")
                .header("Authorization", "Bearer $token")
                .post(JSONObject().put("labels", array).toString().toRequestBody(JSON))
                .build()
            try {
                client.newCall(request).execute().use { response ->
                    when (val code = response.code) {
                        200 -> parseMissing(response.body.string())?.let { LabelOutcome.Done(it) } ?: LabelOutcome.Failed("unreadable response")
                        401, 403 -> LabelOutcome.Unauthorized
                        else -> LabelOutcome.Failed("HTTP $code")
                    }
                }
            } catch (e: IOException) {
                LabelOutcome.Failed(e.javaClass.simpleName)
            }
        }

    companion object {
        private val JSON = "application/json".toMediaType()

        /** Only https endpoints are accepted; trailing slashes are removed. */
        fun normalizeBaseUrl(input: String): String? {
            val trimmed = input.trim().trimEnd('/')
            val parsed = trimmed.toHttpUrlOrNull() ?: return null
            return if (parsed.isHttps) trimmed else null
        }

        fun buildBatchJson(events: List<RawFinancialEventEntity>): String {
            val array = JSONArray()
            events.forEach { e ->
                val t = TransactionParser.parse(e) ?: return@forEach
                array.put(
                    JSONObject()
                        .put("localEventId", e.localEventId)
                        .put("sourceType", e.sourceType.name)
                        .put("sourceApplication", e.sourceApplication)
                        .put("sourceSender", e.sourceSender ?: JSONObject.NULL)
                        .put("sourceEventId", e.sourceEventId)
                        .put("eventTimestamp", Instant.ofEpochMilli(e.eventTimestamp).toString())
                        .put("capturedTimestamp", Instant.ofEpochMilli(e.capturedTimestamp).toString())
                        .put("payloadHash", e.payloadHash)
                        .put("transaction", transactionJson(t))
                        .put("parserStatus", ParserStatus.PARSED.name)
                        .put("parserVersion", TransactionParser.VERSION),
                )
            }
            return JSONObject().put("events", array).toString()
        }

        private fun transactionJson(t: ParsedTransaction): JSONObject = JSONObject()
            .put("direction", t.direction)
            .put("amount", t.amount.toPlainString())
            .put("currency", t.currency)
            .put("accountLast4", t.accountLast4 ?: JSONObject.NULL)
            .put("counterparty", t.counterparty ?: JSONObject.NULL)
            .put("counterpartyAccountLast4", t.counterpartyAccountLast4 ?: JSONObject.NULL)
            .put("paymentMode", t.paymentMode ?: JSONObject.NULL)
            .put("referenceNo", t.referenceNo ?: JSONObject.NULL)
            .put("balanceAfter", t.balanceAfter?.toPlainString() ?: JSONObject.NULL)
            .put("transactionDate", t.transactionDate?.toString() ?: JSONObject.NULL)
            .put("bankCode", t.bankCode)
            .put("note", t.note ?: JSONObject.NULL)

        fun parseMissing(body: String): Set<String>? = try {
            val array = JSONObject(body).getJSONArray("missing")
            (0 until array.length()).map { array.getString(it) }.toSet()
        } catch (e: JSONException) {
            null
        }

        fun parseBatchResults(body: String): List<ItemResult>? = try {
            val array = JSONObject(body).getJSONArray("results")
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                ItemResult(
                    localEventId = o.optString("localEventId"),
                    status = o.getString("status"),
                    reason = if (o.isNull("reason")) null else o.optString("reason"),
                )
            }
        } catch (_: JSONException) {
            null
        }

        fun parseRegistration(body: String): RegisterOutcome.Registered? = try {
            val o = JSONObject(body)
            RegisterOutcome.Registered(o.getLong("deviceId"), o.getString("deviceToken"))
        } catch (_: JSONException) {
            null
        }
    }
}
