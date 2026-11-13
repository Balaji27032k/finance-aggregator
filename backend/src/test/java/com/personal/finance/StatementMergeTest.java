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
import org.junit.jupiter.api.Test;

class StatementMergeTest extends AdminTestBase {

    private static String as(String json, String type, String time) {
        return json.replace("\"sourceType\":\"SMS\"", "\"sourceType\":\"" + type + "\"")
                .replace("\"referenceNo\":\"000000000003\"", "\"referenceNo\":null")
                .replace("2026-11-10T08:15:00Z", time);
    }

    @Test
    void aStatementRowAndAPaymentAppNotificationForTheSamePaymentAreOneTransaction() throws Exception {
        Device device = register(mvc);
        mvc.perform(ingest(device, as(event("stm-n", uniqueSourceId(), "888.88", "ZZ Stmt Person"), "NOTIFICATION", "2026-11-10T08:15:00Z")))
                .andExpect(status().isOk());
        // The statement has a date but no time, so it is stamped at noon India time the same day.
        mvc.perform(ingest(device, as(event("stm-s", uniqueSourceId(), "888.88", "ZZ Stmt Person"), "MANUAL", "2026-11-10T06:30:00Z")))
                .andExpect(status().isOk());
        Cookie cookie = signIn();
        mvc.perform(get("/api/admin/transactions?from=2026-10-01&to=2026-11-30&q=zz stmt person").cookie(cookie))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].sources.length()").value(2));
    }

    @Test
    void twoDifferentPeopleOrAmountsAreNotMerged() throws Exception {
        Device device = register(mvc);
        mvc.perform(ingest(device, as(event("stm-a", uniqueSourceId(), "777.77", "ZZ First Person"), "NOTIFICATION", "2026-11-10T08:15:00Z")))
                .andExpect(status().isOk());
        mvc.perform(ingest(device, as(event("stm-b", uniqueSourceId(), "777.77", "ZZ Other Name"), "MANUAL", "2026-11-10T06:30:00Z")))
                .andExpect(status().isOk());
        mvc.perform(get("/api/admin/transactions?from=2026-10-01&to=2026-11-30&q=777.77").cookie(signIn()))
                .andExpect(jsonPath("$.total").value(2));
    }
}
