package com.personal.finance.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class RatesServiceTest {

    private static final String REPLY = "```json\n{\"items\":["
            + "{\"key\":\"ppf\",\"ratePercent\":7.1,\"asOf\":\"2026-Q3\",\"note\":\"current quarter\"},"
            + "{\"key\":\"fd5y\",\"ratePercent\":\"6.30%\",\"asOf\":\"2026-10-19\",\"note\":\"SBI general public\"},"
            + "{\"key\":\"epf\",\"ratePercent\":8.25,\"asOf\":\"FY 2024-25\",\"note\":\"\"},"
            + "{\"key\":\"inflation\",\"ratePercent\":4.2,\"asOf\":\"2026-09\",\"note\":\"CPI\"},"
            + "{\"key\":\"savings\",\"ratePercent\":71,\"asOf\":\"x\",\"note\":\"out of range\"},"
            + "{\"key\":\"bitcoin\",\"ratePercent\":50,\"asOf\":\"x\",\"note\":\"unknown\"},"
            + "{\"key\":\"ppf\",\"ratePercent\":9,\"asOf\":\"x\",\"note\":\"duplicate\"}"
            + "]}\n```";

    private final AtomicInteger calls = new AtomicInteger();
    private JdbcTemplate jdbc;
    private Instant now = Instant.parse("2026-11-12T10:00:00Z");
    private final Clock clock = new Clock() {
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        public Instant instant() {
            return now;
        }
    };

    private AiModel model(boolean configured, Optional<AiModel.Grounded> answer, String problem) {
        return new AiModel() {
            public boolean configured() {
                return configured;
            }

            public Optional<String> generate(String system, String user, boolean json) {
                return Optional.empty();
            }

            public Optional<Grounded> generateGrounded(String system, String user) {
                calls.incrementAndGet();
                return answer;
            }

            public String lastProblem() {
                return problem;
            }
        };
    }

    @BeforeEach
    void database() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:rates" + System.nanoTime() + ";DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("CREATE TABLE market_rates (id INT NOT NULL PRIMARY KEY, body TEXT NOT NULL, fetched_at TIMESTAMP NOT NULL)");
    }

    @Test
    void onlyValuesThatPassTheChecksAreKeptAndEachComesWithItsSources() {
        RatesService service = new RatesService(
                model(true, Optional.of(new AiModel.Grounded(REPLY, List.of(new AiModel.Source("India Post", "https://www.indiapost.gov.in/x")))), null), jdbc, clock);
        MarketRates r = service.refresh();
        assertEquals("PARTIAL", r.status());
        assertEquals(List.of("fd5y", "ppf", "epf", "inflation"), r.items().stream().map(MarketRates.Rate::key).toList());
        assertEquals(new BigDecimal("7.1"), r.items().get(1).ratePercent());
        assertEquals(new BigDecimal("6.3"), r.items().get(0).ratePercent());
        assertEquals("https://www.indiapost.gov.in/x", r.sources().get(0).url());
        assertEquals(now, r.fetchedAt());
        assertEquals(r.items(), service.current().orElseThrow().items());
    }

    @Test
    void askingAgainSoonReusesTheSavedCopyAndSpendsNoQuota() {
        RatesService service = new RatesService(model(true, Optional.of(new AiModel.Grounded(REPLY, List.of())), null), jdbc, clock);
        service.refresh();
        now = now.plus(Duration.ofMinutes(3));
        MarketRates again = service.refresh();
        assertEquals(1, calls.get());
        assertTrue(again.message().contains("few minutes"));
        now = now.plus(Duration.ofMinutes(30));
        service.refresh();
        assertEquals(2, calls.get());
    }

    @Test
    void whenTheSearchFailsTheLastCopyIsKeptAndTheReasonIsGiven() {
        RatesService good = new RatesService(model(true, Optional.of(new AiModel.Grounded(REPLY, List.of())), null), jdbc, clock);
        good.refresh();
        now = now.plus(Duration.ofHours(2));
        MarketRates stale = new RatesService(model(true, Optional.empty(), "Web search is not available for this AI key or model (HTTP 400)."), jdbc, clock).refresh();
        assertEquals("STALE", stale.status());
        assertEquals(4, stale.items().size());
        assertTrue(stale.message().contains("not available"));
    }

    @Test
    void withoutAKeyOrAnUnreadableReplyNothingIsInventedOrSaved() {
        MarketRates none = new RatesService(model(false, Optional.empty(), null), jdbc, clock).refresh();
        assertEquals("DEFAULTS", none.status());
        assertTrue(none.message().contains("standard values"));
        assertTrue(none.items().stream().allMatch(i -> "standard".equals(i.asOf())));
        assertTrue(none.items().stream().anyMatch(i -> i.key().equals("ppf")));
        assertNull(none.fetchedAt());
        MarketRates garbled = new RatesService(model(true, Optional.of(new AiModel.Grounded("I could not find any rates", List.of())), null), jdbc, clock).refresh();
        assertEquals("DEFAULTS", garbled.status());
        assertFalse(new RatesService(model(true, Optional.empty(), null), jdbc, clock).current().isPresent());
    }

    @Test
    void theCoachUsesRecentlyFetchedRatesAndNotOldOnes() {
        RatesService service = new RatesService(model(true, Optional.of(new AiModel.Grounded(REPLY, List.of())), null), jdbc, clock);
        service.refresh();
        Map<String, Double> defaults = service.coachDefaults();
        assertEquals(7.1, defaults.get("ppf"));
        assertEquals(6.3, defaults.get("fd"));
        assertEquals(4.2, defaults.get("inflation"));
        now = now.plus(Duration.ofDays(60));
        assertTrue(service.coachDefaults().isEmpty());
    }
}
