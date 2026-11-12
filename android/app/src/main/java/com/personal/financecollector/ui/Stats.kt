package com.personal.financecollector.ui

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.time.format.TextStyle
import java.util.Locale

enum class Period(val label: String) {
    WEEK("7 days"),
    MONTH("This month"),
    LAST_MONTH("Last month"),
    THREE_MONTHS("3 months"),
    YEAR("1 year");

    fun range(today: LocalDate): Pair<LocalDate, LocalDate> = when (this) {
        WEEK -> today.minusDays(6) to today
        MONTH -> today.withDayOfMonth(1) to today
        LAST_MONTH -> today.minusMonths(1).withDayOfMonth(1) to today.withDayOfMonth(1).minusDays(1)
        THREE_MONTHS -> today.minusDays(89) to today
        YEAR -> today.minusDays(364) to today
    }

    /** What this period is compared with: the same days of last month for "this month", otherwise the stretch just before it. */
    fun previous(today: LocalDate): Comparison {
        val (from, to) = range(today)
        val days = ChronoUnit.DAYS.between(from, to).toInt() + 1
        return when (this) {
            MONTH -> {
                val start = from.minusMonths(1)
                Comparison(start, start.plusDays(minOf(days.toLong(), start.lengthOfMonth().toLong()) - 1), "the same days last month")
            }
            LAST_MONTH -> {
                val start = from.minusMonths(1)
                Comparison(start, start.withDayOfMonth(start.lengthOfMonth()), "the month before")
            }
            else -> Comparison(from.minusDays(days.toLong()), from.minusDays(1), "the previous $days days")
        }
    }
}

data class Comparison(val from: LocalDate, val to: LocalDate, val label: String)

data class Totals(val income: BigDecimal, val spend: BigDecimal, val count: Int) {
    val net: BigDecimal get() = income - spend
}

data class Bucket(val key: String, val total: BigDecimal, val count: Int)
data class DayAmount(val date: LocalDate, val spend: BigDecimal, val income: BigDecimal)
/** [dailyRate] is the usual day-to-day spending the projection is built on; one-off large payments are left out of it. */
data class MonthPace(
    val spentSoFar: BigDecimal,
    val projected: BigDecimal,
    val daysElapsed: Int,
    val daysInMonth: Int,
    val dailyRate: BigDecimal = BigDecimal.ZERO,
)

data class Stats(
    val from: LocalDate,
    val to: LocalDate,
    val days: Int,
    val totals: Totals,
    val previous: Totals,
    val savingsPercent: Int?,
    val daily: List<DayAmount>,
    val byCategory: List<Bucket>,
    val topPayees: List<Bucket>,
    val biggest: List<LedgerTxn>,
    val highlights: List<String>,
    /** Words for what [previous] covers, for example "the same days last month". */
    val previousLabel: String = "the previous $days days",
)

object StatsCalculator {

    fun between(all: List<LedgerTxn>, from: LocalDate, to: LocalDate): List<LedgerTxn> =
        all.filter { !it.date.isBefore(from) && !it.date.isAfter(to) }

    fun totals(list: List<LedgerTxn>): Totals = Totals(
        income = list.filter { it.credit }.sumOf { it.amount },
        spend = list.filter { !it.credit }.sumOf { it.amount },
        count = list.size,
    )

    fun compute(everything: List<LedgerTxn>, from: LocalDate, to: LocalDate, comparison: Comparison? = null): Stats {
        // Moving money between your own accounts is neither spending nor income.
        val all = everything.filter { !it.internalTransfer }
        val days = ChronoUnit.DAYS.between(from, to).toInt() + 1
        val inRange = between(all, from, to)
        val against = comparison ?: Comparison(from.minusDays(days.toLong()), from.minusDays(1), "the previous $days days")
        val previous = between(all, against.from, against.to)
        val totals = totals(inRange)
        val prevTotals = totals(previous)
        val savings = if (totals.income.signum() > 0) {
            totals.net.multiply(BigDecimal(100)).divide(totals.income, 0, RoundingMode.HALF_UP).toInt()
        } else null

        val spendByDay = inRange.filter { !it.credit }.groupBy { it.date }.mapValues { e -> e.value.sumOf { it.amount } }
        val incomeByDay = inRange.filter { it.credit }.groupBy { it.date }.mapValues { e -> e.value.sumOf { it.amount } }
        val daily = (0 until days).map { i ->
            val d = from.plusDays(i.toLong())
            DayAmount(d, spendByDay[d] ?: BigDecimal.ZERO, incomeByDay[d] ?: BigDecimal.ZERO)
        }
        val debits = inRange.filter { !it.credit }
        val byCategory = categoryBuckets(debits)
        val topPayees = payeeBuckets(debits).take(5)
        val biggest = debits.sortedByDescending { it.amount }.take(3)

        return Stats(from, to, days, totals, prevTotals, savings, daily, byCategory, topPayees, biggest,
            highlights(against.label, totals, prevTotals, savings, byCategory, debits, days), against.label)
    }

    /**
     * Spending so far this month, and where the month is heading. The projection adds the usual day-to-day spending of
     * the last 90 days for the days still to come, so a single big payment (rent, a fee) early in the month is not
     * multiplied across the rest of it.
     */
    fun monthPace(everything: List<LedgerTxn>, today: LocalDate): MonthPace {
        val all = everything.filter { !it.internalTransfer }
        val month = YearMonth.from(today)
        val spent = all.filter { !it.credit && YearMonth.from(it.date) == month }.sumOf { it.amount }
        val elapsed = today.dayOfMonth
        val length = month.lengthOfMonth()
        val daysLeft = length - elapsed

        val window = all.filter { !it.credit && !it.date.isAfter(today) && !it.date.isBefore(today.minusDays(89)) }
        val firstDay = window.minOfOrNull { it.date } ?: today
        val windowDays = (ChronoUnit.DAYS.between(firstDay, today).toInt() + 1).coerceAtLeast(1)
        val rate = if (windowDays < 7 || window.isEmpty()) {
            spent.divide(BigDecimal(elapsed), 2, RoundingMode.HALF_UP)
        } else {
            val amounts = window.map { it.amount }.sorted()
            val median = amounts[amounts.size / 2]
            val oneOff = maxOf(BigDecimal(5000), median.multiply(BigDecimal(5)))
            window.filter { it.amount <= oneOff }.sumOf { it.amount }.divide(BigDecimal(windowDays), 2, RoundingMode.HALF_UP)
        }
        val projected = spent.add(rate.multiply(BigDecimal(daysLeft))).setScale(2, RoundingMode.HALF_UP)
        return MonthPace(spent, projected, elapsed, length, rate)
    }

    /** Payees grouped by name however the bank spells it (SWIGGY, Swiggy), shown with their most common spelling. */
    private fun payeeBuckets(list: List<LedgerTxn>): List<Bucket> =
        list.groupBy { LedgerOverlay.payeeKey(it.counterparty) ?: (it.counterparty?.trim()?.lowercase().orEmpty()) }
            .map { (_, v) ->
                val name = v.mapNotNull { it.counterparty?.trim()?.ifEmpty { null } }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
                Bucket(name?.let(::prettyName) ?: "Unknown", v.sumOf { it.amount }, v.size)
            }
            .sortedByDescending { it.total }

    private fun buckets(list: List<LedgerTxn>, key: (LedgerTxn) -> String): List<Bucket> =
        list.groupBy(key).map { (k, v) -> Bucket(k, v.sumOf { it.amount }, v.size) }.sortedByDescending { it.total }

    /** Category totals; a split payment adds each of its parts to its own category. */
    fun categoryBuckets(list: List<LedgerTxn>): List<Bucket> =
        list.flatMap { it.categoryParts }.groupBy({ it.first }, { it.second })
            .map { (k, v) -> Bucket(k, v.fold(BigDecimal.ZERO, BigDecimal::add), v.size) }.sortedByDescending { it.total }

    /** What has been spent per category in one month, for budgets. */
    fun categorySpend(everything: List<LedgerTxn>, month: YearMonth): Map<String, BigDecimal> =
        categoryBuckets(everything.filter { !it.internalTransfer && !it.credit && YearMonth.from(it.date) == month }).associate { it.key to it.total }

    private fun highlights(
        label: String, totals: Totals, previous: Totals, savings: Int?, byCategory: List<Bucket>, debits: List<LedgerTxn>, days: Int,
    ): List<String> {
        val out = mutableListOf<String>()
        if (previous.spend.signum() > 0) {
            val change = totals.spend.subtract(previous.spend).multiply(BigDecimal(100)).divide(previous.spend, 0, RoundingMode.HALF_UP).toInt()
            out += when {
                change == 0 -> "Spending is about the same as $label."
                change > 0 -> "You spent $change% more than in $label."
                else -> "You spent ${-change}% less than in $label."
            }
        }
        val total = byCategory.sumOf { it.total }
        byCategory.firstOrNull()?.takeIf { total.signum() > 0 }?.let {
            val pct = it.total.multiply(BigDecimal(100)).divide(total, 0, RoundingMode.HALF_UP).toInt()
            out += "${it.key} is your biggest category: $pct% of spending."
        }
        val weekday = debits.groupBy { it.date.dayOfWeek }.mapValues { e -> e.value.sumOf { it.amount } }
            .maxByOrNull { it.value }?.key
        if (weekday != null && days >= 14) out += "You spend the most on ${weekday.fullName()}s."
        if (savings != null) {
            out += if (savings >= 0) "You kept $savings% of what came in." else "You spent ${-savings}% more than what came in."
        }
        return out
    }

    private fun DayOfWeek.fullName(): String = getDisplayName(TextStyle.FULL, Locale.ENGLISH)
}
