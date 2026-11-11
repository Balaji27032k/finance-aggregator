package com.personal.financecollector.collector

import com.personal.financecollector.ConfigKeys
import com.personal.financecollector.database.ConfigDao
import com.personal.financecollector.database.RawFinancialEventEntity
import com.personal.financecollector.database.SourceType
import com.personal.financecollector.database.UploadStatus
import com.personal.financecollector.database.ParserStatus
import com.personal.financecollector.database.set
import com.personal.financecollector.export.GmailMessageParser
import com.personal.financecollector.export.GmailNeedsConsent
import com.personal.financecollector.export.MailSource
import java.io.IOException
import java.util.UUID

/** Only the bank senders and subjects whose e-mails describe a completed transaction. */
object BankMailQuery {
    fun build(afterEpochSeconds: Long): String =
        "from:(@csb.bank.in OR @csb.co.in OR neftinfo.itps@alerts.sbi.bank.in OR yonobysbi@alerts.sbi.bank.in) " +
            "subject:(\"Debit Alert\" OR \"Credit Alert\" OR \"FT-Credit\" OR \"NEFT Transaction\" OR \"Transaction success\") " +
            "-in:spam -in:trash" + if (afterEpochSeconds > 0) " after:$afterEpochSeconds" else ""
}

fun interface BankMailStore {
    /** Returns false when the e-mail is already stored. */
    suspend fun add(event: RawFinancialEventEntity): Boolean
}

sealed interface BankMailResult {
    data class Done(val captured: Int) : BankMailResult
    data object NeedsConnect : BankMailResult
    data class Failed(val reason: String) : BankMailResult
}

/** Reads new bank alert e-mails, keeps the ones that parse as transactions, and queues them for upload like any SMS. */
class BankMailIngestor(
    private val source: MailSource,
    private val store: BankMailStore,
    private val config: ConfigDao,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun run(): BankMailResult {
        val after = config.get(ConfigKeys.GMAIL_BANK_AFTER)?.toLongOrNull() ?: 0L
        val query = BankMailQuery.build(after)
        var newest = after
        var captured = 0
        try {
            var page: String? = null
            do {
                val listed = source.list(query, page)
                for (id in listed.ids) {
                    val mail = GmailMessageParser.extractText(source.get(id) ?: continue) ?: continue
                    newest = maxOf(newest, mail.internalDateMs / 1000)
                    val parsed = EmailTransactionParser.parse(mail.fromAddress, mail.subject, mail.body, mail.internalDateMs) ?: continue
                    val payload = (mail.subject + "\n" + mail.body).take(MAX_PAYLOAD)
                    val added = store.add(
                        RawFinancialEventEntity(
                            localEventId = UUID.randomUUID().toString(),
                            sourceType = SourceType.GMAIL,
                            sourceApplication = parsed.bankCode,
                            sourceSender = mail.fromAddress,
                            sourceEventId = mail.id,
                            eventTimestamp = mail.internalDateMs,
                            capturedTimestamp = clock(),
                            rawPayload = payload,
                            payloadHash = Hashing.sha256(payload),
                            parserStatus = ParserStatus.UNPARSED,
                            parserVersion = TransactionParser.VERSION,
                            uploadStatus = UploadStatus.PENDING,
                        ),
                    )
                    if (added) captured++
                }
                page = listed.nextPageToken
            } while (page != null)
        } catch (_: GmailNeedsConsent) {
            config.set(ConfigKeys.GMAIL_BANK_STATUS, NEEDS_CONNECT)
            return BankMailResult.NeedsConnect
        } catch (e: IOException) {
            config.set(ConfigKeys.GMAIL_BANK_STATUS, "Could not reach Gmail (${e.javaClass.simpleName})")
            return BankMailResult.Failed(e.javaClass.simpleName)
        }
        // One day of overlap covers late delivery; stored e-mails are never duplicated.
        if (newest > after) config.set(ConfigKeys.GMAIL_BANK_AFTER, (newest - OVERLAP_SECONDS).coerceAtLeast(1).toString())
        config.set(ConfigKeys.GMAIL_BANK_LAST_CHECK, clock().toString())
        config.set(ConfigKeys.GMAIL_BANK_STATUS, "OK")
        val total = (config.get(ConfigKeys.GMAIL_BANK_CAPTURED)?.toIntOrNull() ?: 0) + captured
        config.set(ConfigKeys.GMAIL_BANK_CAPTURED, total.toString())
        return BankMailResult.Done(captured)
    }

    companion object {
        const val NEEDS_CONNECT = "NEEDS_CONNECT"
        private const val MAX_PAYLOAD = 4000
        private const val OVERLAP_SECONDS = 86_400L
    }
}
