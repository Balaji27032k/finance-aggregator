package com.personal.finance.ai;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Interest rates found on the web. [status] is OK (all found), PARTIAL (some found), STALE (the last good copy) or UNAVAILABLE. */
public record MarketRates(Instant fetchedAt, String status, String message, List<Rate> items, List<AiModel.Source> sources) {

    public record Rate(String key, String label, BigDecimal ratePercent, String asOf, String note) {
    }

    public static MarketRates unavailable(String message) {
        return new MarketRates(null, "UNAVAILABLE", message, List.of(), List.of());
    }
}
