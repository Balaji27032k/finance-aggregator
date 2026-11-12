package com.personal.finance.controller;

import com.personal.finance.dto.AdminViews.Count;
import com.personal.finance.dto.AdminViews.DataHealth;
import com.personal.finance.dto.AdminViews.SpendLookup;
import com.personal.finance.dto.AdminViews.TransactionPage;
import com.personal.finance.dto.Summary;
import com.personal.finance.dto.TransactionView;
import com.personal.finance.entity.Direction;
import com.personal.finance.repository.FinancialTransactionRepository;
import com.personal.finance.service.AnalyticsService;
import com.personal.finance.service.TransactionQueryService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

/** Read-only data for the web dashboard. Requires the admin session cookie. */
@RestController
@RequestMapping("/api/admin")
public class AdminDataController {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final int MAX_GROUPS = 10_000;
    private static final int MAX_RANGE_DAYS = 800;

    private final AnalyticsService analytics;
    private final TransactionQueryService transactions;
    private final FinancialTransactionRepository ledger;
    private final Clock clock;

    public AdminDataController(AnalyticsService analytics, TransactionQueryService transactions,
                               FinancialTransactionRepository ledger, Clock clock) {
        this.analytics = analytics;
        this.transactions = transactions;
        this.ledger = ledger;
        this.clock = clock;
    }

    @GetMapping("/summary")
    public Summary summary(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate previousFrom,
                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate previousTo) {
        LocalDate end = to != null ? to : today();
        LocalDate start = from != null ? from : end.minusDays(29);
        validate(start, end);
        if (previousFrom != null && previousTo != null) {
            validate(previousFrom, previousTo);
        }
        return analytics.summary(start, end, previousFrom, previousTo);
    }

    @GetMapping("/transactions")
    public TransactionPage transactions(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Direction direction,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) BigDecimal minAmount,
            @RequestParam(required = false) BigDecimal maxAmount,
            @RequestParam(defaultValue = "date") String sort,
            @RequestParam(defaultValue = "desc") String order,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        LocalDate end = to != null ? to : today();
        LocalDate start = from != null ? from : end.minusDays(89);
        validate(start, end);
        int pageSize = Math.max(1, Math.min(size, 200));

        List<TransactionView> inRange = transactions.list(
                start.minusDays(1).atStartOfDay(IST).toInstant(), end.plusDays(2).atStartOfDay(IST).toInstant(), MAX_GROUPS)
                .stream().filter(v -> within(v, start, end)).toList();
        TreeSet<String> categories = new TreeSet<>();
        inRange.forEach(v -> v.partsByCategory().forEach(p -> categories.add(p.category())));

        String needle = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        List<TransactionView> filtered = inRange.stream()
                .filter(v -> direction == null || v.direction() == direction)
                .filter(v -> category == null || category.isBlank() || v.partsByCategory().stream().anyMatch(p -> category.equals(p.category())))
                .filter(v -> source == null || source.isBlank() || v.sources().stream().anyMatch(s -> s.type().equalsIgnoreCase(source)))
                .filter(v -> minAmount == null || v.amount().compareTo(minAmount) >= 0)
                .filter(v -> maxAmount == null || v.amount().compareTo(maxAmount) <= 0)
                .filter(v -> needle.isEmpty() || matches(v, needle))
                .sorted(comparator(sort, order))
                .toList();

        BigDecimal debit = filtered.stream().filter(v -> v.direction() == Direction.DEBIT).map(TransactionView::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal credit = filtered.stream().filter(v -> v.direction() == Direction.CREDIT).map(TransactionView::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        int fromIndex = (int) Math.min((long) Math.max(page, 0) * pageSize, filtered.size());
        List<TransactionView> items = filtered.subList(fromIndex, Math.min(fromIndex + pageSize, filtered.size()));
        return new TransactionPage(filtered.size(), Math.max(page, 0), pageSize, debit, credit, List.copyOf(categories), items);
    }

    /** How much went on one thing, for example "tea": counts the matching share of split payments, not the whole payment. */
    @GetMapping("/spend")
    public SpendLookup spend(@RequestParam String term,
                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        String needle = term.trim().toLowerCase(Locale.ROOT);
        if (needle.length() < 2 || needle.length() > 60) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Type at least two letters");
        }
        LocalDate end = to != null ? to : today();
        LocalDate start = from != null ? from : end.minusDays(29);
        validate(start, end);
        List<SpendLookup.Match> items = transactions.list(
                        start.minusDays(1).atStartOfDay(IST).toInstant(), end.plusDays(2).atStartOfDay(IST).toInstant(), MAX_GROUPS)
                .stream().filter(v -> within(v, start, end))
                .map(v -> new SpendLookup.Match(v, matchedAmount(v, needle)))
                .filter(m -> m.matched().signum() > 0)
                .sorted(Comparator.comparing((SpendLookup.Match m) -> m.transaction().eventTimestamp()).reversed())
                .toList();
        BigDecimal total = items.stream().map(SpendLookup.Match::matched).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new SpendLookup(term.trim(), start, end, total, items.size(), items);
    }

    /** For a split payment only the parts the user named count; otherwise the whole payment when its payee, note, description or category fits. */
    static BigDecimal matchedAmount(TransactionView v, String needle) {
        if (v.direction() != Direction.DEBIT || "Own transfer".equals(v.category())) {
            return BigDecimal.ZERO;
        }
        if (!v.splits().isEmpty()) {
            return v.splits().stream().filter(p -> contains(p.category(), needle) || contains(p.name(), needle)).map(TransactionView.Split::amount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        }
        boolean hit = contains(v.counterparty(), needle) || contains(v.note(), needle) || contains(v.label(), needle) || contains(v.category(), needle);
        return hit ? v.amount() : BigDecimal.ZERO;
    }

    @GetMapping("/health")
    public DataHealth health() {
        return new DataHealth(ledger.countGroups(), ledger.findMultiChannelGroupIds().size(), ledger.earliestEvent(),
                counts(ledger.countBySource()), counts(ledger.countByBank()), Instant.now(clock));
    }

    private static List<Count> counts(List<Object[]> rows) {
        return rows.stream()
                .map(r -> new Count(r[0] == null ? "UNKNOWN" : r[0].toString(), ((Number) r[1]).longValue(), (Instant) r[2]))
                .sorted(Comparator.comparingLong(Count::count).reversed()).toList();
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(IST));
    }

    private static void validate(LocalDate from, LocalDate to) {
        if (to.isBefore(from) || java.time.temporal.ChronoUnit.DAYS.between(from, to) > MAX_RANGE_DAYS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid date range");
        }
    }

    private static boolean within(TransactionView v, LocalDate from, LocalDate to) {
        LocalDate d = v.transactionDate() != null ? v.transactionDate() : v.eventTimestamp().atZone(IST).toLocalDate();
        return !d.isBefore(from) && !d.isAfter(to);
    }

    private static boolean matches(TransactionView v, String needle) {
        return contains(v.counterparty(), needle) || contains(v.note(), needle) || contains(v.label(), needle) || contains(v.referenceNo(), needle)
                || contains(v.accountLast4(), needle) || contains(v.category(), needle)
                || v.splits().stream().anyMatch(p -> contains(p.category(), needle) || contains(p.name(), needle))
                || v.amount().stripTrailingZeros().toPlainString().contains(needle);
    }

    private static boolean contains(String value, String needle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
    }

    private static Comparator<TransactionView> comparator(String sort, String order) {
        Comparator<TransactionView> by = "amount".equals(sort)
                ? Comparator.comparing(TransactionView::amount)
                : Comparator.comparing(TransactionView::eventTimestamp);
        return "asc".equalsIgnoreCase(order) ? by : by.reversed();
    }
}
