package com.personal.financecollector.sync

import com.personal.financecollector.database.ConfigDao
import com.personal.financecollector.database.DeviceConfigurationEntity
import com.personal.financecollector.database.ParserStatus
import com.personal.financecollector.database.RawFinancialEventEntity
import com.personal.financecollector.database.SourceType
import com.personal.financecollector.database.UploadStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

fun testEvent(id: String, capturedAt: Long = 1_000L, status: UploadStatus = UploadStatus.PENDING) = RawFinancialEventEntity(
    localEventId = id,
    sourceType = SourceType.SMS,
    sourceApplication = "SBIUPI",
    sourceSender = "VM-SBIUPI-S",
    sourceEventId = "src-$id",
    eventTimestamp = 1_700_000_000_000L,
    capturedTimestamp = capturedAt,
    rawPayload = "Rs 10 debited from A/c X0000 on 10-11-26",
    payloadHash = "hash-$id",
    parserStatus = ParserStatus.UNPARSED,
    parserVersion = "raw-1",
    uploadStatus = status,
)

class FakeConfig(initial: Map<String, String> = emptyMap()) : ConfigDao {
    val values = initial.toMutableMap()
    override suspend fun put(config: DeviceConfigurationEntity) {
        values[config.key] = config.value
    }

    override suspend fun get(key: String): String? = values[key]
    override fun observe(key: String): Flow<String?> = flowOf(values[key])
    override suspend fun deleteKeys(keys: List<String>) {
        keys.forEach { values.remove(it) }
    }
}

class FakeStore : UploadStore {
    class Row(
        var event: RawFinancialEventEntity,
        var status: UploadStatus,
        var attempts: Int = 0,
        var nextAttemptAt: Long = 0,
        var error: String? = null,
        var ackAt: Long? = null,
    )

    val rows = linkedMapOf<String, Row>()

    fun add(event: RawFinancialEventEntity, status: UploadStatus = UploadStatus.PENDING) {
        rows[event.localEventId] = Row(event, status)
    }

    fun status(id: String) = rows.getValue(id).status

    override suspend fun resetUploading() {
        rows.values.filter { it.status == UploadStatus.UPLOADING }.forEach { it.status = UploadStatus.RETRY }
    }

    override suspend fun dueForUpload(now: Long, limit: Int) = rows.values
        .filter { (it.status == UploadStatus.PENDING || it.status == UploadStatus.RETRY) && it.nextAttemptAt <= now }
        .sortedBy { it.event.capturedTimestamp }.take(limit).map { it.event }

    override suspend fun markUploading(ids: List<String>) = ids.forEach { rows.getValue(it).status = UploadStatus.UPLOADING }

    override suspend fun acknowledge(id: String, ackAt: Long) {
        rows.getValue(id).apply { status = UploadStatus.SYNCED; this.ackAt = ackAt; error = null }
    }

    override suspend fun markNeedsReview(id: String, reason: String) {
        rows.getValue(id).apply { status = UploadStatus.NEEDS_REVIEW; error = reason }
    }

    override suspend fun markFailedPermanently(id: String, reason: String) {
        rows.getValue(id).apply { status = UploadStatus.FAILED_PERMANENTLY; error = reason }
    }

    override suspend fun markRetry(id: String, error: String, nextAttemptAt: Long) {
        rows.getValue(id).apply { status = UploadStatus.RETRY; attempts++; this.error = error; this.nextAttemptAt = nextAttemptAt }
    }

    override suspend fun revertToPending(ids: List<String>) = ids.forEach { rows.getValue(it).status = UploadStatus.PENDING }
    override suspend fun attemptsOf(id: String) = rows.getValue(id).attempts
    override suspend fun minNextAttemptAt() = rows.values
        .filter { it.status == UploadStatus.PENDING || it.status == UploadStatus.RETRY }
        .minOfOrNull { it.nextAttemptAt }
}

class FakeApi(private vararg val script: (List<RawFinancialEventEntity>) -> BatchOutcome) : UploadApi {
    val calls = mutableListOf<List<String>>()
    override suspend fun uploadBatch(baseUrl: String, token: String, events: List<RawFinancialEventEntity>): BatchOutcome {
        calls += events.map { it.localEventId }
        return script[minOf(calls.size - 1, script.size - 1)](events)
    }
}

fun ackAll(status: String = "ACCEPTED"): (List<RawFinancialEventEntity>) -> BatchOutcome = { events ->
    BatchOutcome.Acked(events.map { ItemResult(it.localEventId, status, null) })
}
