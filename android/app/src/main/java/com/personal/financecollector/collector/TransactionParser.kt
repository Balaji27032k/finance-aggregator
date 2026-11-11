package com.personal.financecollector.collector

import com.personal.financecollector.database.RawFinancialEventEntity
import com.personal.financecollector.database.SourceType
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.DateTimeException
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
 * Returns null whenever the message is not a completed money movement or cannot be read with confidence:
 * OTPs, declines, upcoming-mandate notices, payment requests, balance replies and offers never become transactions.
 */
object TransactionParser {
    const val VERSION = "parser-3"

    /** Sender entities whose SMS describe movements on the user's own accounts or cards. */
    val BANK_SENDERS = setOf("INDBNK", "CSBBNK", "SBIUPI", "SBIPSG", "SBIBNK", "SBYONO", "ATMSBI", "CBSSBI", "SBIINB", "ICICIT")

    private const val CUR = """(?<![A-Za-z])(?:Rs\.?|INR|₹)"""
    private const val NUM = """[0-9][0-9,]*(?:\.[0-9]{1,2})?"""
    private const val OPT_CUR = """(?:Rs\.?\s*)?(?:INR\s*)?"""
    private val I = setOf(RegexOption.IGNORE_CASE)

    private val debitWord = Regex(
        """\b(?:debited|withdrawn|w/d|spent|sent(?=\s+(?:rs|inr|₹))|has\s+a\s+debit|debit(?=\s+\d)|""" +
            """transaction\s+number\s+\d+\s+for\s+(?:rs|inr))\b""",
        I,
    )
    private val creditWord = Regex("""\b(?:credited|deposited|has\s+credit|credit(?=\s+\d))\b""", I)

    private val amountAfterWord = Regex(
        """\b(?:debited|withdrawn|spent|sent|credited|deposited|debit|credit)\s*(?:by|for|with|of)?\s*$OPT_CUR($NUM)""", I,
    )
    private val currencyAmount = Regex("""$CUR\s*(?:INR\s*)?($NUM)""", I)
    private val balance = Regex(
        """\b(?:(?:avl|avail(?:able)?|current|cur|clear|updated(?:\s+available)?(?:\s+account)?)\.?\s*)?bal(?:ance)?\.?[\s:\-]*(?:is\s*)?$OPT_CUR($NUM)""",
        I,
    )

    private val account = Regex("""\bA/?c(?:count)?\.?\s*(?:No\.?|number)?\.?\s*(?:ending(?:\s+with)?\s*)?[*Xx.\-]*\s*([0-9]{3,})""", I)
    private val card = Regex("""\bcard\s*(?:no\.?|ending(?:\s+with)?)?\s*[*Xx\-]*([0-9]{4})\b""", I)

    private const val STOP = """(?=[.,(]|\s+(?:RRN|Ref|UPI|If\s|Avl|Avail|Not\s|from\s|on\s+\d|on\s+date|dt\s|Ac\s|A/c\s)|\s*$)"""
    private val payee = Regex("""\b(?:to|at)\s+(?!your\b|A/c\b)(?=[A-Za-z])(.+?)$STOP""", I)
    private val payer = Regex("""\b(?:by|from)\s+(?!A/c\b)(?!(?:Rs\.?|INR|₹)?\s*\d)(?=[A-Za-z])(.+?)$STOP""", I)
    private val linked = Regex("""linked to (?:mobile(?: number)?|VPA)\s+([A-Za-z0-9X@._\-]+)""", I)
    private val vpa = Regex("""([A-Za-z0-9._\-]+@[A-Za-z0-9]+)""")
    private val upiStructured = Regex("""UPI/(?:DR|CR)/(\d{6,})/([^/]*)/([A-Za-z0-9]{3,4})/([^/]*)/(.*?)(?=\s+on\s+\d|\s*$)""", I)
    private val impsStructured = Regex("""IMPS-+(\d{9,})-([^-]+)-[A-Za-z]{4}0[A-Za-z0-9]{6}-[*Xx]*(\d{3,})""", I)
    private val reference = Regex(
        """(?:UPI/(?:DR|CR|REVERSAL)/|UPI\s*Ref\.?\s*no\.?|IMPS\s*Ref\.?\s*no\.?|UPI|IMPS|RRN|Ref\s*no\.?|Ref|""" +
            """UTR(?:\s*No\.?)?|Transaction\s*(?:Number|No\.?))\s*[:\-]?\s*((?=[A-Za-z0-9]*\d)[A-Za-z0-9]{6,})""",
        I,
    )
    private val date = Regex(
        """\b(?:on|dt|dated)\s+(?:date\s+)?(?:(\d{4})[-/](\d{1,2})[-/](\d{1,2})|(\d{1,2})[-/.](\d{1,2})[-/.](\d{2,4})|""" +
            """(\d{1,2})[-/ ]?([A-Za-z]{3})[-/ ]?(\d{2,4}))""",
        I,
    )
    private val info = Regex("""INFO:\s*(.+)$""", I)
    private val towards = Regex("""\btowards\s+(.+?)(?=\s+on\s+\d|\.\s|\.$|$)""", I)
    private val footer = Regex("""\s*-\s*(?:SBI|Indian Bank|CSB Bank|CSB)\s*$""", I)

    private val notAMovement = listOf(
        Regex("""\bOTP\s+(?:is|for|to|code)\b|\bis\s+(?:your\s+|the\s+)?OTP\b|\bOTP[:\-]|\bone[- ]time\s+password\b""", I),
        Regex("""\b(?:declined|failed|unsuccessful|unable to|not completed)\b""", I),
        Regex("""\bwill be\s+(?:auto\s+)?debited\b""", I),
        Regex("""\bupcoming mandate\b""", I),
        Regex("""\b(?:requested money|has requested|on approv(?:al|ing))\b""", I),
        Regex("""\bRecurring Deposit\b""", I),
        Regex("""\bLast transaction in\b""", I),
    )

    fun parse(event: RawFinancialEventEntity): ParsedTransaction? = when (event.sourceType) {
        SourceType.SMS -> parseSms(event.sourceApplication, event.rawPayload, event.eventTimestamp)
        SourceType.NOTIFICATION -> parseNotification(event.sourceApplication, event.rawPayload, event.eventTimestamp)
        else -> null
    }

    fun parseSms(bankCode: String, body: String, eventTimestampMs: Long): ParsedTransaction? =
        if (bankCode.uppercase() in BANK_SENDERS) parseBankText(bankCode, body, eventTimestampMs) else null

    /** Bank-app alerts mirror the bank SMS; payment apps (Google Pay, PhonePe, Paytm) use their own short wording. */
    fun parseNotification(packageName: String, text: String, eventTimestampMs: Long): ParsedTransaction? {
        val app = NotificationApps.shortName(packageName)
        return parseBankText(app, text, eventTimestampMs) ?: parsePaymentApp(app, text, eventTimestampMs)
    }

    /** The bank-alert engine shared by SMS, bank-app notifications and bank e-mails. */
    fun parseBankText(bankCode: String, body: String, eventTimestampMs: Long): ParsedTransaction? {
        val text = body.replace(Regex("""\s+"""), " ").trim()
        if (notAMovement.any { it.containsMatchIn(text) }) return null

        val debit = debitWord.find(text)
        val credit = creditWord.find(text)
        val first = listOfNotNull(debit, credit).minByOrNull { it.range.first } ?: return null
        val isDebit = first === debit

        val balanceMatch = balance.find(text)
        val amountText = amountAfterWord.find(text, first.range.first)?.takeIf { it.range.first == first.range.first }
            ?.groupValues?.get(1)
            ?: currencyAmount.findAll(text).firstOrNull { m -> balanceMatch?.groups?.get(1)?.range?.let { it.first !in m.range } != false }
                ?.groupValues?.get(1)
            ?: return null
        val amount = money(amountText) ?: return null
        if (amount.signum() <= 0) return null

        val accounts = account.findAll(text).map { it.groupValues[1].takeLast(4) }.toList()
        val cardLast4 = card.find(text)?.groupValues?.get(1)
        val structured = upiStructured.find(text)
        val imps = impsStructured.find(text)
        val referenceNo = structured?.groupValues?.get(1) ?: imps?.groupValues?.get(1) ?: reference.find(text)?.groupValues?.get(1)

        // A real alert always carries an account or card, a reference or a balance; offers and notices do not.
        if (accounts.isEmpty() && cardLast4 == null && referenceNo == null && balanceMatch == null) return null

        val name = structured?.groupValues?.get(2)?.trim()?.takeIf { it.isNotEmpty() }
            ?: imps?.groupValues?.get(2)?.trim()?.takeIf { it.isNotEmpty() }
            ?: linked.find(text)?.groupValues?.get(1)?.trimEnd('.', '-')
            ?: (if (isDebit) payee else payer).find(text)?.groupValues?.get(1)?.trim()?.take(200)?.ifBlank { null }
            ?: vpa.find(text)?.value
        val remark = structured?.groupValues?.get(5)?.trim()?.takeIf { it.isNotEmpty() && !it.equals("UPI", true) }
        val note = info.find(text)?.groupValues?.get(1)?.replace(footer, "")?.trim()
            ?: remark
            ?: special(text)
            ?: (if (structured == null && imps == null) towards.find(text)?.groupValues?.get(1)?.trim() else null)

        return ParsedTransaction(
            direction = if (isDebit) "DEBIT" else "CREDIT",
            amount = amount,
            currency = "INR",
            accountLast4 = accounts.firstOrNull() ?: cardLast4,
            counterparty = name,
            counterpartyAccountLast4 = accounts.drop(1).firstOrNull { it != accounts.first() }
                ?: imps?.groupValues?.get(3)?.takeLast(4),
            paymentMode = mode(text),
            referenceNo = referenceNo,
            balanceAfter = balanceMatch?.groupValues?.get(1)?.let { money(it) },
            transactionDate = textDate(text) ?: Instant.ofEpochMilli(eventTimestampMs).atZone(ZoneId.systemDefault()).toLocalDate(),
            bankCode = bankCode,
            note = note?.take(255)?.ifBlank { null },
        )
    }

    private const val APP_STOP = """(?=[.,!]|\s+(?:via|using|on|from|UPI|Ref|successful(?:ly)?)\b|\s*$)"""
    private val payCur = """$CUR\s*($NUM)"""
    private val paidYou = Regex("""^(.{2,60}?)\s+(?:paid|sent)\s+you\s+$payCur""", I)
    private val receivedFrom = Regex("""\b(?:received|credited)\s+$payCur(?:\s+from\s+(.+?)$APP_STOP)?""", I)
    private val amountReceived = Regex("""$payCur\s+(?:received|credited)(?:\s+from\s+(.+?)$APP_STOP)?""", I)
    private val youPaid = Regex("""\b(?:you\s+)?(?:paid|sent|payment\s+of)\s+$payCur(?:\s+(?:to|at)\s+(.+?)$APP_STOP)?""", I)
    private val amountPaid = Regex("""$payCur\s+(?:paid|sent|debited|spent)(?:\s+(?:to|at)\s+(.+?)$APP_STOP)?""", I)
    private val appNoise = Regex("""\b(?:cashback|reward|offer|scratch|won|win|coupon|reminder|due|expires?|request(?:ed)?|collect|pending)\b""", I)

    /** Short app wording such as "Name paid you Rs 500" or "You paid Rs 30 to Shop"; needs a counterparty or reference. */
    private fun parsePaymentApp(app: String, body: String, eventTimestampMs: Long): ParsedTransaction? {
        val text = body.replace(Regex("""\s+"""), " ").trim()
        if (notAMovement.any { it.containsMatchIn(text) } || appNoise.containsMatchIn(text)) return null

        val hit: Triple<String, String, String?> = paidYou.find(text)?.let { Triple("CREDIT", it.groupValues[2], it.groupValues[1]) }
            ?: receivedFrom.find(text)?.let { Triple("CREDIT", it.groupValues[1], it.groupValues[2].ifBlank { null }) }
            ?: amountReceived.find(text)?.let { Triple("CREDIT", it.groupValues[1], it.groupValues[2].ifBlank { null }) }
            ?: youPaid.find(text)?.let { Triple("DEBIT", it.groupValues[1], it.groupValues[2].ifBlank { null }) }
            ?: amountPaid.find(text)?.let { Triple("DEBIT", it.groupValues[1], it.groupValues[2].ifBlank { null }) }
            ?: return null
        val amount = money(hit.second)?.takeIf { it.signum() > 0 } ?: return null
        val name = hit.third?.trim()?.take(200)?.ifBlank { null }
        val referenceNo = reference.find(text)?.groupValues?.get(1)
        if (name == null && referenceNo == null) return null

        return ParsedTransaction(
            direction = hit.first,
            amount = amount,
            currency = "INR",
            accountLast4 = account.find(text)?.groupValues?.get(1)?.takeLast(4),
            counterparty = name,
            counterpartyAccountLast4 = null,
            paymentMode = if (app in setOf("GPAY", "PHONEPE", "PAYTM", "BHIM") || Regex("""\bUPI\b""", I).containsMatchIn(text)) "UPI" else mode(text),
            referenceNo = referenceNo,
            balanceAfter = null,
            transactionDate = textDate(text) ?: Instant.ofEpochMilli(eventTimestampMs).atZone(ZoneId.systemDefault()).toLocalDate(),
            bankCode = app,
            note = null,
        )
    }

    /** Messages whose kind matters more than any free text in them. */
    private fun special(text: String): String? = when {
        Regex("""/REVERSAL/""", I).containsMatchIn(text) -> "UPI reversal"
        Regex("""\bUPI LITE\b""", I).containsMatchIn(text) -> "UPI Lite top-up"
        Regex("""\binterest\b|\bint\.""", I).containsMatchIn(text) -> "Interest"
        else -> null
    }

    private fun mode(text: String): String? = when {
        Regex("""\b(?:mandate|autopay)\b""", I).containsMatchIn(text) -> "AUTOPAY"
        Regex("""\bUPI\b""", I).containsMatchIn(text) -> "UPI"
        Regex("""^Sent\s+Rs""", I).containsMatchIn(text) && Regex("""\bRRN\b""", I).containsMatchIn(text) -> "UPI"
        Regex("""\bIMPS\b""", I).containsMatchIn(text) -> "IMPS"
        Regex("""\bNEFT\b""", I).containsMatchIn(text) -> "NEFT"
        Regex("""\bRTGS\b""", I).containsMatchIn(text) -> "RTGS"
        Regex("""\bATM\b""", I).containsMatchIn(text) -> "ATM"
        Regex("""\b(?:debit|credit)\s+card\b""", I).containsMatchIn(text) -> "CARD"
        else -> null
    }

    private fun money(raw: String): BigDecimal? = raw.replace(",", "").toBigDecimalOrNull()?.setScale(2, RoundingMode.HALF_UP)

    private fun textDate(text: String): LocalDate? {
        val m = date.find(text) ?: return null
        return try {
            when {
                m.groups[1] != null -> LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
                m.groups[4] != null -> LocalDate.of(year(m.groupValues[6]), m.groupValues[5].toInt(), m.groupValues[4].toInt())
                else -> {
                    val month = Month.entries.firstOrNull { it.name.startsWith(m.groupValues[8].uppercase()) } ?: return null
                    LocalDate.of(year(m.groupValues[9]), month, m.groupValues[7].toInt())
                }
            }
        } catch (_: DateTimeException) {
            null
        }
    }

    private fun year(raw: String): Int = raw.toInt().let { if (raw.length <= 2) 2000 + it else it }
}
