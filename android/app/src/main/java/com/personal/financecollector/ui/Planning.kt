package com.personal.financecollector.ui

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

data class AccountBalance(val bankCode: String, val last4: String, val balance: BigDecimal, val asOfMs: Long) {
    val key: String get() = "$bankCode|$last4"
}

data class NetWorthPoint(val date: LocalDate, val total: BigDecimal)

/** [change] compares today with about a month ago, when there is a balance from then. */
data class NetWorth(val total: BigDecimal, val accounts: List<AccountBalance>, val series: List<NetWorthPoint>, val change: BigDecimal?)

/** Adds up the latest balance each bank message reported for each account. */
object NetWorthCalculator {

    fun latestBalances(ledger: List<LedgerTxn>): List<AccountBalance> {
        val brand = AccountGrouping.brandOf(ledger)
        return ledger.filter { it.balanceAfter != null && it.accountLast4 != null }
            .groupBy { brand(it) to it.accountLast4!! }
            .map { (k, v) ->
                val last = v.maxBy { it.timeMs }
                AccountBalance(k.first, k.second, last.balanceAfter!!, last.timeMs)
            }
            .sortedByDescending { it.balance }
    }

    /** [mine] limits the total to the user's own accounts (last digits); empty means every account with a known balance. */
    fun compute(ledger: List<LedgerTxn>, excluded: Set<String>, today: LocalDate, mine: Set<String> = emptySet()): NetWorth {
        val accounts = latestBalances(ledger).filter { it.key !in excluded && (mine.isEmpty() || it.last4 in mine) }
        val brand = AccountGrouping.brandOf(ledger)
        val byAccount = ledger.filter {
            it.balanceAfter != null && it.accountLast4 != null && (mine.isEmpty() || it.accountLast4 in mine) &&
                "${brand(it)}|${it.accountLast4}" !in excluded
        }
            .groupBy { "${brand(it)}|${it.accountLast4}" }
            .mapValues { (_, v) -> v.sortedBy { it.timeMs } }

        fun totalOn(date: LocalDate): BigDecimal? {
            val end = date.plusDays(1).atStartOfDay(LedgerBuilder.IST).toInstant().toEpochMilli()
            val known = byAccount.values.mapNotNull { list -> list.lastOrNull { it.timeMs < end }?.balanceAfter }
            return if (known.isEmpty()) null else known.fold(BigDecimal.ZERO, BigDecimal::add)
        }

        val dates = (11 downTo 1).map { YearMonth.from(today).minusMonths(it.toLong()).atEndOfMonth() } + today
        val series = dates.mapNotNull { d -> totalOn(d)?.let { NetWorthPoint(d, it) } }
        val total = accounts.fold(BigDecimal.ZERO) { acc, a -> acc + a.balance }
        val monthAgo = totalOn(today.minusDays(30))
        return NetWorth(total, accounts, series, monthAgo?.let { total - it })
    }
}

/** A payment that has repeated about monthly, and when the next one is expected. */
data class Bill(val key: String, val name: String, val amount: BigDecimal, val times: Int, val lastDate: LocalDate, val nextDue: LocalDate)

object BillDetector {

    /** At least three payments to one payee in three different months, similar in amount and roughly a month apart. */
    fun detect(ledger: List<LedgerTxn>, today: LocalDate): List<Bill> {
        val since = today.minusMonths(7)
        val groups = ledger.filter { !it.credit && !it.internalTransfer && !it.date.isBefore(since) && !it.date.isAfter(today) }
            .mapNotNull { t -> LedgerOverlay.payeeKey(t.counterparty)?.let { it to t } }
            .groupBy({ it.first }, { it.second })

        val out = mutableListOf<Bill>()
        for ((key, list) in groups) {
            if (list.size < 3 || list.map { YearMonth.from(it.date) }.distinct().size < 3) continue
            val amounts = list.map { it.amount }.sorted()
            val median = amounts[amounts.size / 2]
            val tolerance = median.multiply(BigDecimal("0.2"))
            val stable = amounts.count { (it - median).abs() <= tolerance }
            if (stable < maxOf(3, Math.ceil(list.size * 0.7).toInt())) continue
            val dates = list.map { it.date }.sorted()
            val gaps = dates.zipWithNext { a, b -> ChronoUnit.DAYS.between(a, b) }.sorted()
            val gap = gaps[gaps.size / 2]
            if (gap < 20 || gap > 40) continue
            val last = dates.last()
            // Not seen for two cycles means it has probably stopped.
            if (ChronoUnit.DAYS.between(last, today) > gap * 2) continue
            // Monthly payments fall on the same day of the month, so step by calendar months rather than a fixed number of days.
            val monthly = gap in 27..34
            fun after(d: LocalDate) = if (monthly) d.plusMonths(1) else d.plusDays(gap)
            var next = after(last)
            while (next.isBefore(today.minusDays(3))) next = after(next)
            val name = list.mapNotNull { it.counterparty }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: key
            out += Bill(key, prettyName(name), median.setScale(0, RoundingMode.HALF_UP), list.size, last, next)
        }
        return out.sortedBy { it.nextDue }
    }
}

/** How far a category is through its monthly limit. */
data class BudgetStatus(val category: String, val limit: Long, val spent: BigDecimal) {
    val percent: Int get() = if (limit <= 0) 0 else spent.multiply(BigDecimal(100)).divide(BigDecimal(limit), 0, RoundingMode.HALF_UP).toInt()
    val over: Boolean get() = spent > BigDecimal(limit)
}

object BudgetCalculator {
    fun statuses(ledger: List<LedgerTxn>, limits: Map<String, Long>, month: YearMonth): List<BudgetStatus> {
        val spent = StatsCalculator.categorySpend(ledger, month)
        return limits.map { (c, l) -> BudgetStatus(c, l, spent[c] ?: BigDecimal.ZERO) }.sortedByDescending { it.percent }
    }

    /** The alert levels, in percent, that [status] has reached. */
    fun levelsReached(status: BudgetStatus): List<Int> = listOf(80, 100).filter { status.percent >= it }
}

fun Instant.toIstDate(): LocalDate = atZone(LedgerBuilder.IST).toLocalDate()
