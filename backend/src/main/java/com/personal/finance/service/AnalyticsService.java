package com.personal.finance.service;

import com.personal.finance.dto.Summary;
import com.personal.finance.dto.Summary.AccountBalance;
import com.personal.finance.dto.Summary.Bucket;
import com.personal.finance.dto.Summary.DayPoint;
import com.personal.finance.dto.Summary.MonthPoint;
import com.personal.finance.dto.Summary.Projection;
import com.personal.finance.dto.Summary.Range;
import com.personal.finance.dto.Summary.Recurring;
import com.personal.finance.dto.Summary.Totals;
import com.personal.finance.dto.TransactionView;
import com.personal.finance.entity.Direction;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/** Computes the dashboard's numbers from merged transactions. Days follow the date in the message, or India time. */
@Service
public class AnalyticsService {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final int MAX_GROUPS = 10_000;
    private static final int TOP = 10;

    private final RecentTransactions transactions;
    private final Clock clock;

    public AnalyticsService(RecentTransactions transactions, Clock clock) {
        this.transactions = transactions;
        this.clock = clock;
    }

    public Summary summary(LocalDate from, LocalDate to) {
        LocalDate today = LocalDate.now(clock.withZone(IST));
        int days = (int) ChronoUnit.DAYS.between(from, to) + 1;
        LocalDate previousFrom = from.minusDays(days);
        LocalDate wideFrom = earliest(previousFrom, today.minusMonths(12).withDayOfMonth(1));
        LocalDate wideTo = later(to, today);
        List<TransactionView> wide = transactions.list(
                wideFrom.minusDays(1).atStartOfDay(IST).toInstant(), wideTo.plusDays(2).atStartOfDay(IST).toInstant(), MAX_GROUPS);

        List<TransactionView> inRange = between(wide, from, to);
        List<TransactionView> before = between(wide, previousFrom, from.minusDays(1));
        Totals totals = totals(inRange, days);
        Totals previous = totals(before, days);
        BigDecimal savings = totals.income().signum() > 0
                ? totals.net().multiply(BigDecimal.valueOf(100)).divide(totals.income(), 1, RoundingMode.HALF_UP) : null;

        List<DayPoint> daily = daily(inRange, from, to);
        return new Summary(new Range(from, to, days), totals, previous, savings, daily, monthly(wide, today),
                buckets(inRange, Direction.DEBIT, v -> orUnknown(v.counterparty()), TOP),
                buckets(inRange, Direction.CREDIT, v -> orUnknown(v.counterparty()), TOP),
                buckets(inRange, Direction.DEBIT, TransactionView::category, 20),
                buckets(inRange, Direction.DEBIT, v -> v.paymentMode() == null ? "Other" : v.paymentMode(), 20),
                buckets(inRange, Direction.DEBIT, v -> v.sources().isEmpty() ? "Unknown" : v.sources().get(0).bankCode(), 20),
                weekdays(inRange), hours(inRange),
                inRange.stream().filter(v -> v.direction() == Direction.DEBIT)
                        .sorted(Comparator.comparing(TransactionView::amount).reversed()).limit(5).toList(),
                recurring(wide, today), spikes(daily), balances(wide), projection(wide, today));
    }

    static LocalDate dateOf(TransactionView v) {
        return v.transactionDate() != null ? v.transactionDate() : v.eventTimestamp().atZone(IST).toLocalDate();
    }

    private static LocalDate earliest(LocalDate a, LocalDate b) {
        return a.isBefore(b) ? a : b;
    }

    private static LocalDate later(LocalDate a, LocalDate b) {
        return a.isAfter(b) ? a : b;
    }

    private static List<TransactionView> between(List<TransactionView> all, LocalDate from, LocalDate to) {
        return all.stream().filter(v -> !dateOf(v).isBefore(from) && !dateOf(v).isAfter(to)).toList();
    }

    private static String orUnknown(String name) {
        return name == null || name.isBlank() ? "Unknown" : name.trim();
    }

    private static BigDecimal sum(List<TransactionView> list, Direction d) {
        return list.stream().filter(v -> v.direction() == d).map(TransactionView::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static Totals totals(List<TransactionView> list, int days) {
        BigDecimal income = sum(list, Direction.CREDIT);
        BigDecimal spend = sum(list, Direction.DEBIT);
        BigDecimal largest = list.stream().filter(v -> v.direction() == Direction.DEBIT).map(TransactionView::amount)
                .max(Comparator.naturalOrder()).orElse(BigDecimal.ZERO);
        return new Totals(income, spend, income.subtract(spend), list.size(),
                spend.divide(BigDecimal.valueOf(Math.max(days, 1)), 2, RoundingMode.HALF_UP), largest);
    }

    private static List<DayPoint> daily(List<TransactionView> list, LocalDate from, LocalDate to) {
        Map<LocalDate, BigDecimal[]> byDay = new HashMap<>();
        for (TransactionView v : list) {
            BigDecimal[] cell = byDay.computeIfAbsent(dateOf(v), k -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
            cell[v.direction() == Direction.CREDIT ? 0 : 1] = cell[v.direction() == Direction.CREDIT ? 0 : 1].add(v.amount());
        }
        List<DayPoint> out = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            BigDecimal[] cell = byDay.getOrDefault(d, new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
            out.add(new DayPoint(d, cell[0], cell[1]));
        }
        return out;
    }

    private static List<MonthPoint> monthly(List<TransactionView> wide, LocalDate today) {
        TreeMap<YearMonth, BigDecimal[]> months = new TreeMap<>();
        YearMonth last = YearMonth.from(today);
        for (int i = 11; i >= 0; i--) {
            months.put(last.minusMonths(i), new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
        }
        for (TransactionView v : wide) {
            BigDecimal[] cell = months.get(YearMonth.from(dateOf(v)));
            if (cell != null) {
                int i = v.direction() == Direction.CREDIT ? 0 : 1;
                cell[i] = cell[i].add(v.amount());
            }
        }
        return months.entrySet().stream().map(e -> new MonthPoint(e.getKey().toString(), e.getValue()[0], e.getValue()[1])).toList();
    }

    private static List<Bucket> buckets(List<TransactionView> list, Direction d, Function<TransactionView, String> key, int limit) {
        Map<String, BigDecimal[]> sums = new HashMap<>();
        Map<String, Integer> counts = new HashMap<>();
        for (TransactionView v : list) {
            if (v.direction() != d) {
                continue;
            }
            String k = key.apply(v);
            sums.merge(k, new BigDecimal[] {v.amount()}, (a, b) -> new BigDecimal[] {a[0].add(b[0])});
            counts.merge(k, 1, Integer::sum);
        }
        return sums.entrySet().stream()
                .sorted(Map.Entry.<String, BigDecimal[]>comparingByValue(Comparator.comparing(a -> a[0])).reversed())
                .limit(limit).map(e -> new Bucket(e.getKey(), e.getValue()[0], counts.get(e.getKey()))).toList();
    }

    private static List<Bucket> weekdays(List<TransactionView> list) {
        Map<DayOfWeek, BigDecimal> sums = new java.util.EnumMap<>(DayOfWeek.class);
        Map<DayOfWeek, Integer> counts = new java.util.EnumMap<>(DayOfWeek.class);
        for (DayOfWeek d : DayOfWeek.values()) {
            sums.put(d, BigDecimal.ZERO);
            counts.put(d, 0);
        }
        for (TransactionView v : list) {
            if (v.direction() == Direction.DEBIT) {
                DayOfWeek d = dateOf(v).getDayOfWeek();
                sums.merge(d, v.amount(), BigDecimal::add);
                counts.merge(d, 1, Integer::sum);
            }
        }
        return java.util.Arrays.stream(DayOfWeek.values())
                .map(d -> new Bucket(d.name().charAt(0) + d.name().substring(1, 3).toLowerCase(Locale.ROOT), sums.get(d), counts.get(d))).toList();
    }

    private static List<Bucket> hours(List<TransactionView> list) {
        BigDecimal[] sums = new BigDecimal[24];
        int[] counts = new int[24];
        java.util.Arrays.fill(sums, BigDecimal.ZERO);
        for (TransactionView v : list) {
            if (v.direction() == Direction.DEBIT) {
                int h = v.eventTimestamp().atZone(IST).getHour();
                sums[h] = sums[h].add(v.amount());
                counts[h]++;
            }
        }
        List<Bucket> out = new ArrayList<>();
        for (int h = 0; h < 24; h++) {
            out.add(new Bucket(String.format("%02d", h), sums[h], counts[h]));
        }
        return out;
    }

    /** Debits to the same payee in at least three different months with a stable amount and roughly monthly spacing. */
    private static List<Recurring> recurring(List<TransactionView> wide, LocalDate today) {
        LocalDate since = today.minusMonths(7);
        Map<String, List<TransactionView>> byPayee = wide.stream()
                .filter(v -> v.direction() == Direction.DEBIT && v.counterparty() != null && !dateOf(v).isBefore(since))
                .collect(Collectors.groupingBy(v -> v.counterparty().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "")));
        List<Recurring> out = new ArrayList<>();
        for (List<TransactionView> list : byPayee.values()) {
            if (list.stream().map(v -> YearMonth.from(dateOf(v))).distinct().count() < 3 || list.size() < 3) {
                continue;
            }
            List<BigDecimal> amounts = list.stream().map(TransactionView::amount).sorted().toList();
            BigDecimal median = amounts.get(amounts.size() / 2);
            long stable = amounts.stream().filter(a -> a.subtract(median).abs().compareTo(median.multiply(new BigDecimal("0.2"))) <= 0).count();
            List<LocalDate> dates = list.stream().map(AnalyticsService::dateOf).sorted().toList();
            List<Long> gaps = new ArrayList<>();
            for (int i = 1; i < dates.size(); i++) {
                gaps.add(ChronoUnit.DAYS.between(dates.get(i - 1), dates.get(i)));
            }
            long medianGap = gaps.stream().sorted().toList().get(gaps.size() / 2);
            if (stable >= Math.max(3, (long) Math.ceil(list.size() * 0.7)) && medianGap >= 20 && medianGap <= 40) {
                LocalDate last = dates.get(dates.size() - 1);
                out.add(new Recurring(list.get(0).counterparty(), median, list.size(), last, last.plusDays(medianGap)));
            }
        }
        out.sort(Comparator.comparing(Recurring::typicalAmount).reversed());
        return out;
    }

    private static List<DayPoint> spikes(List<DayPoint> daily) {
        if (daily.size() < 7) {
            return List.of();
        }
        double mean = daily.stream().mapToDouble(d -> d.spend().doubleValue()).average().orElse(0);
        double variance = daily.stream().mapToDouble(d -> Math.pow(d.spend().doubleValue() - mean, 2)).average().orElse(0);
        double limit = Math.max(mean + 2 * Math.sqrt(variance), mean * 2);
        return daily.stream().filter(d -> d.spend().doubleValue() > limit && d.spend().signum() > 0)
                .sorted(Comparator.comparing(DayPoint::spend).reversed()).limit(5).toList();
    }

    private static List<AccountBalance> balances(List<TransactionView> wide) {
        Map<String, TransactionView> latest = new HashMap<>();
        for (TransactionView v : wide) {
            if (v.balanceAfter() == null || v.accountLast4() == null || v.sources().isEmpty()) {
                continue;
            }
            String key = v.sources().get(0).bankCode() + "|" + v.accountLast4();
            latest.merge(key, v, (a, b) -> b.eventTimestamp().isAfter(a.eventTimestamp()) ? b : a);
        }
        return latest.values().stream()
                .map(v -> new AccountBalance(v.sources().get(0).bankCode(), v.accountLast4(), v.balanceAfter(), v.eventTimestamp()))
                .sorted(Comparator.comparing(AccountBalance::asOf).reversed()).toList();
    }

    private static Projection projection(List<TransactionView> wide, LocalDate today) {
        YearMonth month = YearMonth.from(today);
        BigDecimal spent = wide.stream().filter(v -> v.direction() == Direction.DEBIT && YearMonth.from(dateOf(v)).equals(month))
                .map(TransactionView::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        int elapsed = today.getDayOfMonth();
        int length = month.lengthOfMonth();
        BigDecimal projected = spent.multiply(BigDecimal.valueOf(length)).divide(BigDecimal.valueOf(elapsed), 2, RoundingMode.HALF_UP);
        return new Projection(month.toString(), spent, projected, elapsed, length);
    }
}
