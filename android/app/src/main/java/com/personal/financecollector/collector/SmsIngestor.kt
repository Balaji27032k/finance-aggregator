package com.personal.financecollector.collector

import com.personal.financecollector.ConfigKeys
import com.personal.financecollector.DefaultSmsSenders
import com.personal.financecollector.StatusKeys
import com.personal.financecollector.database.FinanceDatabase
import com.personal.financecollector.database.RawFinancialEventEntity
import com.personal.financecollector.database.SourceType
import com.personal.financecollector.database.UploadStatus
import java.util.UUID

enum class SmsIngestResult { CAPTURED, DUPLICATE, PAUSED, SENDER_NOT_ALLOWED, FILTERED }

class SmsIngestor(private val db: FinanceDatabase, private val onCaptured: () -> Unit = {}) {

    suspend fun loadRules(): SmsRules = SmsRules(
        paused = db.configDao().get(ConfigKeys.COLLECTION_PAUSED) == "true",
        allowedSenders = db.sourceDao().enabledIdentifiers(SourceType.SMS).toSet() + DefaultSmsSenders.all.map { it.first },
    )

    suspend fun ingest(sender: String?, body: String, timestampMs: Long): SmsIngestResult =
        ingest(loadRules(), sender, body, timestampMs)

    suspend fun ingest(rules: SmsRules, sender: String?, body: String, timestampMs: Long): SmsIngestResult {
        val status = db.statusDao()
        val accepted = when (val c = SmsClassifier.classify(sender, body, rules)) {
            SmsClassification.Paused -> return SmsIngestResult.PAUSED
            SmsClassification.SenderNotAllowed -> return SmsIngestResult.SENDER_NOT_ALLOWED
            SmsClassification.Filtered -> {
                status.ensure(StatusKeys.SMS)
                status.incrementFiltered(StatusKeys.SMS)
                return SmsIngestResult.FILTERED
            }
            is SmsClassification.Accept -> c
        }

        val now = System.currentTimeMillis()
        val eventTime = if (timestampMs > 0) timestampMs else now
        val payloadHash = Hashing.sha256(accepted.payload)

        // The same SMS can arrive live and again via the history scan; its timestamp differs by source, so also match on a time window.
        val nearby = db.eventDao().countNear(
            SourceType.SMS, accepted.entityCode, payloadHash, eventTime - DUPLICATE_WINDOW_MS, eventTime + DUPLICATE_WINDOW_MS,
        )
        if (nearby > 0) return SmsIngestResult.DUPLICATE

        val inserted = db.eventDao().insertWithQueue(
            RawFinancialEventEntity(
                localEventId = UUID.randomUUID().toString(),
                sourceType = SourceType.SMS,
                sourceApplication = accepted.entityCode,
                sourceSender = sender,
                sourceEventId = Hashing.sha256("sms|${accepted.entityCode}|$payloadHash|${eventTime / 1000}"),
                eventTimestamp = eventTime,
                capturedTimestamp = now,
                rawPayload = accepted.payload,
                payloadHash = payloadHash,
                parserStatus = accepted.parserStatus,
                parserVersion = NotificationCollector.PARSER_VERSION,
                uploadStatus = UploadStatus.PENDING,
            ),
        )
        if (!inserted) return SmsIngestResult.DUPLICATE
        status.ensure(StatusKeys.SMS)
        status.setLastEvent(StatusKeys.SMS, now)
        onCaptured()
        return SmsIngestResult.CAPTURED
    }

    private companion object {
        const val DUPLICATE_WINDOW_MS = 120_000L
    }
}
