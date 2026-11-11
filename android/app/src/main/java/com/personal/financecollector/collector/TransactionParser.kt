package com.personal.financecollector.collector

import com.personal.financecollector.database.RawFinancialEventEntity
import com.personal.financecollector.database.SourceType
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.Month
import java.time.ZoneId

data class ParsedTransaction(
    val direction: String,
    val amount: BigDecimal,
    val currency: String,
    val accountLast4: String?,
    val counterparty: String?,
    val counterpartyAccountLast4: String?,
    val paymentMode: String?,
    val referenceNo: String?,
    val balanceAfter: BigDecimal?,
    val transactionDate: LocalDate?,
    val bankCode: String,
    val note: String?,
)

/**
 * Extracts the facts of a bank SMS (amount, direction, account, counterparty, reference, balance, date).
 * Returns null when the direction or amount cannot be determined, so an unfamiliar message is never guessed at.
 */
object TransactionParser {
    const val VERSION = "sms-1"

    private const val CUR = """(?<![A-Za-z])(?:Rs\.?|INR|₹)"""
    private const val NUM = """[0-9][0-9,]*(?:\.[0-9]{1,2})?"""
    private val OPTS = setOf(RegexOption.IGNORE_CASE)

    private val direction = Regex("""\b(debited|withdrawn|spent|sent|credited|received|deposited)\b""", OPTS)
    private val amountAfterKeyword = Regex(
        """\b(?:debited|withdrawn|spent|sent|credited|received|deposited)\s*(?:by|for|with|of)?\s*(?:Rs\.?|INR|₹)?\s*($NUM)""", OPTS,
    )
    private val currencyAmount = Regex("""$CUR\s*($NUM)""", OPTS)
    private val balance = Regex(
        """(?:Avl\.?\s*Bal(?:ance)?|Available\s+Bal(?:ance)?|Bal(?:ance)?)\s*(?:is|:|-)?\s*(?:Rs\.?|INR|₹)?\s*($NUM)""", OPTS,
    )
    private val account = Regex("""A/c\s*(?:No\.?\s*)?[*Xx]*\s*([0-9]{3,})""", OPTS)
    private val stop = """(?=[.,(]|\s+(?:RRN|Ref|UPI|If\s|Avl|Not\s)|$)"""
    private val payee = Regex("""\b(?:to|at)\s+(?!your\b|A/c\b)(?=[A-Za-z])(.+?)$stop""", OPTS)
    private val payer = Regex("""\b(?:by|from)\s+(?!A/c\b)(?!(?:Rs\.?|INR|₹)?\s*\d)(?=[A-Za-z])(.+?)$stop""", OPTS)
    private val reference = Regex(
        """(?:UPI\s*Ref\.?\s*no\.?|RRN|Ref\s*no\.?|UTR(?:\s*No\.?)?)\s*[:\-]?\s*([A-Za-z0-9]{6,})""", OPTS,
    )
    private val date = Regex(
        """\bon\s+(?:date\s+)?(?:(\d{1,2})[-/](\d{1,2})[-/](\d{2,4})|(\d{1,2})[-/ ]?([A-Za-z]{3})[-/ ]?(\d{2,4}))""", OPTS,
    )
    private val info = Regex("""INFO:\s*(.+)$""", OPTS)
    private val footer = Regex("""\s*-\s*(?:SBI|Indian Bank|CSB Bank)\s*$""", OPTS)

    fun parse(event: RawFinancialEventEntity): ParsedTransaction? =
        if (event.sourceType == SourceType.SMS) {
            parseSms(event.sourceApplication, event.rawPayload, event.eventTimestamp)
        } else {
            null
        }

    fun parseSms(bankCode: String, body: String, eventTimestampMs: Long): ParsedTransaction? {
        val text = body.replace(Regex("""\s+"""), " ").trim()
        val keyword = direction.find(text) ?: return null
        val isDebit = keyword.value.lowercase() in setOf("debited", "withdrawn", "spent", "sent")

        val balanceMatch = balance.find(text)
        val amountText = amountAfterKeyword.find(text, keyword.range.first)?.takeIf { it.range.first == keyword.range.first }
            ?.groupValues?.get(1)
            ?: currencyAmount.findAll(text).firstOrNull { m -> balanceMatch?.groups?.get(1)?.range?.let { it.first !in m.range } != false }
                ?.groupValues?.get(1)
            ?: return null
        val amount = money(amountText) ?: return null
        if (amount.signum() <= 0) return null

        val accounts = account.findAll(text).map { it.groupValues[1].takeLast(4) }.toList()
        val name = (if (isDebit) payee else payer).find(text)?.groupValues?.get(1)?.trim()?.take(200)?.ifBlank { null }
        val upper = text.uppercase()
        val mode = listOf("UPI", "NEFT", "IMPS", "RTGS", "ATM").firstOrNull { Regex("""\b$it\b""").containsMatchIn(upper) }
        val note = info.find(text)?.groupValues?.get(1)?.replace(footer, "")?.trim()?.take(255)?.ifBlank { null }

        return ParsedTransaction(
            direction = if (isDebit) "DEBIT" else "CREDIT",
            amount = amount,
            currency = "INR",
            accountLast4 = accounts.firstOrNull(),
            counterparty = name,
            counterpartyAccountLast4 = accounts.getOrNull(1),
            paymentMode = mode,
            referenceNo = reference.find(text)?.groupValues?.get(1),
            balanceAfter = balanceMatch?.groupValues?.get(1)?.let { money(it) },
            transactionDate = textDate(text) ?: Instant.ofEpochMilli(eventTimestampMs).atZone(ZoneId.systemDefault()).toLocalDate(),
            bankCode = bankCode,
            note = note,
        )
    }

    private fun money(raw: String): BigDecimal? =
        raw.replace(",", "").toBigDecimalOrNull()?.setScale(2, java.math.RoundingMode.HALF_UP)

    private fun textDate(text: String): LocalDate? {
        val m = date.find(text) ?: return null
        return try {
            if (m.groups[1] != null) {
                LocalDate.of(year(m.groupValues[3]), m.groupValues[2].toInt(), m.groupValues[1].toInt())
            } else {
                val month = Month.entries.firstOrNull { it.name.startsWith(m.groupValues[5].uppercase()) } ?: return null
                LocalDate.of(year(m.groupValues[6]), month, m.groupValues[4].toInt())
            }
        } catch (_: java.time.DateTimeException) {
            null
        }
    }

    private fun year(raw: String): Int = raw.toInt().let { if (raw.length <= 2) 2000 + it else it }
}
