package com.personal.financecollector.ui

import java.time.Instant

/** Spreadsheet-ready export of transactions as the user sees them (corrections included). */
object CsvExport {
    private val header = listOf(
        "Date", "Received (IST)", "Direction", "Amount", "Payee", "What for", "Category", "Own transfer",
        "Payment mode", "Account", "Reference", "Balance after", "Note", "Seen in", "Split",
    )

    fun build(list: List<LedgerTxn>): String {
        val rows = list.map { t ->
            listOf(
                t.date.toString(),
                Instant.ofEpochMilli(t.timeMs).atZone(LedgerBuilder.IST).toLocalDateTime().toString().replace('T', ' '),
                if (t.credit) "IN" else "OUT",
                t.amount.toPlainString(),
                t.counterparty.orEmpty(),
                t.label.orEmpty(),
                t.category,
                if (t.internalTransfer) "yes" else "no",
                t.paymentMode.orEmpty(),
                t.accountLast4.orEmpty(),
                t.referenceNo.orEmpty(),
                t.balanceAfter?.toPlainString().orEmpty(),
                t.note.orEmpty(),
                t.sources.joinToString("+"),
                t.splits.joinToString("; ") { "${it.name?.let { n -> "$n / " }.orEmpty()}${it.category} ${it.amount.toPlainString()}" },
            )
        }
        return (listOf(header) + rows).joinToString("\r\n") { r -> r.joinToString(",") { cell(it) } } + "\r\n"
    }

    /** A cell that starts like a formula is prefixed so a spreadsheet shows it as text instead of running it. */
    fun cell(value: String): String {
        val safe = if (value.isNotEmpty() && value[0] in "=+-@\t\r" && !value.matches(Regex("-?[0-9]+(\\.[0-9]+)?"))) "'$value" else value
        return "\"" + safe.replace("\"", "\"\"") + "\""
    }
}
