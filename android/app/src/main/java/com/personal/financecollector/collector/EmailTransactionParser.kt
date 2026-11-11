package com.personal.financecollector.collector

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

/**
 * Turns transaction-alert e-mails from known banks into the same fields as the SMS parser.
 * Anything else (statements, newsletters, offers, OTPs) returns null.
 */
object EmailTransactionParser {
    private val I = setOf(RegexOption.IGNORE_CASE)
    private val csbCut = Regex("""\b(?:If this transaction|Warm regards|Best Regards|This is an auto)\b""", I)
    private val csbSubject = Regex("""\b(?:Debit|Credit)\s+Alert\b|\bFT-Credit\b""", I)

    private val neft = Regex(
        """Credited to Your A/c:\s*[Xx*]*(\d+)\s+Amount:\s*INR\s*([0-9][0-9,]*(?:\.\d{1,2})?)\s+UTR No\.?:\s*(\w+)\s+Date:\s*(\d{1,2})/(\d{1,2})/(\d{4})\s+Sent by:\s*(.+?)\s+Sender Bank""",
        I,
    )
    private val yonoTransfer = Regex(
        """Amount\s+Rs\.?\s*([0-9][0-9,]*(?:\.\d{1,2})?)\s+Transaction Number\s+(\w+)\s+Date of Transaction\s+(\d{1,2})\.(\d{1,2})\.(\d{2,4})\s+""" +
            """Debit account\s+[xX*]*(\d+)\s+Beneficiary Name\s+(.+?)\s+Beneficiary Account Number\s+[xX*]*(\d+)""",
        I,
    )

    fun parse(fromAddress: String, subject: String, body: String, receivedMs: Long): ParsedTransaction? {
        val from = fromAddress.lowercase()
        val text = body.replace(Regex("""\s+"""), " ").trim()
        return when {
            from.endsWith("@csb.bank.in") || from.endsWith("@csb.co.in") -> csb(subject, text, receivedMs)
            from == "neftinfo.itps@alerts.sbi.bank.in" -> sbiNeft(text)
            from == "yonobysbi@alerts.sbi.bank.in" && subject.contains("Transaction success", true) -> yono(text)
            else -> null
        }
    }

    private fun csb(subject: String, text: String, receivedMs: Long): ParsedTransaction? {
        if (!csbSubject.containsMatchIn(subject)) return null
        val alert = csbCut.find(text)?.let { text.substring(0, it.range.first) } ?: text
        return TransactionParser.parseBankText("CSB", alert, receivedMs)
    }

    private fun sbiNeft(text: String): ParsedTransaction? {
        val m = neft.find(text) ?: return null
        val date = LocalDate.of(m.groupValues[6].toInt(), m.groupValues[5].toInt(), m.groupValues[4].toInt())
        return ParsedTransaction(
            direction = "CREDIT", amount = money(m.groupValues[2]), currency = "INR", accountLast4 = m.groupValues[1].takeLast(4),
            counterparty = m.groupValues[7].trim().take(200), counterpartyAccountLast4 = null, paymentMode = "NEFT",
            referenceNo = m.groupValues[3], balanceAfter = null, transactionDate = date, bankCode = "SBI", note = null,
        )
    }

    private fun yono(text: String): ParsedTransaction? {
        val m = yonoTransfer.find(text) ?: return null
        val year = m.groupValues[5].toInt().let { if (m.groupValues[5].length <= 2) 2000 + it else it }
        return ParsedTransaction(
            direction = "DEBIT", amount = money(m.groupValues[1]), currency = "INR", accountLast4 = m.groupValues[6].takeLast(4),
            counterparty = m.groupValues[7].trim().take(200), counterpartyAccountLast4 = m.groupValues[8].takeLast(4),
            paymentMode = "IMPS", referenceNo = m.groupValues[2], balanceAfter = null,
            transactionDate = LocalDate.of(year, m.groupValues[4].toInt(), m.groupValues[3].toInt()),
            bankCode = "SBI", note = null,
        )
    }

    private fun money(raw: String): BigDecimal = raw.replace(",", "").toBigDecimal().setScale(2, RoundingMode.HALF_UP)
}
