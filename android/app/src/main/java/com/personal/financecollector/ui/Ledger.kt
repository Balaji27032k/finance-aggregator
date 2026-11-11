package com.personal.financecollector.ui

import com.personal.financecollector.collector.ParsedTransaction
import com.personal.financecollector.collector.TransactionParser
import com.personal.financecollector.database.RawFinancialEventEntity
import com.personal.financecollector.database.SourceType
import com.personal.financecollector.database.UploadStatus
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** One real transaction, merged from every channel (SMS, e-mail, notification) that reported it. */
data class LedgerTxn(
    val key: String,
    val credit: Boolean,
    val amount: BigDecimal,
    val counterparty: String?,
    val paymentMode: String?,
    val accountLast4: String?,
    val counterpartyAccountLast4: String?,
    val referenceNo: String?,
    val balanceAfter: BigDecimal?,
    val note: String?,
    val bankCode: String,
    val date: LocalDate,
    val timeMs: Long,
    val category: String,
    val sources: List<String>,
    val uploaded: Boolean,
) {
    val title: String get() = counterparty?.let(::prettyName) ?: paymentMode?.let { "$it ${if (credit) "credit" else "payment"}" } ?: if (credit) "Money received" else "Money sent"
}

/** Banks often send names in capitals; show them in normal case. Names that already have lower case are left alone. */
fun prettyName(name: String): String =
    if (name.length > 3 && name.any { it.isLetter() } && name == name.uppercase()) {
        name.lowercase().split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
    } else name

/** Same rules as the server uses to group channels, so the phone and the dashboard agree. */
object LedgerBuilder {
    val IST: ZoneId = ZoneId.of("Asia/Kolkata")
    private const val WINDOW_MS = 3 * 60_000L
    private const val MIN_REFERENCE = 9

    private class Item(val event: RawFinancialEventEntity, val parsed: ParsedTransaction) {
        val rank: Int = when (event.sourceType) {
            SourceType.GMAIL -> 0
            SourceType.SMS -> 1
            else -> 2
        }
    }

    private class Group(first: Item) {
        val items = mutableListOf(first)
        val last: Item get() = items.last()
    }

    fun build(events: List<RawFinancialEventEntity>, parse: (RawFinancialEventEntity) -> ParsedTransaction? = TransactionParser::parse): List<LedgerTxn> {
        val items = events.sortedBy { it.eventTimestamp }.mapNotNull { e -> parse(e)?.let { Item(e, it) } }
        val groups = mutableListOf<Group>()
        val byReference = HashMap<String, Group>()

        for (item in items) {
            val p = item.parsed
            val reference = p.referenceNo?.takeIf { it.length >= MIN_REFERENCE }
            val refKey = reference?.let { "$it|${p.direction}|${p.amount.toPlainString()}" }
            var group = refKey?.let { byReference[it] }
            if (group == null) {
                group = nearby(groups, item, reference)
            }
            if (group == null) {
                group = Group(item)
                groups += group
            } else {
                group.items += item
            }
            if (refKey != null) byReference.putIfAbsent(refKey, group)
        }
        return groups.map { toTxn(it) }.sortedByDescending { it.timeMs }
    }

    private fun nearby(groups: List<Group>, item: Item, reference: String?): Group? {
        val p = item.parsed
        for (i in groups.indices.reversed()) {
            val g = groups[i]
            val gap = item.event.eventTimestamp - g.last.event.eventTimestamp
            if (gap > WINDOW_MS) break
            val q = g.last.parsed
            if (q.direction != p.direction || q.amount.compareTo(p.amount) != 0) continue
            if (g.items.any { it.event.sourceType == item.event.sourceType }) continue
            if (reference != null && q.referenceNo != null && q.referenceNo != reference) continue
            if (!compatible(p.counterparty, q.counterparty)) continue
            return g
        }
        return null
    }

    internal fun compatible(a: String?, b: String?): Boolean {
        if (a == null || b == null) return true
        val left = words(a)
        val right = words(b)
        return left.any { x -> right.any { y -> x.startsWith(y) || y.startsWith(x) } }
    }

    private fun words(value: String): Set<String> =
        value.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length >= 3 }.toSet()

    private fun toTxn(group: Group): LedgerTxn {
        val ordered = group.items.sortedWith(compareBy<Item> { it.rank }.thenBy { it.event.eventTimestamp })
        fun <T> pick(field: (ParsedTransaction) -> T?): T? = ordered.firstNotNullOfOrNull { field(it.parsed) }
        val first = ordered.first()
        val earliest = group.items.minOf { it.event.eventTimestamp }
        val counterparty = pick { it.counterparty }
        val mode = pick { it.paymentMode }
        val note = pick { it.note }
        val credit = first.parsed.direction == "CREDIT"
        val date = pick { it.transactionDate } ?: Instant.ofEpochMilli(earliest).atZone(IST).toLocalDate()
        return LedgerTxn(
            key = first.event.localEventId,
            credit = credit,
            amount = first.parsed.amount,
            counterparty = counterparty,
            paymentMode = mode,
            accountLast4 = pick { it.accountLast4 },
            counterpartyAccountLast4 = pick { it.counterpartyAccountLast4 },
            referenceNo = pick { it.referenceNo },
            balanceAfter = pick { it.balanceAfter },
            note = note,
            bankCode = first.parsed.bankCode,
            date = date,
            timeMs = earliest,
            category = Categories.of(credit, counterparty, mode, note),
            sources = ordered.map { sourceName(it.event.sourceType) }.distinct(),
            uploaded = group.items.all { it.event.uploadStatus == UploadStatus.SYNCED },
        )
    }

    private fun sourceName(type: SourceType): String = when (type) {
        SourceType.GMAIL -> "Email"
        SourceType.SMS -> "SMS"
        else -> "Notification"
    }
}

/** Display-only labels based on the payee and note as received; nothing is renamed or stored. */
object Categories {
    const val OTHER = "Other"

    private val rules: List<Pair<String, Regex>> = listOf(
        "Investments" to r("groww|zerodha|angel|indmoney|upstox|mutual fund|\\bsip\\b|\\bnse\\b|\\bbse\\b|axis mf|nippon"),
        "Rent & housing" to r("\\brent\\b|landlord|housing|apartment|society|maintenance|\\bpg\\b"),
        "Bills & recharge" to r("jio|airtel|vodafone|\\bvi\\b|bsnl|recharge|electricity|tneb|tangedco|broadband|\\bdth\\b|postpaid|prepaid|insurance|uiic|\\blic\\b|gas |water board|autopay|mandate|sms charge"),
        "Transport" to r("transport|metro|\\bbus\\b|railway|irctc|redbus|rapido|uber|\\bola\\b|cumta|setc|tnstc|petrol|fuel|toll|fastag|parking|\\bcab\\b|auto fare|traffic"),
        "Food & drinks" to r("hungerbox|swiggy|zomato|restaurant|hotel|cafe|coffee|\\btea\\b|bakery|biryani|\\bfood|eatgood|eats|kitchen|canteen|sweets|juice|pizza|burger|meal|mess\\b|dosa|idly|snack|ice cream|bhojan|foods|catering"),
        "Health" to r("pharma|medical|medi\\b|hospital|clinic|\\blab\\b|apollo|health|diagnostic|dental"),
        "Shopping" to r("amazon|flipkart|myntra|ajio|meesho|\\bmart\\b|supermarket|\\bstores?\\b|wholesale|fashion|\\bmall\\b|bazaar|retail|jewel|textile|trends|max "),
        "Entertainment & apps" to r("google|spotify|netflix|prime video|hotstar|youtube|bookmyshow|play store|gpay-tv|apple|subscription"),
    )

    private fun r(pattern: String) = Regex(pattern, RegexOption.IGNORE_CASE)

    fun of(credit: Boolean, counterparty: String?, mode: String?, note: String?): String {
        val text = "${counterparty.orEmpty()} ${note.orEmpty()}".lowercase()
        if (credit && text.contains("salary")) return "Salary"
        if (mode.equals("ATM", ignoreCase = true)) return if (credit) "Cash deposit" else "Cash withdrawal"
        rules.firstOrNull { it.second.containsMatchIn(text) }?.let { return it.first }
        if (text.contains("interest")) return "Interest"
        return if (credit) "Money received" else OTHER
    }
}
