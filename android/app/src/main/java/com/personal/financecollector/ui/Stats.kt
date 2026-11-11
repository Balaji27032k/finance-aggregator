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
}

data class Totals(val income: BigDecimal, val spend: BigDecimal, val count: Int) {
    val net: BigDecimal get() = income - spend
}

data class Bucket(val key: String, val total: BigDecimal, val count: Int)
data class DayAmount(val date: LocalDate, val spend: BigDecimal, val income: BigDecimal)
data class MonthPace(val spentSoFar: BigDecimal, val projected: BigDecimal, val daysElapsed: Int, val daysInMonth: Int)

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
)

object StatsCalculator {

    fun between(all: List<LedgerTxn>, from: LocalDate, to: LocalDate): List<LedgerTxn> =
        all.filter { !it.date.isBefore(from) && !it.date.isAfter(to) }

    fun totals(list: List<LedgerTxn>): Totals = Totals(
        income = list.filter { it.credit }.sumOf { it.amount },
        spend = list.filter { !it.credit }.sumOf { it.amount },
        count = list.size,
    )

    fun compute(all: List<LedgerTxn>, from: LocalDate, to: LocalDate): Stats {
        val days = ChronoUnit.DAYS.between(from, to).toInt() + 1
        val inRange = between(all, from, to)
        val previous = between(all, from.minusDays(days.toLong()), from.minusDays(1))
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
        val byCategory = buckets(debits) { it.category }
        val topPayees = buckets(debits) { it.counterparty?.trim()?.ifEmpty { null } ?: "Unknown" }.take(5)
        val biggest = debits.sortedByDescending { it.amount }.take(3)

        return Stats(from, to, days, totals, prevTotals, savings, daily, byCategory, topPayees, biggest,
            highlights(days, totals, prevTotals, savings, byCategory, debits))
    }

    fun monthPace(all: List<LedgerTxn>, today: LocalDate): MonthPace {
        val month = YearMonth.from(today)
        val spent = all.filter { !it.credit && YearMonth.from(it.date) == month }.sumOf { it.amount }
        val elapsed = today.dayOfMonth
        val length = month.lengthOfMonth()
        val projected = spent.multiply(BigDecimal(length)).divide(BigDecimal(elapsed), 2, RoundingMode.HALF_UP)
        return MonthPace(spent, projected, elapsed, length)
    }

    private fun buckets(list: List<LedgerTxn>, key: (LedgerTxn) -> String): List<Bucket> =
        list.groupBy(key).map { (k, v) -> Bucket(k, v.sumOf { it.amount }, v.size) }.sortedByDescending { it.total }

    private fun highlights(
        days: Int, totals: Totals, previous: Totals, savings: Int?, byCategory: List<Bucket>, debits: List<LedgerTxn>,
    ): List<String> {
        val out = mutableListOf<String>()
        if (previous.spend.signum() > 0) {
            val change = totals.spend.subtract(previous.spend).multiply(BigDecimal(100)).divide(previous.spend, 0, RoundingMode.HALF_UP).toInt()
            out += when {
                change == 0 -> "Spending is about the same as the previous $days days."
                change > 0 -> "You spent $change% more than in the previous $days days."
                else -> "You spent ${-change}% less than in the previous $days days."
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
