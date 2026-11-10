package com.personal.financecollector.sync

import com.personal.financecollector.ConfigKeys
import com.personal.financecollector.database.ConfigDao
import com.personal.financecollector.database.EventDao
import com.personal.financecollector.database.RawFinancialEventEntity
import com.personal.financecollector.database.UploadStatus
import com.personal.financecollector.database.set
import kotlin.random.Random

interface TokenSource {
    suspend fun token(): String?
}

/** The queue operations the runner needs, so the retry rules can be tested without Room. */
interface UploadStore {
    suspend fun resetUploading()
    suspend fun dueForUpload(now: Long, limit: Int): List<RawFinancialEventEntity>
    suspend fun markUploading(ids: List<String>)
    suspend fun acknowledge(id: String, ackAt: Long)
    suspend fun markNeedsReview(id: String, reason: String)
    suspend fun markFailedPermanently(id: String, reason: String)
    suspend fun markRetry(id: String, error: String, nextAttemptAt: Long)
    suspend fun revertToPending(ids: List<String>)
    suspend fun attemptsOf(id: String): Int
    suspend fun minNextAttemptAt(): Long?
}

class RoomUploadStore(private val dao: EventDao) : UploadStore {
    override suspend fun resetUploading() = dao.resetUploading()
    override suspend fun dueForUpload(now: Long, limit: Int) = dao.dueForUpload(now, limit)
    override suspend fun markUploading(ids: List<String>) = dao.markUploading(ids)
    override suspend fun acknowledge(id: String, ackAt: Long) = dao.acknowledge(id, ackAt)
    override suspend fun markNeedsReview(id: String, reason: String) = dao.finishWithStatus(id, UploadStatus.NEEDS_REVIEW, reason)
    override suspend fun markFailedPermanently(id: String, reason: String) =
        dao.finishWithStatus(id, UploadStatus.FAILED_PERMANENTLY, reason)

    override suspend fun markRetry(id: String, error: String, nextAttemptAt: Long) {
        dao.markRetry(id, error)
        dao.scheduleRetry(id, nextAttemptAt)
    }

    override suspend fun revertToPending(ids: List<String>) = dao.revertToPending(ids)
    override suspend fun attemptsOf(id: String) = dao.attemptsOf(id) ?: 0
    override suspend fun minNextAttemptAt() = dao.minNextAttemptAt()
}

sealed interface RunResult {
    data object NotPaired : RunResult
    data object Idle : RunResult
    data object Unauthorized : RunResult
    /** Events remain queued; run again at [atMillis]. */
    data class RetryAt(val atMillis: Long) : RunResult
}

/**
 * Uploads due events in batches. An event becomes SYNCED only when the backend answers ACCEPTED or DUPLICATE for it.
 * Anything else keeps the event in the queue (transient failures retry with jittered backoff) or parks it for review.
 */
class UploadRunner(
    private val store: UploadStore,
    private val api: UploadApi,
    private val config: ConfigDao,
    private val credentials: TokenSource,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: Random = Random.Default,
) {
    suspend fun run(): RunResult {
        val baseUrl = config.get(ConfigKeys.BACKEND_URL)
        val token = credentials.token()
        if (config.get(ConfigKeys.PAIRED) != "true" || baseUrl.isNullOrBlank() || token == null) return RunResult.NotPaired

        // Left over from a run that was killed mid-upload; the backend deduplicates if it had in fact stored them.
        store.resetUploading()
        config.set(ConfigKeys.SYNC_LAST_ATTEMPT_AT, clock().toString())

        while (true) {
            val batch = store.dueForUpload(clock(), BATCH_SIZE)
            if (batch.isEmpty()) break
            val ids = batch.map { it.localEventId }
            store.markUploading(ids)

            when (val outcome = api.uploadBatch(baseUrl, token, batch)) {
                is BatchOutcome.Acked -> applyResults(ids, outcome.results)
                BatchOutcome.Unauthorized -> {
                    store.revertToPending(ids)
                    config.set(ConfigKeys.SYNC_LAST_ERROR, UNAUTHORIZED)
                    return RunResult.Unauthorized
                }
                is BatchOutcome.Transient -> {
                    retryLater(ids, outcome.reason)
                    return RunResult.RetryAt(store.minNextAttemptAt() ?: clock())
                }
                is BatchOutcome.Rejected -> {
                    ids.forEach { store.markFailedPermanently(it, outcome.reason) }
                    config.set(ConfigKeys.SYNC_LAST_ERROR, outcome.reason)
                }
            }
        }

        val next = store.minNextAttemptAt()
        return if (next == null) RunResult.Idle else RunResult.RetryAt(next)
    }

    private suspend fun applyResults(sent: List<String>, results: List<ItemResult>) {
        val byId = results.associateBy { it.localEventId }
        val now = clock()
        var missing = false
        for (id in sent) {
            val result = byId[id]
            when (result?.status) {
                "ACCEPTED", "DUPLICATE" -> store.acknowledge(id, now)
                "REJECTED" -> store.markNeedsReview(id, result.reason ?: "rejected by backend")
                else -> {
                    missing = true
                    retryLater(listOf(id), "no acknowledgement")
                }
            }
        }
        if (!missing) {
            config.set(ConfigKeys.SYNC_LAST_OK_AT, now.toString())
            config.set(ConfigKeys.SYNC_LAST_ERROR, "")
        }
    }

    private suspend fun retryLater(ids: List<String>, reason: String) {
        config.set(ConfigKeys.SYNC_LAST_ERROR, reason)
        val now = clock()
        ids.forEach { id -> store.markRetry(id, reason, now + Backoff.delayMillis(store.attemptsOf(id), random)) }
    }

    companion object {
        // The backend commits one transaction per event over a remote database, so large batches outlast the timeout.
        const val BATCH_SIZE = 10
        const val UNAUTHORIZED = "unauthorized"
    }
}
