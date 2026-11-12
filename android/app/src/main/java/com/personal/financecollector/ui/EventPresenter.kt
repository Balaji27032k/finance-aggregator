package com.personal.financecollector.ui

import com.personal.financecollector.collector.NotificationApps
import com.personal.financecollector.collector.TransactionParser
import com.personal.financecollector.database.RawFinancialEventEntity
import com.personal.financecollector.database.SourceType
import com.personal.financecollector.database.UploadStatus
import java.math.BigDecimal
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class StatusKind { DONE, WAITING, RETRYING, FAILED, REVIEW }

data class EventCardData(
    val title: String,
    val amount: String?,
    val credit: Boolean?,
    val details: String,
    val extra: String?,
    val note: String?,
    val status: String,
    val understood: Boolean,
    val statusKind: StatusKind = StatusKind.DONE,
    val problem: String? = null,
    val received: String = "",
)

/** Turns a captured event into the facts shown on the Events tab; the raw message text is never displayed. */
object EventPresenter {
    private val dateFormat = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH)
    private val receivedFormat = DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a", Locale.ENGLISH)

    /** The cards are sorted by this time, so it is shown on every card. */
    fun present(event: RawFinancialEventEntity): EventCardData = build(event).copy(
        received = java.time.Instant.ofEpochMilli(event.eventTimestamp).atZone(LedgerBuilder.IST).format(receivedFormat),
    )

    private fun build(event: RawFinancialEventEntity): EventCardData {
        val t = TransactionParser.parse(event)
        val source = sourceLabel(event)
        if (t == null) {
            return EventCardData("Not understood yet", null, null, source, "Kept on this phone for review", null, "Not uploaded", false, StatusKind.REVIEW)
        }
        val credit = t.direction == "CREDIT"
        val title = t.counterparty ?: t.paymentMode?.let { "$it ${if (credit) "credit" else "payment"}" } ?: if (credit) "Money received" else "Money sent"
        val details = listOfNotNull(
            t.transactionDate?.format(dateFormat),
            t.paymentMode,
            t.accountLast4?.let { "A/c ••$it" },
            source,
        ).joinToString(" · ")
        val extra = listOfNotNull(t.referenceNo?.let { "Ref $it" }, t.balanceAfter?.let { "Balance ${rupees(it)}" })
            .joinToString(" · ").ifEmpty { null }
        return EventCardData(
            title, (if (credit) "+" else "-") + rupees(t.amount), credit, details, extra, t.note,
            statusLabel(event.uploadStatus), true, statusKind(event), problem(event),
        )
    }

    fun statusKind(status: UploadStatus): StatusKind = when (status) {
        UploadStatus.SYNCED -> StatusKind.DONE
        UploadStatus.PENDING, UploadStatus.UPLOADING -> StatusKind.WAITING
        UploadStatus.RETRY -> StatusKind.RETRYING
        UploadStatus.FAILED_PERMANENTLY -> StatusKind.FAILED
        UploadStatus.NEEDS_REVIEW -> StatusKind.REVIEW
    }

    /** A message the server refused is a failure; one that is merely not understood is only kept for review. */
    fun statusKind(event: RawFinancialEventEntity): StatusKind {
        val kind = statusKind(event.uploadStatus)
        return if (kind == StatusKind.REVIEW && !event.lastError.isNullOrBlank() && event.lastError != NOT_UNDERSTOOD_REASON) StatusKind.FAILED else kind
    }

    const val NOT_UNDERSTOOD_REASON = "message format not recognised yet"

    /** The reason an upload did not go through, when there is one; it only ever names fields, never message text. */
    fun problem(event: RawFinancialEventEntity): String? =
        event.lastError?.takeIf { it.isNotBlank() && event.uploadStatus != UploadStatus.SYNCED }

    fun sourceLabel(event: RawFinancialEventEntity): String = when (event.sourceType) {
        SourceType.NOTIFICATION -> NotificationApps.shortName(event.sourceApplication)
        SourceType.SMS -> event.sourceApplication
        SourceType.GMAIL -> "Email ${event.sourceApplication}"
        else -> event.sourceType.name
    }

    fun statusLabel(status: UploadStatus): String = when (status) {
        UploadStatus.SYNCED -> "Uploaded"
        UploadStatus.PENDING, UploadStatus.UPLOADING -> "Waiting to upload"
        UploadStatus.RETRY -> "Upload failed, will retry"
        UploadStatus.NEEDS_REVIEW -> "Kept on phone"
        UploadStatus.FAILED_PERMANENTLY -> "Upload failed"
    }

    /** Indian digit grouping, for example 1,06,216.00. */
    fun rupees(amount: BigDecimal): String {
        val plain = amount.setScale(2, java.math.RoundingMode.HALF_UP).abs().toPlainString()
        val (whole, fraction) = plain.split(".").let { it[0] to it[1] }
        val grouped = if (whole.length <= 3) whole else {
            val head = whole.dropLast(3).reversed().chunked(2).joinToString(",").reversed()
            "$head,${whole.takeLast(3)}"
        }
        return "₹$grouped.$fraction"
    }
}
