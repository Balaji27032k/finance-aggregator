package com.personal.financecollector.ui

import com.personal.financecollector.database.TransactionLabelEntity

/** The user's own account numbers (last digits) and the names their accounts appear under, besides the ones seen in messages. */
data class OwnSettings(val accountLast4s: Set<String> = emptySet(), val names: Set<String> = emptySet())

/** Applies the user's corrections and own-account knowledge on top of what the messages say. */
object LedgerOverlay {
    private const val PAIR_WINDOW_MS = 10 * 60_000L

    /** A stable key for a payee so one correction covers all its payments, however the bank spells the name. */
    fun payeeKey(name: String?): String? =
        name?.lowercase()?.split(Regex("[^a-z0-9]+"))?.filter { it.length >= 3 }?.take(3)?.joinToString(" ")?.ifBlank { null }

    fun detectedAccounts(base: List<LedgerTxn>): Set<String> = base.mapNotNull { it.accountLast4 }.toSet()

    fun apply(
        base: List<LedgerTxn>,
        labels: Map<String, TransactionLabelEntity>,
        rules: Map<String, String>,
        own: OwnSettings,
    ): List<LedgerTxn> {
        val accounts = detectedAccounts(base) + own.accountLast4s
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
            t.copy(
                label = label,
                edited = explicit != null || label != null || rule != null,
                userCategory = explicit,
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
