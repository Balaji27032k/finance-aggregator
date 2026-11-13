package com.personal.finance;

import static com.personal.finance.TestSupport.register;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.personal.finance.TestSupport.Device;
import org.junit.jupiter.api.Test;

class RatesApiTest extends AdminTestBase {

    @Test
    void withoutAnAiKeyTheRatesEndpointSaysSoInsteadOfInventingNumbers() throws Exception {
        Device device = register(mvc);
        mvc.perform(get("/api/v1/rates").header("Authorization", device.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DEFAULTS"))
                .andExpect(jsonPath("$.items[?(@.key=='ppf')].ratePercent").value(7.1));
        mvc.perform(post("/api/v1/rates/refresh").header("Authorization", device.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DEFAULTS"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("no AI key")));
        mvc.perform(post("/api/admin/rates/refresh").cookie(signIn())).andExpect(status().isOk());
        mvc.perform(get("/api/v1/rates")).andExpect(status().isUnauthorized());
    }
}
