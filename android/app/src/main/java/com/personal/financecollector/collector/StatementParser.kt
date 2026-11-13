package com.personal.financecollector.collector

import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.DateTimeException
import java.time.LocalDate
import java.time.Month
import java.time.ZoneId
import java.util.zip.ZipInputStream

/** One line of a bank statement. [debit] and [credit] are never both set. */
data class StatementRow(
    val date: LocalDate,
    val description: String,
    val reference: String?,
    val debit: BigDecimal?,
    val credit: BigDecimal?,
    val balance: BigDecimal?,
)

/** [accountNumber] is the account the statement says it is for, when the file says so. */
data class StatementParse(val rows: List<StatementRow>, val skipped: Int, val problem: String?, val accountNumber: String? = null)

/**
 * Reads a statement downloaded from net banking or YONO (CSV, or .xlsx with or without a password) and turns each line into the
 * same shape the app gets from a bank message. Lines it cannot read with confidence are counted as skipped, never guessed.
 */
object StatementParser {
    private val IST: ZoneId = ZoneId.of("Asia/Kolkata")
    private val I = setOf(RegexOption.IGNORE_CASE)

    fun parseFile(bytes: ByteArray, password: String?): StatementParse {
        if (OfficeCrypto.isCompoundFile(bytes)) {
            return when (val r = OfficeCrypto.open(bytes, password)) {
                is OfficeResult.Opened -> parseXlsx(r.bytes)
                OfficeResult.NeedsPassword -> problem("This file is password protected. Type its password above and choose the file again.")
                OfficeResult.WrongPassword -> problem("That password did not open the file.")
                is OfficeResult.Unsupported -> problem(r.reason)
            }
        }
        if (bytes.size > 1 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) return parseXlsx(bytes)
        return parse(String(bytes, Charsets.UTF_8))
    }

    private fun problem(text: String) = StatementParse(emptyList(), 0, text)

    private fun parseXlsx(bytes: ByteArray): StatementParse {
        val rows = try {
            XlsxReader.rows(bytes)
        } catch (e: Exception) {
            null
        } ?: return problem("This Excel file could not be read. Save the statement as CSV from Excel and import that.")
        return parseRows(rows)
    }

    fun parse(text: String): StatementParse {
        val clean = text.removePrefix("\uFEFF")
        val rows = listOf(',', '\t', ';').firstNotNullOfOrNull { d ->
            csvRows(clean, d).takeIf { r -> r.any { row -> row.size >= 4 && isHeader(row) } }
        } ?: return problem("Could not find the column headings (date, details, debit, credit, balance).")
        return parseRows(rows)
    }

    private fun parseRows(rows: List<List<String>>): StatementParse {
        val headerIndex = rows.indexOfFirst { it.size >= 4 && isHeader(it) }
        if (headerIndex < 0) return problem("Could not find the column headings (date, details, debit, credit, balance).")
        val header = rows[headerIndex].map { it.trim().lowercase() }

        fun column(vararg words: String): Int = words.firstNotNullOfOrNull { w -> header.indexOfFirst { it.contains(w) }.takeIf { it >= 0 } } ?: -1
        val dateCol = column("txn date", "transaction date", "post date", "date")
        val descCol = column("description", "narration", "particulars", "details", "remarks")
        val debitCol = column("debit", "withdrawal")
        val creditCol = column("credit", "deposit")
        if (minOf(dateCol, descCol, debitCol, creditCol) < 0) return problem("The file does not have date, details, debit and credit columns.")
        val refCol = column("ref no", "ref.", "cheque", "chq", "reference")
        val balanceCol = column("balance")

        val accountNumber = rows.take(headerIndex).flatten()
            .firstNotNullOfOrNull { Regex("""Account\s*Number\s*:?\s*(\d{6,})""", I).find(it)?.groupValues?.get(1) }

        val out = mutableListOf<StatementRow>()
        var skipped = 0
        for (cells in rows.drop(headerIndex + 1)) {
            fun cell(i: Int) = if (i in cells.indices) cells[i].trim() else ""
            // Totals, notes and the bank's footer have no date; they are not transactions.
            val date = parseDate(cell(dateCol)) ?: continue
            val debit = money(cell(debitCol))
            val credit = money(cell(creditCol))
            if ((debit == null) == (credit == null)) {
                skipped++
                continue
            }
            out += StatementRow(
                date = date,
                description = description(cell(descCol)),
                reference = if (refCol >= 0) cell(refCol).takeIf { it.isNotBlank() && it != "-" } else null,
                debit = debit,
                credit = credit,
                balance = if (balanceCol >= 0) money(cell(balanceCol)) else null,
            )
        }
        return StatementParse(out, skipped, if (out.isEmpty()) "No transactions were found in this file." else null, accountNumber)
    }

    /** Banks wrap long text onto a new line that starts with a space, in the middle of a word; that break carries no space. */
    internal fun description(raw: String): String =
        raw.replace("\r\n", "\n").replace("\n ", "").replace('\n', ' ').replace(Regex("""\s+"""), " ").trim()

    private fun isHeader(cells: List<String>): Boolean {
        val l = cells.joinToString(" ").lowercase()
        return l.contains("date") && (l.contains("debit") || l.contains("withdrawal")) && (l.contains("credit") || l.contains("deposit"))
    }

    /** Splits text into rows of cells; double quotes may hold the delimiter and line breaks. */
    internal fun csvRows(text: String, delimiter: Char): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val cur = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                quoted && c == '"' && i + 1 < text.length && text[i + 1] == '"' -> { cur.append('"'); i++ }
                c == '"' -> quoted = !quoted
                quoted -> cur.append(c)
                c == delimiter -> { row += cur.toString(); cur.clear() }
                c == '\n' -> { row += cur.toString(); cur.clear(); rows += row; row = mutableListOf() }
                c == '\r' -> Unit
                else -> cur.append(c)
            }
            i++
        }
        if (cur.isNotEmpty() || row.isNotEmpty()) { row += cur.toString(); rows += row }
        return rows
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
                // A date typed as a date in Excel is a count of days since 30 Dec 1899.
                ?: Regex("""^(\d{5})(?:\.\d+)?$""").find(s)?.let { LocalDate.of(1899, 12, 30).plusDays(it.groupValues[1].toLong()) }
        } catch (_: DateTimeException) {
            null
        }
    }

    private fun year(raw: String): Int = raw.toInt().let { if (raw.length <= 2) 2000 + it else it }

    /** Noon India time on the day: a statement has dates but no times. */
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
        val n = narrate(p[8])
        val ref = n.reference ?: p[7].takeIf { it.length >= 6 }
        return ParsedTransaction(
            direction = if (p[4] == "DR") "DEBIT" else "CREDIT",
            amount = amount,
            currency = "INR",
            accountLast4 = p[2].takeIf { it.isNotBlank() },
            counterparty = n.counterparty,
            counterpartyAccountLast4 = null,
            paymentMode = n.mode,
            referenceNo = ref,
            balanceAfter = p[6].toBigDecimalOrNull(),
            transactionDate = date,
            bankCode = p[1].ifBlank { "STATEMENT" },
            note = n.note,
        )
    }

    internal data class Narration(val counterparty: String?, val mode: String?, val reference: String?, val note: String?)

    private val branchTail = Regex("""\s+\d{10,}\s+AT\s+\d{3,5}\b.*$""", I)
    private val rail = Regex("""^(?:WDL|DEP)\s+TFR\s+|^(?:TO|BY)\s+TRANSFER\s*[-:]?\s*""", I)
    private val upi = Regex("""UPI/(?:DR|CR)/(\d{6,})/([^/]*)/[A-Za-z0-9]{3,4}/([^/]*)/?(.*)$""", I)
    private val upiReversal = Regex("""UPI/REV(?:ERSAL)?/(\d{9,})""", I)
    private val imps = Regex("""IMPS[/-]?(?:P2[AP][/-])?(\d{9,})""", I)
    private val neft = Regex("""NEFT\*[^*]*\*([^*]*)\*(.*)$""", I)
    private val pos = Regex("""(?:OTHPOS|OTHPG|SBIPOS)\s*(\d{12})(.*)$""", I)
    private val charges = Regex("""ATMCARD\s+AMC|DECLINE\s+CHARGE""", I)
    private val genericRemark = Regex("""^(?:upi\w*(?:\s+\w)?|pay\w*(?:\s+to)?|paid\w*|sent\w*)$""", I)

    internal fun narrate(raw: String): Narration {
        val t = raw.replace(branchTail, "").replace(rail, "").trim()
        upi.find(t)?.let { m ->
            val remark = m.groupValues[4].trim().takeIf { it.isNotEmpty() && !genericRemark.matches(it) }
            val name = m.groupValues[2].trim().ifEmpty { m.groupValues[3].trim() }.ifEmpty { null }
            return Narration(name, "UPI", m.groupValues[1], remark)
        }
        upiReversal.find(t)?.let { return Narration(null, "UPI", it.groupValues[1], "UPI reversal") }
        neft.find(t)?.let { m -> return Narration(m.groupValues[2].trim().ifEmpty { null }, "NEFT", m.groupValues[1].trim().ifEmpty { null }, null) }
        imps.find(t)?.let { return Narration(null, "IMPS", it.groupValues[1], "IMPS transfer") }
        pos.find(t)?.let { m -> return Narration(m.groupValues[2].trim().ifEmpty { null }, "CARD", m.groupValues[1], null) }
        return when {
            t.startsWith("ATM WDL", true) || t.startsWith("CASH DEPOSIT", true) -> Narration(null, "ATM", null, null)
            t.startsWith("REVERSE", true) -> Narration(null, null, null, "Reversal")
            charges.containsMatchIn(t) -> Narration("Bank charges", null, null, null)
            t.contains("INTERES", true) -> Narration("Interest", null, null, "Interest")
            else -> Narration(t.take(60).ifBlank { null }, null, null, null)
        }
    }
}

/** Reads the first worksheet of an .xlsx as rows of text, without an XML parser so nothing in the file can reach the network. */
internal object XlsxReader {
    private const val MAX_ENTRY = 64L * 1024 * 1024

    fun rows(bytes: ByteArray): List<List<String>>? {
        val files = HashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                val n = e.name
                if (e.isDirectory || !(n == "xl/sharedStrings.xml" || n == "xl/workbook.xml" || n == "xl/_rels/workbook.xml.rels" || n.startsWith("xl/worksheets/"))) continue
                val buf = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(8192)
                while (true) {
                    val r = zip.read(chunk)
                    if (r < 0) break
                    buf.write(chunk, 0, r)
                    if (buf.size() > MAX_ENTRY) return null
                }
                files[n] = buf.toByteArray()
            }
        }
        val sheet = firstSheet(files) ?: return null
        val shared = files["xl/sharedStrings.xml"]?.let { sharedStrings(String(it, Charsets.UTF_8)) } ?: emptyList()
        return sheetRows(String(sheet, Charsets.UTF_8), shared)
    }

    private fun attrs(tag: String): Map<String, String> =
        Regex("""([A-Za-z:]+)="([^"]*)"""").findAll(tag).associate { it.groupValues[1] to it.groupValues[2] }

    private fun firstSheet(files: Map<String, ByteArray>): ByteArray? {
        val workbook = files["xl/workbook.xml"]?.let { String(it, Charsets.UTF_8) }
        val rels = files["xl/_rels/workbook.xml.rels"]?.let { String(it, Charsets.UTF_8) }
        if (workbook != null && rels != null) {
            val id = Regex("""<sheet\b[^>]*>""").find(workbook)?.let { attrs(it.value)["r:id"] }
            val target = id?.let { want ->
                Regex("""<Relationship\b[^>]*>""").findAll(rels).map { attrs(it.value) }.firstOrNull { it["Id"] == want }?.get("Target")
            }
            if (target != null) {
                val path = target.trimStart('/').let { if (it.startsWith("xl/")) it else "xl/$it" }
                files[path]?.let { return it }
            }
        }
        return files.filterKeys { Regex("""xl/worksheets/sheet\d+\.xml""").matches(it) }.minByOrNull { it.key }?.value
    }

    private fun unescape(s: String): String = Regex("""&(#x[0-9A-Fa-f]+|#\d+|amp|lt|gt|quot|apos);""").replace(s) {
        when (val e = it.groupValues[1]) {
            "amp" -> "&"
            "lt" -> "<"
            "gt" -> ">"
            "quot" -> "\""
            "apos" -> "'"
            else -> (if (e[1] == 'x') e.drop(2).toIntOrNull(16) else e.drop(1).toIntOrNull())?.let { c -> String(Character.toChars(c)) } ?: ""
        }
    }

    private fun texts(xml: String): String =
        Regex("""<t\b[^>]*>(.*?)</t>""", RegexOption.DOT_MATCHES_ALL).findAll(xml).joinToString("") { unescape(it.groupValues[1]) }

    private fun sharedStrings(xml: String): List<String> =
        Regex("""<si\b[^>]*?(?:/>|>(.*?)</si>)""", RegexOption.DOT_MATCHES_ALL).findAll(xml).map { texts(it.groupValues[1]) }.toList()

    private fun columnIndex(ref: String): Int {
        var n = 0
        for (c in ref) {
            if (c !in 'A'..'Z') break
            n = n * 26 + (c - 'A' + 1)
        }
        return n - 1
    }

    private fun sheetRows(xml: String, shared: List<String>): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        for (row in Regex("""<row\b[^>]*>(.*?)</row>""", RegexOption.DOT_MATCHES_ALL).findAll(xml)) {
            val cells = ArrayList<String>()
            for (c in Regex("""<c\b([^>]*?)(?:/>|>(.*?)</c>)""", RegexOption.DOT_MATCHES_ALL).findAll(row.groupValues[1])) {
                val a = attrs(c.groupValues[1])
                val inner = c.groupValues[2]
                val v = Regex("""<v>(.*?)</v>""", RegexOption.DOT_MATCHES_ALL).find(inner)?.groupValues?.get(1)?.let { unescape(it) }
                val value = when (a["t"]) {
                    "s" -> v?.toIntOrNull()?.let { shared.getOrNull(it) }.orEmpty()
                    "inlineStr" -> texts(inner)
                    else -> v.orEmpty()
                }
                val at = a["r"]?.let { columnIndex(it) } ?: cells.size
                while (cells.size <= at) cells += ""
                cells[at] = value
            }
            rows += cells
        }
        return rows
    }
}
