package com.personal.financecollector.collector

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.DateTimeException
import java.time.LocalDate
import java.time.Month
import java.time.ZoneId

/** One line of a bank statement. [debit] and [credit] are never both set. */
data class StatementRow(
    val date: LocalDate,
    val description: String,
    val reference: String?,
    val debit: BigDecimal?,
    val credit: BigDecimal?,
    val balance: BigDecimal?,
)

data class StatementParse(val rows: List<StatementRow>, val skipped: Int, val problem: String?)

/**
 * Reads a statement downloaded from net banking or YONO as CSV (comma, semicolon or tab separated) and turns each line into the
 * same shape the app gets from a bank message. Lines it cannot read with confidence are counted as skipped, never guessed.
 */
object StatementParser {
    private val IST: ZoneId = ZoneId.of("Asia/Kolkata")

    fun parse(text: String): StatementParse {
        val clean = text.removePrefix("\uFEFF")
        if (clean.startsWith("PK") || clean.startsWith("\u00D0\u00CF\u0011\u00E0")) {
            return StatementParse(emptyList(), 0, "This is an Excel file. Open it and use Save As > CSV, then import the CSV.")
        }
        val lines = clean.lines()
        val headerIndex = lines.indexOfFirst { isHeader(it) }
        if (headerIndex < 0) return StatementParse(emptyList(), 0, "Could not find the column headings (date, description, debit, credit, balance).")
        val delimiter = listOf(',', '\t', ';').maxBy { d -> lines[headerIndex].count { it == d } }
        val header = split(lines[headerIndex], delimiter).map { it.trim().lowercase() }

        fun column(vararg words: String): Int = words.firstNotNullOfOrNull { w -> header.indexOfFirst { it.contains(w) }.takeIf { it >= 0 } } ?: -1
        val dateCol = column("txn date", "transaction date", "post date", "date").takeIf { it >= 0 } ?: return noColumns()
        val descCol = column("description", "narration", "particulars", "details", "remarks").takeIf { it >= 0 } ?: return noColumns()
        val debitCol = column("debit", "withdrawal").takeIf { it >= 0 } ?: return noColumns()
        val creditCol = column("credit", "deposit").takeIf { it >= 0 } ?: return noColumns()
        val refCol = column("ref no", "ref.", "cheque", "chq", "reference")
        val balanceCol = column("balance")

        val rows = mutableListOf<StatementRow>()
        var skipped = 0
        for (line in lines.drop(headerIndex + 1)) {
            if (line.isBlank()) continue
            val cells = split(line, delimiter)
            fun cell(i: Int) = if (i in cells.indices) cells[i].trim() else ""
            val date = parseDate(cell(dateCol))
            val debit = money(cell(debitCol))
            val credit = money(cell(creditCol))
            if (date == null) {
                // Opening/closing notes and totals at the foot of a statement have no date; they are not transactions.
                if (cells.any { it.isNotBlank() } && money(cell(debitCol)) != null) skipped++
                continue
            }
            if ((debit == null) == (credit == null)) {
                skipped++
                continue
            }
            rows += StatementRow(
                date = date,
                description = cell(descCol).replace(Regex("""\s+"""), " "),
                reference = if (refCol >= 0) cell(refCol).takeIf { it.isNotBlank() && it != "-" } else null,
                debit = debit,
                credit = credit,
                balance = if (balanceCol >= 0) money(cell(balanceCol)) else null,
            )
        }
        return StatementParse(rows, skipped, if (rows.isEmpty()) "No transactions were found in this file." else null)
    }

    private fun noColumns() = StatementParse(emptyList(), 0, "The file does not have date, description, debit and credit columns.")

    private fun isHeader(line: String): Boolean {
        val l = line.lowercase()
        return l.contains("date") && (l.contains("debit") || l.contains("withdrawal")) && (l.contains("credit") || l.contains("deposit"))
    }

    /** Splits one CSV line, honouring double quotes so a comma inside a description does not break the columns. */
    internal fun split(line: String, delimiter: Char): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && quoted && i + 1 < line.length && line[i + 1] == '"' -> { cur.append('"'); i++ }
                c == '"' -> quoted = !quoted
                c == delimiter && !quoted -> { out += cur.toString(); cur.clear() }
                else -> cur.append(c)
            }
            i++
        }
        out += cur.toString()
        return out
    }

    private fun money(raw: String): BigDecimal? {
        val v = raw.replace(",", "").replace("\u20b9", "").replace(Regex("""(?i)\s*(?:cr|dr)\.?$"""), "").trim()
        if (v.isEmpty() || v == "-") return null
        return v.toBigDecimalOrNull()?.setScale(2, RoundingMode.HALF_UP)
    }

    private val monthNames = Month.entries.associateBy { it.name.take(3) }

    internal fun parseDate(raw: String): LocalDate? {
        val s = raw.trim().replace(Regex("""\s+"""), " ")
        if (s.isEmpty()) return null
        return try {
            Regex("""^(\d{4})[-/](\d{1,2})[-/](\d{1,2})""").find(s)?.let { LocalDate.of(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3].toInt()) }
                ?: Regex("""^(\d{1,2})[-/. ]([A-Za-z]{3})[A-Za-z]*[-/. ,]*(\d{2,4})""").find(s)?.let {
                    val month = monthNames[it.groupValues[2].uppercase()] ?: return null
                    LocalDate.of(year(it.groupValues[3]), month, it.groupValues[1].toInt())
                }
                ?: Regex("""^(\d{1,2})[-/.](\d{1,2})[-/.](\d{2,4})""").find(s)?.let { LocalDate.of(year(it.groupValues[3]), it.groupValues[2].toInt(), it.groupValues[1].toInt()) }
        } catch (_: DateTimeException) {
            null
        }
    }

    private fun year(raw: String): Int = raw.toInt().let { if (raw.length <= 2) 2000 + it else it }

    /** Noon India time on the day: a statement has dates but no times, and noon keeps either neighbouring day within a day and a half. */
    fun timestampOf(date: LocalDate): Long = date.atTime(12, 0).atZone(IST).toInstant().toEpochMilli()

    // ---- the stored form of one statement line, so it travels through the same pipeline as a message ----

    private const val TAG = "STMT"

    fun payload(bank: String, last4: String, row: StatementRow): String = listOf(
        TAG, bank.clean(), last4, row.date.toString(), if (row.debit != null) "DR" else "CR",
        (row.debit ?: row.credit)!!.toPlainString(), row.balance?.toPlainString().orEmpty(), row.reference.orEmpty().clean(), row.description.clean(),
    ).joinToString("\t")

    private fun String.clean() = replace('\t', ' ').replace('\n', ' ').trim()

    fun parseEvent(payload: String): ParsedTransaction? {
        val p = payload.split('\t')
        if (p.size < 9 || p[0] != TAG) return null
        val date = try { LocalDate.parse(p[3]) } catch (_: DateTimeException) { return null }
        val amount = p[5].toBigDecimalOrNull()?.takeIf { it.signum() > 0 } ?: return null
        val n = TransactionParser.narration(p[8])
        val ref = (n.reference ?: p[7].takeIf { it.isNotBlank() })
        return ParsedTransaction(
            direction = if (p[4] == "DR") "DEBIT" else "CREDIT",
            amount = amount,
            currency = "INR",
            accountLast4 = p[2].takeIf { it.isNotBlank() },
            counterparty = n.counterparty,
            counterpartyAccountLast4 = null,
            paymentMode = n.mode,
            referenceNo = ref?.takeIf { it.length >= 6 },
            balanceAfter = p[6].toBigDecimalOrNull(),
            transactionDate = date,
            bankCode = p[1].ifBlank { "STATEMENT" },
            note = n.note,
        )
    }
}
