package com.personal.finance.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Everything the dashboard's overview and insights pages show for one date range. */
public record Summary(
        Range range,
        Range previousRange,
        Totals totals,
        Totals previous,
        BigDecimal savingsRatePercent,
        List<DayPoint> daily,
        List<MonthPoint> monthly,
        List<Bucket> topPayees,
        List<Bucket> topIncomeSources,
        List<Bucket> byCategory,
        List<Bucket> byMode,
        List<Bucket> byBank,
        List<Bucket> byWeekday,
        List<Bucket> byHour,
        List<TransactionView> largestExpenses,
        List<Recurring> recurring,
        List<DayPoint> spikes,
        List<AccountBalance> balances,
        Projection projection) {

    public record Range(LocalDate from, LocalDate to, int days) {
    }

    public record Totals(BigDecimal income, BigDecimal spend, BigDecimal net, int count, BigDecimal avgDailySpend,
                         BigDecimal largestExpense) {
    }

    public record DayPoint(LocalDate date, BigDecimal income, BigDecimal spend) {
    }

    public record MonthPoint(String month, BigDecimal income, BigDecimal spend) {
    }

    public record Bucket(String key, BigDecimal total, int count) {
    }

    public record Recurring(String name, BigDecimal typicalAmount, int occurrences, LocalDate lastDate, LocalDate nextExpected) {
    }

    public record AccountBalance(String bankCode, String accountLast4, BigDecimal balance, Instant asOf) {
    }

    public record Projection(String month, BigDecimal spentSoFar, BigDecimal projected, int daysElapsed, int daysInMonth,
                             BigDecimal dailyRate) {
    }
}
