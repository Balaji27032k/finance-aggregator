package com.personal.finance.ai;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Finds the latest interest rates for each kind of investment by letting the model search the web, then keeps only values that
 * pass range checks. Nothing is guessed: a rate that was not found is simply missing from the result.
 */
@Service
public class RatesService {

    private static final int ROW = 1;
    private static final Duration MIN_GAP = Duration.ofMinutes(10);
    private static final Duration FRESH_FOR_COACH = Duration.ofDays(45);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private record Spec(String key, String label, double min, double max) {
    }

    private static final List<Spec> SPECS = List.of(
            new Spec("savings", "Savings account (SBI)", 0, 8),
            new Spec("fd1y", "Fixed deposit, 1 year (SBI)", 2, 12),
            new Spec("fd5y", "Fixed deposit, 5 years (SBI)", 2, 12),
            new Spec("rd", "Recurring deposit, 5 years (SBI)", 2, 12),
            new Spec("ppf", "PPF", 2, 12),
            new Spec("ssy", "Sukanya Samriddhi", 2, 12),
            new Spec("nsc", "National Savings Certificate", 2, 12),
            new Spec("kvp", "Kisan Vikas Patra", 2, 12),
            new Spec("scss", "Senior Citizens Savings Scheme", 2, 12),
            new Spec("poSavings", "Post Office savings account", 1, 8),
            new Spec("poRd", "Post Office recurring deposit", 2, 12),
            new Spec("poTd5y", "Post Office 5-year time deposit", 2, 12),
            new Spec("mis", "Post Office Monthly Income Scheme", 2, 12),
            new Spec("epf", "EPF (latest declared)", 5, 12),
            new Spec("inflation", "Inflation, CPI (latest)", -2, 15),
            new Spec("repo", "RBI repo rate", 2, 12),
            new Spec("equity", "Nifty 50, 10-year annual return", 3, 25));

    private static final String SYSTEM = "You are a careful research assistant. Use Google Search to find the CURRENT official values for India listed "
            + "below, preferring the issuer's own pages (SBI, India Post, Ministry of Finance, EPFO, RBI, MoSPI, NSE or NiftyIndices). Reply with "
            + "JSON only, no other text: {\"items\":[{\"key\":string,\"ratePercent\":number,\"asOf\":string,\"note\":string}]}. Rules: report a "
            + "value ONLY if you found it on a page you used; if you cannot find one, leave that key out; never estimate or guess. ratePercent is "
            + "the annual percentage as a plain number. asOf is the date or quarter the value applies to (for example 2026-10-01 or 2026-Q3). note "
            + "is at most 12 words (for example the tenure used). Keys: savings = SBI savings account rate; fd1y = SBI term deposit, 1 year to less "
            + "than 2 years, general public; fd5y = SBI term deposit, 5 years to 10 years, general public; rd = SBI recurring deposit, 5 years, "
            + "general public; ppf = Public Provident Fund rate for the current quarter; ssy = Sukanya Samriddhi Account; nsc = National Savings "
            + "Certificate; kvp = Kisan Vikas Patra; scss = Senior Citizens Savings Scheme; poSavings = Post Office savings account; poRd = Post "
            + "Office 5-year recurring deposit; poTd5y = Post Office 5-year time deposit; mis = Post Office Monthly Income Scheme; epf = EPF "
            + "interest rate most recently declared by EPFO; inflation = latest year-on-year CPI inflation published by MoSPI; repo = RBI policy "
            + "repo rate; equity = Nifty 50 total return, annualised over 10 years.";

    private final AiModel ai;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final JsonMapper mapper = JsonMapper.builder().build();

    public RatesService(AiModel ai, JdbcTemplate jdbc, Clock clock) {
        this.ai = ai;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** The last fetched copy, if any. */
    public Optional<MarketRates> current() {
        List<String> rows = jdbc.queryForList("SELECT body FROM market_rates WHERE id = ?", String.class, ROW);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(mapper.readValue(rows.get(0), MarketRates.class));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /** Fetches fresh rates unless they were fetched in the last few minutes. */
    public MarketRates refresh() {
        Optional<MarketRates> previous = current();
        Instant now = clock.instant();
        if (previous.isPresent() && previous.get().fetchedAt() != null && Duration.between(previous.get().fetchedAt(), now).compareTo(MIN_GAP) < 0
                && !previous.get().items().isEmpty()) {
            MarketRates p = previous.get();
            return new MarketRates(p.fetchedAt(), p.status(), "Fetched a few minutes ago, so this is that copy.", p.items(), p.sources());
        }
        if (!ai.configured()) {
            return fallback(previous, "Rates are fetched with the AI's web search, and no AI key is set on the server.");
        }
        String today = LocalDate.now(clock.withZone(IST)).toString();
        Optional<AiModel.Grounded> grounded = ai.generateGrounded(SYSTEM, "Today is " + today + ". Find the current values now.");
        if (grounded.isEmpty()) {
            String why = ai.lastProblem() != null ? ai.lastProblem() : "The web search did not return anything.";
            return fallback(previous, why);
        }
        List<MarketRates.Rate> items = parse(grounded.get().text());
        if (items.isEmpty()) {
            return fallback(previous, "The search answered, but no rate could be read from it, so nothing was changed.");
        }
        String status = items.size() == SPECS.size() ? "OK" : "PARTIAL";
        String message = items.size() == SPECS.size() ? "Found every rate."
                : "Found " + items.size() + " of " + SPECS.size() + " rates. The rest could not be confirmed on a page, so they are left out.";
        MarketRates result = new MarketRates(now, status, message, items, grounded.get().sources());
        save(result, now);
        return result;
    }

    private MarketRates fallback(Optional<MarketRates> previous, String message) {
        if (previous.isPresent() && !previous.get().items().isEmpty()) {
            MarketRates p = previous.get();
            return new MarketRates(p.fetchedAt(), "STALE", message + " Showing the last copy.", p.items(), p.sources());
        }
        return MarketRates.unavailable(message);
    }

    /** Values that are missing, unknown, unreadable or outside a sensible range are dropped. */
    List<MarketRates.Rate> parse(String text) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return List.of();
        }
        JsonNode root;
        try {
            root = mapper.readTree(text.substring(start, end + 1));
        } catch (RuntimeException e) {
            return List.of();
        }
        Map<String, Spec> specs = new HashMap<>();
        SPECS.forEach(s -> specs.put(s.key(), s));
        Map<String, MarketRates.Rate> found = new LinkedHashMap<>();
        for (JsonNode item : root.path("items")) {
            Spec spec = specs.get(item.path("key").asString(""));
            BigDecimal value = number(item.path("ratePercent"));
            if (spec == null || value == null || found.containsKey(spec.key())) {
                continue;
            }
            double d = value.doubleValue();
            if (d < spec.min() || d > spec.max()) {
                continue;
            }
            found.put(spec.key(), new MarketRates.Rate(spec.key(), spec.label(), value.stripTrailingZeros(), clean(item.path("asOf").asString(""), 20),
                    clean(item.path("note").asString(""), 80)));
        }
        List<MarketRates.Rate> out = new ArrayList<>();
        for (Spec s : SPECS) {
            if (found.containsKey(s.key())) {
                out.add(found.get(s.key()));
            }
        }
        return out;
    }

    private static final Pattern NUMBER = Pattern.compile("-?\\d+(?:\\.\\d+)?");

    private static BigDecimal number(JsonNode node) {
        if (node.isNumber()) {
            return node.decimalValue();
        }
        Matcher m = NUMBER.matcher(node.asString(""));
        return m.find() ? new BigDecimal(m.group()) : null;
    }

    private static String clean(String value, int max) {
        String out = value.replaceAll("\\p{Cntrl}", " ").trim();
        return out.length() > max ? out.substring(0, max) : out;
    }

    private void save(MarketRates rates, Instant now) {
        String body = mapper.writeValueAsString(rates);
        Timestamp at = Timestamp.from(now);
        if (jdbc.update("UPDATE market_rates SET body = ?, fetched_at = ? WHERE id = ?", body, at, ROW) == 0) {
            jdbc.update("INSERT INTO market_rates (id, body, fetched_at) VALUES (?, ?, ?)", ROW, body, at);
        }
    }

    /** Recently fetched values the coach should assume until the person sets their own: inflation, equity, fd, ppf, epf, savings. */
    public Map<String, Double> coachDefaults() {
        Optional<MarketRates> rates = current();
        Map<String, Double> out = new HashMap<>();
        if (rates.isEmpty() || rates.get().fetchedAt() == null
                || Duration.between(rates.get().fetchedAt(), clock.instant()).compareTo(FRESH_FOR_COACH) > 0) {
            return out;
        }
        Map<String, Double> byKey = new HashMap<>();
        rates.get().items().forEach(r -> byKey.put(r.key(), r.ratePercent().doubleValue()));
        for (String k : Set.of("inflation", "equity", "ppf", "epf", "savings")) {
            if (byKey.containsKey(k)) {
                out.put(k, byKey.get(k));
            }
        }
        Double fd = byKey.containsKey("fd5y") ? byKey.get("fd5y") : byKey.get("fd1y");
        if (fd != null) {
            out.put("fd", fd);
        }
        return out;
    }
}
