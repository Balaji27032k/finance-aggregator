package com.personal.finance;

import static com.personal.finance.TestSupport.event;
import static com.personal.finance.TestSupport.ingest;
import static com.personal.finance.TestSupport.register;
import static com.personal.finance.TestSupport.uniqueSourceId;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.personal.finance.TestSupport.Device;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class AskApiTest extends AdminTestBase {

    private static final String QUESTION = "{\"question\":\"how much on zz ask cafe in the last 12 months\"}";

    @Test
    void theDashboardAndThePhoneCanAskWithoutAnAiKey() throws Exception {
        Device device = register(mvc);
        mvc.perform(ingest(device, event("ask-1", uniqueSourceId(), "613.37", "ZZ Ask Cafe"))).andExpect(status().isOk());
        Cookie cookie = signIn();

        mvc.perform(post("/api/admin/ask").cookie(cookie).contentType(MediaType.APPLICATION_JSON).content(QUESTION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aiUsed").value(false))
                .andExpect(jsonPath("$.intent").value("spend_on"))
                .andExpect(jsonPath("$.exact.count").value(1))
                .andExpect(jsonPath("$.exact.total").value(613.37));
        mvc.perform(post("/api/v1/ask").header("Authorization", device.bearer()).contentType(MediaType.APPLICATION_JSON).content(QUESTION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exact.count").value(1));
    }

    @Test
    void askingNeedsASignInAndAQuestion() throws Exception {
        mvc.perform(post("/api/admin/ask").contentType(MediaType.APPLICATION_JSON).content(QUESTION)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/ask").contentType(MediaType.APPLICATION_JSON).content(QUESTION)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/admin/ask").cookie(signIn()).contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"\"}"))
                .andExpect(status().isBadRequest());
    }
}
