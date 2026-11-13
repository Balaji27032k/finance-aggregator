package com.personal.financecollector.ui

import com.personal.financecollector.database.TransactionLabelEntity

/**
 * The user's own account numbers (last digits) and the names their accounts appear under, besides the ones seen in messages.
 * [accountBrands] says which bank each listed account belongs to, so card digits from that bank can be tied to it.
 */
data class OwnSettings(
    val accountLast4s: Set<String> = emptySet(),
    val names: Set<String> = emptySet(),
    val accountBrands: Map<String, String> = emptyMap(),
)

/** Applies the user's corrections and own-account knowledge on top of what the messages say. */
object LedgerOverlay {
    private const val PAIR_WINDOW_MS = 10 * 60_000L

    /** A stable key for a payee so one correction covers all its payments, however the bank spells the name. */
    fun payeeKey(name: String?): String? {
        if (name == null) return null
        // A mobile number identifies the payee whichever UPI handle (@ybl, @oksbi, ...) is attached to it.
        MOBILE.find(name)?.let { return it.groupValues[1] }
        return name.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length >= 3 }.take(3).joinToString(" ").ifBlank { null }
    }

    private val MOBILE = Regex("(?<!\\d)([6-9]\\d{9})(?!\\d)")

    fun detectedAccounts(base: List<LedgerTxn>): Set<String> = base.mapNotNull { it.accountLast4 }.toSet()

    /**
     * A card number in a bank's message is not an account. When the user listed exactly one account at that bank, the card and ATM
     * digits are tied to it (the original digits stay in [LedgerTxn.instrumentLast4]); with several accounts there is no way to tell.
     */
    private fun tieCardsToAccounts(rows: List<LedgerTxn>, own: OwnSettings): List<LedgerTxn> {
        if (own.accountBrands.isEmpty()) return rows
        val brandOf = AccountGrouping.brandOf(rows)
        val accountsByBrand = own.accountBrands.entries.groupBy({ it.value }, { it.key })
        return rows.map { t ->
            val digits = t.accountLast4 ?: return@map t
            if (digits in own.accountBrands) return@map t
            val target = accountsByBrand[brandOf(t)]?.singleOrNull() ?: return@map t
            t.copy(accountLast4 = target, instrumentLast4 = digits)
        }
    }

    fun apply(
        raw: List<LedgerTxn>,
        labels: Map<String, TransactionLabelEntity>,
        rules: Map<String, String>,
        own: OwnSettings,
        splits: Map<String, List<SplitPart>> = emptyMap(),
    ): List<LedgerTxn> {
        val base = tieCardsToAccounts(raw, own)
        // Once the user has listed their accounts, only those count as theirs; until then every account seen in messages does.
        val accounts = own.accountLast4s.ifEmpty { detectedAccounts(base) }
        val names = own.names.map { words(it) }.filter { it.isNotEmpty() }

        val first = base.map { t ->
            val saved = t.eventIds.mapNotNull { labels[it] }.maxByOrNull { it.updatedAt }
            val label = saved?.label?.takeIf { it.isNotBlank() }
            val explicit = saved?.category?.takeIf { it.isNotBlank() }
            val rule = payeeKey(t.counterparty)?.let { rules[it] }
            val settled = explicit ?: rule
            val auto = when {
                settled != null -> settled
                label != null -> Categories.of(t.credit, t.counterparty, t.paymentMode, listOfNotNull(t.note, label).joinToString(" "))
                else -> t.category
            }
            val isOwn = settled == null && looksOwn(t, accounts, names)
            val category = if (isOwn) Categories.OWN_TRANSFER else auto
            // A split only counts while its parts still add up to the payment.
            val parts = t.eventIds.firstNotNullOfOrNull { splits[it] }?.takeIf { p -> p.size >= 2 && p.sumOf { it.amount }.compareTo(t.amount) == 0 }.orEmpty()
            t.copy(
                label = label,
                edited = explicit != null || label != null || rule != null || parts.isNotEmpty(),
                userCategory = explicit,
                splits = parts,
                category = category,
                internalTransfer = category == Categories.OWN_TRANSFER,
            ) to (settled != null)
        }

        val result = first.map { it.first }.toMutableList()
        pairTransfers(result, first.map { it.second }, accounts)
        return result
    }

    /** A debit from one of your accounts matched by an equal credit into another of yours within minutes is one transfer. */
    private fun pairTransfers(list: MutableList<LedgerTxn>, settled: List<Boolean>, accounts: Set<String>) {
        val creditsByAmount = HashMap<String, MutableList<Int>>()
        list.forEachIndexed { i, t ->
            if (t.credit && !settled[i] && !t.internalTransfer && t.accountLast4 in accounts) {
                creditsByAmount.getOrPut(t.amount.stripTrailingZeros().toPlainString()) { mutableListOf() }.add(i)
            }
        }
        val used = HashSet<Int>()
        list.indices.forEach { i ->
            val d = list[i]
            if (d.credit || settled[i] || d.internalTransfer || d.accountLast4 !in accounts) return@forEach
            val match = creditsByAmount[d.amount.stripTrailingZeros().toPlainString()].orEmpty().firstOrNull { j ->
                j !in used && list[j].accountLast4 != d.accountLast4 && kotlin.math.abs(list[j].timeMs - d.timeMs) <= PAIR_WINDOW_MS
            } ?: return@forEach
            used += match
            listOf(i, match).forEach { k ->
                list[k] = list[k].copy(category = Categories.OWN_TRANSFER, internalTransfer = true)
            }
        }
    }

    private fun looksOwn(t: LedgerTxn, accounts: Set<String>, names: List<Set<String>>): Boolean {
        val other = t.counterpartyAccountLast4
        if (other != null && other != t.accountLast4 && other in accounts) return true
        val who = t.counterparty?.let { words(it) } ?: return false
        return names.any { who.containsAll(it) }
    }

    private fun words(value: String): Set<String> =
        value.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length >= 2 }.toSet()
}
