package com.personal.financecollector.collector

import com.personal.financecollector.database.FinanceDatabase
import com.personal.financecollector.database.ParserStatus
import com.personal.financecollector.database.RawFinancialEventEntity
import com.personal.financecollector.database.SourceType
import com.personal.financecollector.database.UploadStatus
import java.time.LocalDate
import java.util.UUID

data class StatementImportResult(
    val added: Int,
    val alreadyHad: Int,
    val skipped: Int,
    val from: LocalDate?,
    val to: LocalDate?,
    val problem: String?,
)

/** Stores the lines of a downloaded statement as events, so they merge with the bank's alerts and upload like any other. */
class StatementImporter(private val db: FinanceDatabase) {

    suspend fun import(file: ByteArray, password: String?, bank: String, last4: String): StatementImportResult {
        val parsed = StatementParser.parseFile(file, password)
        if (parsed.problem != null) return StatementImportResult(0, 0, parsed.skipped, null, null, parsed.problem)
        val number = parsed.accountNumber
        if (number != null && !number.endsWith(last4)) {
            return StatementImportResult(0, 0, 0, null, null, "This statement is for the account ending ${number.takeLast(4)}, not ending $last4. Pick that account instead.")
        }
        val now = System.currentTimeMillis()
        val seen = HashMap<String, Int>()
        val perDay = HashMap<LocalDate, Int>()
        var added = 0
        var had = 0
        // Oldest first, so the last line of a day carries the day's closing balance.
        val rows = if (parsed.rows.first().date > parsed.rows.last().date) parsed.rows.reversed() else parsed.rows
        for (row in rows) {
            val payload = StatementParser.payload(bank, last4, row)
            // Two identical lines on one day are two real payments, so the n-th copy gets its own id; importing the file again adds nothing.
            val n = seen.merge(payload, 1, Int::plus)!!
            val hash = Hashing.sha256(payload)
            val inserted = db.eventDao().insertWithQueue(
                RawFinancialEventEntity(
                    localEventId = UUID.randomUUID().toString(),
                    sourceType = SourceType.MANUAL,
                    sourceApplication = bank.uppercase().take(20),
                    sourceSender = null,
                    sourceEventId = Hashing.sha256("stmt|$hash|$n"),
                    eventTimestamp = StatementParser.timestampOf(row.date) + 1000L * perDay.merge(row.date, 1, Int::plus)!!,
                    capturedTimestamp = now,
                    rawPayload = payload,
                    payloadHash = hash,
                    parserStatus = ParserStatus.PARSED,
                    parserVersion = TransactionParser.VERSION,
                    uploadStatus = UploadStatus.PENDING,
                ),
            )
            if (inserted) added++ else had++
        }
        return StatementImportResult(added, had, parsed.skipped, rows.minOf { it.date }, rows.maxOf { it.date }, null)
    }
}
