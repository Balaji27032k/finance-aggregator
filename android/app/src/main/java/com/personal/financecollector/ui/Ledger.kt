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

/** [name] is what this part was, such as "Masala dosa"; several parts can share a category. */
data class SplitPart(val category: String, val amount: BigDecimal, val name: String? = null)

/** One channel's copy of a transaction, for example the SMS or the e-mail. */
data class TxnPart(val source: String, val kind: StatusKind, val statusText: String, val capturedMs: Long, val problem: String?)

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
    /** Every captured event (SMS, e-mail, notification) behind this one transaction; edits are saved against all of them. */
    val eventIds: List<String> = emptyList(),
    /** What the user says it was for, such as "milk". */
    val label: String? = null,
    /** True when the user changed the category or added a description. */
    val edited: Boolean = false,
    /** Moves money between the user's own accounts; left out of spending and income totals. */
    val internalTransfer: Boolean = false,
    /** How each channel's copy of this transaction stands with the server. */
    val parts: List<TxnPart> = emptyList(),
    /** The worst upload state across the channels. */
    val status: StatusKind = StatusKind.DONE,
    val problem: String? = null,
    /** The category the user picked for this one transaction, if any (a payee rule is not counted here). */
    val userCategory: String? = null,
    /** When the user split the payment between categories; the parts add up to [amount]. Empty means one category. */
    val splits: List<SplitPart> = emptyList(),
    /** When a card or ATM number in the message was tied to one of the user's accounts, the digits the message actually showed. */
    val instrumentLast4: String? = null,
    /** False for a transaction known only from a bank statement, which has a date but no time of day. */
    val timeKnown: Boolean = true,
) {
    /** The category and amount pairs this payment counts as in category totals. */
    val categoryParts: List<Pair<String, BigDecimal>>
        get() = if (splits.isNotEmpty()) splits.map { it.category to it.amount } else listOf(category to amount)

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
            SourceType.MANUAL -> 1
            else -> 2
        }
    }

    private class Group(first: Item) {
        val items = mutableListOf(first)
        val last: Item get() = items.last()
    }

    fun build(events: List<RawFinancialEventEntity>, parse: (RawFinancialEventEntity) -> ParsedTransaction? = TransactionParser::parse): List<LedgerTxn> {
        val all = events.sortedBy { it.eventTimestamp }.mapNotNull { e -> parse(e)?.let { Item(e, it) } }
        val items = all.filter { it.event.sourceType != SourceType.MANUAL }
        val statements = all.filter { it.event.sourceType == SourceType.MANUAL }
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

        // A statement line joins the alert for the same payment when the reference matches, or failing that when the amount, direction
        // and name agree within a day and a half; otherwise it is a transaction no alert reported.
        val alertGroups = groups.toList()
        for (s in statements) {
            val p = s.parsed
            val reference = p.referenceNo?.takeIf { it.length >= MIN_REFERENCE }
            val byRef = reference?.let { byReference["$it|${p.direction}|${p.amount.toPlainString()}"] }?.takeIf { g -> g.items.none { it.event.sourceType == SourceType.MANUAL } }
            val group = byRef ?: statementMatch(alertGroups, s)
            if (group != null) group.items += s else groups += Group(s)
        }
        return groups.map { toTxn(it) }.sortedByDescending { it.timeMs }
    }

    private const val STATEMENT_WINDOW_MS = 36 * 3_600_000L

    private fun statementMatch(groups: List<Group>, s: Item): Group? {
        val p = s.parsed
        val at = s.event.eventTimestamp
        return groups.asSequence()
            .filter { g -> kotlin.math.abs(g.items.first().event.eventTimestamp - at) <= STATEMENT_WINDOW_MS }
            .filter { g -> g.items.none { it.event.sourceType == SourceType.MANUAL } }
            .filter { g ->
                val q = g.items.first().parsed
                q.direction == p.direction && q.amount.compareTo(p.amount) == 0
            }
            .filter { g ->
                // Only payment-app alerts, which carry no bank, can be matched by amount; a message that names an account must name this one.
                g.items.all { it.parsed.accountLast4 == null || it.parsed.accountLast4 == p.accountLast4 } &&
                    g.items.all { it.event.sourceType == SourceType.NOTIFICATION || it.parsed.accountLast4 == p.accountLast4 }
            }
            .filter { g -> g.items.all { p.referenceNo == null || it.parsed.referenceNo == null || it.parsed.referenceNo == p.referenceNo } }
            .filter { g -> g.items.all { compatible(p.counterparty, it.parsed.counterparty) } }
            .minByOrNull { g -> kotlin.math.abs(g.items.first().event.eventTimestamp - at) }
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
        val earliest = group.items.filter { it.event.sourceType != SourceType.MANUAL }.ifEmpty { group.items }.minOf { it.event.eventTimestamp }
        val counterparty = pick { it.counterparty }
        val mode = pick { it.paymentMode }
        val note = pick { it.note }
        val credit = first.parsed.direction == "CREDIT"
        val date = pick { it.transactionDate } ?: Instant.ofEpochMilli(earliest).atZone(IST).toLocalDate()
        val parts = ordered.map {
            TxnPart(
                sourceName(it.event.sourceType), EventPresenter.statusKind(it.event), EventPresenter.statusLabel(it.event.uploadStatus),
                it.event.capturedTimestamp, EventPresenter.problem(it.event),
            )
        }
        val worst = severity.first { s -> parts.any { it.kind == s } }
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
            eventIds = group.items.map { it.event.localEventId },
            parts = parts,
            status = worst,
            problem = parts.firstOrNull { it.kind == StatusKind.FAILED || it.kind == StatusKind.RETRYING }?.problem,
            timeKnown = group.items.any { it.event.sourceType != SourceType.MANUAL },
        )
    }

    private val severity = listOf(StatusKind.FAILED, StatusKind.RETRYING, StatusKind.WAITING, StatusKind.REVIEW, StatusKind.DONE)

    private fun sourceName(type: SourceType): String = when (type) {
        SourceType.GMAIL -> "Email"
        SourceType.SMS -> "SMS"
        SourceType.MANUAL -> "Statement"
        else -> "Notification"
    }
}

/** Display-only labels based on the payee and note as received; nothing is renamed or stored. */
object Categories {
    const val OTHER = "Other"
    const val OWN_TRANSFER = "Own transfer"
    const val P2P = "P2P transfer"

    /** What the user can pick when fixing a transaction. */
    val CHOICES = listOf(
        "Food & drinks", "Groceries", "Transport", "Shopping", "Bills & recharge", "Rent & housing", "Health",
        "Investments", "Entertainment & apps", "Education", "Travel", "Personal care", "Gifts & donations",
        "Cash withdrawal", "Cash deposit", "Salary", P2P, "Money received", "Interest", OWN_TRANSFER, OTHER,
    )

    private val rules: List<Pair<String, Regex>> = listOf(
        "Investments" to r("groww|zerodha|angel|indmoney|upstox|mutual fund|\\bsip\\b|\\bnse\\b|\\bbse\\b|axis mf|nippon"),
        "Rent & housing" to r("\\brent\\b|landlord|housing|apartment|society|maintenance|\\bpg\\b"),
        "Bills & recharge" to r("jio|airtel|vodafone|\\bvi\\b|bsnl|recharge|electricity|tneb|tangedco|broadband|\\bdth\\b|postpaid|prepaid|insurance|uiic|\\blic\\b|\\bgas\\b|water board|autopay|mandate|sms charge"),
        "Groceries" to r("bigbasket|blinkit|zepto|instamart|grocer|kirana|vegetable|\\bfruits?\\b|\\bmilk\\b|dairy|provision|supermarket|dmart|reliance fresh|\\bmore retail"),
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
        if (looksLikePerson(counterparty, mode)) return P2P
        return if (credit) "Money received" else OTHER
    }

    private val business = r(
        "\\b(pvt|ltd|llp|limited|enterprises?|traders?|stores?|shop|mart|bazaar|agency|services?|solutions?|technolog\\w*|tech|" +
            "hotel|cafe|restaurant|bank|finance|insurance|hospital|clinic|school|college|trust|foundation|motors|electronics|textiles?|" +
            "jewell?ers?|fuel|petro\\w*|gas|pharma\\w*|medicals?|general|provision|bakery|sweets|tea|coffee|biryani|foods?|kitchen|salon|studio|" +
            "gym|ticket|recharge|payments?|wallet|upi|mobiles?|telecom)\\b",
    )

    /** A UPI, IMPS or NEFT counterparty that looks like an individual: a mobile-number address or a short plain name. */
    fun looksLikePerson(counterparty: String?, mode: String?): Boolean {
        val c = counterparty?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        if (mode != null && !Regex("UPI|IMPS|NEFT|RTGS", RegexOption.IGNORE_CASE).matches(mode)) return false
        if (Regex("\\d{10}@.*").matches(c)) return true
        if (c.contains('@') || !Regex("[A-Za-z .'-]{2,40}").matches(c) || c.split(Regex("\\s+")).size > 4) return false
        return !business.containsMatchIn(c)
    }
}
