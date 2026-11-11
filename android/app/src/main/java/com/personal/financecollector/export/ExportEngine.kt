package com.personal.financecollector.export

import com.personal.financecollector.collector.Hashing
import com.personal.financecollector.database.ConfigDao
import com.personal.financecollector.database.set
import com.personal.financecollector.sync.BackendApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

sealed interface StagingOutcome {
    data class Stored(val stored: Int, val duplicates: Int) : StagingOutcome
    data object Unauthorized : StagingOutcome
    data class Transient(val reason: String) : StagingOutcome
    data class Rejected(val reason: String) : StagingOutcome
}

fun interface StagingSink {
    suspend fun upload(messages: List<StagedMessage>): StagingOutcome
}

class BackendStagingSink(
    private val client: OkHttpClient,
    private val baseUrl: String,
    private val token: String,
) : StagingSink {
    override suspend fun upload(messages: List<StagedMessage>): StagingOutcome = withContext(Dispatchers.IO) {
        val url = BackendApi.normalizeBaseUrl(baseUrl) ?: return@withContext StagingOutcome.Rejected("backend URL is not https")
        val request = Request.Builder()
            .url("$url/api/v1/staging/batch")
            .header("Authorization", "Bearer $token")
            .post(StagingJson.build(messages).toRequestBody("application/json".toMediaType()))
            .build()
        try {
            client.newCall(request).execute().use { response ->
                when (val code = response.code) {
                    200 -> parseStored(response.body.string()) ?: StagingOutcome.Transient("unreadable response")
                    401, 403 -> StagingOutcome.Unauthorized
                    408, 429, in 500..599 -> StagingOutcome.Transient("HTTP $code")
                    else -> StagingOutcome.Rejected("HTTP $code")
                }
            }
        } catch (e: IOException) {
            StagingOutcome.Transient("upload to backend: ${e.javaClass.simpleName}")
        }
    }

    companion object {
        fun parseStored(body: String): StagingOutcome.Stored? = try {
            JSONObject(body).let { StagingOutcome.Stored(it.getInt("stored"), it.getInt("duplicates")) }
        } catch (_: JSONException) {
            null
        }
    }
}

sealed interface ExportResult {
    data object Complete : ExportResult
    data class Retry(val reason: String) : ExportResult
    data class Failed(val reason: String) : ExportResult
}

object ExportState {
    const val RUNNING = "RUNNING"
    const val COMPLETE = "COMPLETE"
    const val FAILED = "FAILED"
}

/** Counters and the resume checkpoint of one export, kept in device configuration under "export_<name>_". */
class ExportProgress(private val config: ConfigDao, name: String) {
    private val prefix = "export_${name}_"

    suspend fun text(field: String): String? = config.get(prefix + field)
    suspend fun number(field: String): Long = text(field)?.toLongOrNull() ?: 0L
    suspend fun put(field: String, value: Any) = config.set(prefix + field, value.toString())

    suspend fun reset() {
        listOf(SEEN, UPLOADED, SKIPPED, TOTAL, CHECKPOINT, ERROR).forEach { put(it, "") }
    }

    companion object {
        const val STATE = "state"
        const val SEEN = "seen"
        const val UPLOADED = "uploaded"
        const val SKIPPED = "skipped"
        const val TOTAL = "total"
        const val CHECKPOINT = "checkpoint"
        const val ERROR = "error"
    }
}

private class Abort(val result: ExportResult) : Exception()

/** Uploads one batch; returns a result when the export must stop. */
private suspend fun ExportProgress.deliver(sink: StagingSink, batch: List<StagedMessage>): ExportResult? {
    if (batch.isEmpty()) return null
    return when (val outcome = sink.upload(batch)) {
        is StagingOutcome.Stored -> {
            put(ExportProgress.UPLOADED, number(ExportProgress.UPLOADED) + batch.size)
            null
        }
        StagingOutcome.Unauthorized -> ExportResult.Failed("The backend rejected the device token. Pair again.")
        is StagingOutcome.Transient -> ExportResult.Retry(outcome.reason)
        is StagingOutcome.Rejected -> ExportResult.Failed(outcome.reason)
    }
}

data class SmsRow(val id: Long, val address: String?, val body: String, val date: Long)

interface SmsReader {
    suspend fun read(afterId: Long, onRow: suspend (SmsRow) -> Unit)
}

/** Sends every inbox message except personal-number chats, with OTP digits masked. Resumes after the last uploaded id. */
class SmsExporter(
    private val reader: SmsReader,
    private val progress: ExportProgress,
    private val sink: StagingSink,
    private val batchSize: Int = 25,
) {
    suspend fun run(): ExportResult {
        var lastId = progress.number(ExportProgress.CHECKPOINT)
        var seen = progress.number(ExportProgress.SEEN)
        var skipped = progress.number(ExportProgress.SKIPPED)
        progress.put(ExportProgress.STATE, ExportState.RUNNING)
        progress.put(ExportProgress.ERROR, "")

        val batch = mutableListOf<StagedMessage>()
        var batchLastId = lastId

        suspend fun flush() {
            progress.deliver(sink, batch)?.let { throw Abort(it) }
            batch.clear()
            lastId = batchLastId
            progress.put(ExportProgress.CHECKPOINT, lastId)
            progress.put(ExportProgress.SEEN, seen)
            progress.put(ExportProgress.SKIPPED, skipped)
        }

        val result = try {
            reader.read(lastId) { row ->
                seen++
                batchLastId = row.id
                if (row.address.isNullOrBlank() || ExportMasking.isPersonalNumber(row.address)) {
                    skipped++
                } else {
                    batch += StagedMessage(
                        channel = "SMS",
                        externalId = Hashing.sha256("sms|${row.id}|${row.date}|${row.address}"),
                        sender = row.address,
                        subject = null,
                        receivedAtMs = row.date,
                        body = ExportMasking.maskOtp(row.body),
                        bodyTruncated = false,
                        labels = null,
                    )
                    if (batch.size >= batchSize) flush()
                }
            }
            flush()
            ExportResult.Complete
        } catch (a: Abort) {
            a.result
        }
        finish(result)
        return result
    }

    private suspend fun finish(result: ExportResult) {
        when (result) {
            ExportResult.Complete -> progress.put(ExportProgress.STATE, ExportState.COMPLETE)
            is ExportResult.Retry -> progress.put(ExportProgress.ERROR, result.reason)
            is ExportResult.Failed -> {
                progress.put(ExportProgress.STATE, ExportState.FAILED)
                progress.put(ExportProgress.ERROR, result.reason)
            }
        }
    }
}

data class MailPage(val ids: List<String>, val nextPageToken: String?, val estimate: Int)

interface MailSource {
    suspend fun list(query: String, pageToken: String?): MailPage

    /** Full message JSON, or null if it no longer exists. */
    suspend fun get(id: String): String?
}

class GmailNeedsConsent : Exception("Gmail access has not been granted")

/** Fetches matching mail newest-first, drops filtered senders, and checkpoints by date so it can resume. */
class GmailExporter(
    private val source: MailSource,
    private val progress: ExportProgress,
    private val sink: StagingSink,
    private val maxMessages: Int = 20_000,
    private val fetchParallelism: Int = 6,
    private val chunkSize: Int = 10,
) {
    suspend fun run(): ExportResult {
        var seen = progress.number(ExportProgress.SEEN)
        var skipped = progress.number(ExportProgress.SKIPPED)
        val before = progress.number(ExportProgress.CHECKPOINT).takeIf { it > 0 }
        val query = GmailQuery.build() + (before?.let { " before:$it" } ?: "")
        progress.put(ExportProgress.STATE, ExportState.RUNNING)
        progress.put(ExportProgress.ERROR, "")

        val gate = Semaphore(fetchParallelism)
        val result = try {
            var page: String? = null
            var first = true
            do {
                val listed = source.list(query, page)
                if (first && before == null) progress.put(ExportProgress.TOTAL, listed.estimate)
                first = false
                for (chunk in listed.ids.chunked(chunkSize)) {
                    val raw = coroutineScope { chunk.map { id -> async { gate.withPermit { source.get(id) } } }.awaitAll() }
                    val parsed = raw.filterNotNull().mapNotNull { runCatching { GmailMessageParser.parse(it) }.getOrNull() }
                    val keep = parsed.mapNotNull { it.message }
                    progress.deliver(sink, keep)?.let { throw Abort(it) }
                    seen += chunk.size
                    skipped += chunk.size - keep.size
                    parsed.minOfOrNull { it.internalDateMs }?.takeIf { it > 0 }
                        ?.let { progress.put(ExportProgress.CHECKPOINT, it / 1000) }
                    progress.put(ExportProgress.SEEN, seen)
                    progress.put(ExportProgress.SKIPPED, skipped)
                }
                page = listed.nextPageToken
            } while (page != null && seen < maxMessages)
            ExportResult.Complete
        } catch (a: Abort) {
            a.result
        } catch (_: GmailNeedsConsent) {
            ExportResult.Failed("Connect Gmail again (access was not granted)")
        } catch (e: IOException) {
            ExportResult.Retry("reading Gmail: ${e.javaClass.simpleName}")
        }

        when (result) {
            ExportResult.Complete -> progress.put(ExportProgress.STATE, ExportState.COMPLETE)
            is ExportResult.Retry -> progress.put(ExportProgress.ERROR, result.reason)
            is ExportResult.Failed -> {
                progress.put(ExportProgress.STATE, ExportState.FAILED)
                progress.put(ExportProgress.ERROR, result.reason)
            }
        }
        return result
    }
}
