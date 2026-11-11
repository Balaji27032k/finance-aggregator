package com.personal.finance;

import static com.personal.finance.TestSupport.event;
import static com.personal.finance.TestSupport.ingest;
import static com.personal.finance.TestSupport.register;
import static com.personal.finance.TestSupport.uniqueSourceId;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.personal.finance.TestSupport.Device;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AdminDataTest extends AdminTestBase {

    private static boolean seeded;
    private Cookie cookie;

    @BeforeEach
    void seed() throws Exception {
        cookie = signIn();
        if (seeded) {
            return;
        }
        seeded = true;
        Device device = register(mvc);
        mvc.perform(ingest(device, event("a1", uniqueSourceId(), "611.00", "ZZ Marker Cafe"))).andExpect(status().isOk());
        mvc.perform(ingest(device, event("a2", uniqueSourceId(), "233.00", "ZZ Marker Cafe"))).andExpect(status().isOk());
    }

    @Test
    void transactionsCanBeFilteredSearchedAndPaged() throws Exception {
        mvc.perform(get("/api/admin/transactions?from=2026-10-01&to=2026-11-30&q=zz marker").cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items[0].category").value("Food & drinks"))
                .andExpect(jsonPath("$.categories").isNotEmpty());

        mvc.perform(get("/api/admin/transactions?from=2026-10-01&to=2026-11-30&q=zz marker&sort=amount&order=asc&size=1")
                        .cookie(cookie))
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].amount").value(233.00));

        mvc.perform(get("/api/admin/transactions?from=2026-10-01&to=2026-11-30&q=zz marker&direction=CREDIT").cookie(cookie))
                .andExpect(jsonPath("$.total").value(0));
        mvc.perform(get("/api/admin/transactions?from=2026-10-01&to=2026-11-30&q=zz marker&minAmount=300").cookie(cookie))
                .andExpect(jsonPath("$.total").value(1));
    }

    @Test
    void summaryAddsUpTheSeededSpending() throws Exception {
        mvc.perform(get("/api/admin/summary?from=2026-10-01&to=2026-11-30").cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.range.days").value(61))
                .andExpect(jsonPath("$.daily.length()").value(61))
                .andExpect(jsonPath("$.monthly.length()").value(12))
                .andExpect(jsonPath("$.byCategory[?(@.key=='Food & drinks')]").isNotEmpty())
                .andExpect(jsonPath("$.topPayees[?(@.key=='ZZ Marker Cafe')]").isNotEmpty())
                .andExpect(jsonPath("$.byWeekday.length()").value(7))
                .andExpect(jsonPath("$.byHour.length()").value(24))
                .andExpect(jsonPath("$.balances").isNotEmpty())
                .andExpect(jsonPath("$.projection.daysInMonth").isNumber());
    }

    @Test
    void invalidRangesAreRejected() throws Exception {
        mvc.perform(get("/api/admin/summary?from=2026-11-30&to=2026-10-01").cookie(cookie)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/admin/summary?from=2020-01-01&to=2026-10-01").cookie(cookie)).andExpect(status().isBadRequest());
    }

    @Test
    void healthShowsWhereDataCameFrom() throws Exception {
        mvc.perform(get("/api/admin/health").cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactions").isNumber())
                .andExpect(jsonPath("$.bySource[?(@.key=='SMS')]").isNotEmpty())
                .andExpect(jsonPath("$.byBank").isNotEmpty());
    }
}
