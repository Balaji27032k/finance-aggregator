package com.personal.finance;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "app.rate-limit.anonymous-per-minute=3")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RateLimitTest {

    @Autowired
    MockMvc mvc;

    @Test
    void repeatedPairingGuessesFromOneAddressAreThrottled() throws Exception {
        String wrong = "{\"pairingCode\":\"wrong-code-000\",\"deviceName\":\"x\"}";
        for (int i = 0; i < 3; i++) {
            mvc.perform(post("/api/v1/devices/register").contentType(MediaType.APPLICATION_JSON).content(wrong))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/api/v1/devices/register").contentType(MediaType.APPLICATION_JSON).content(wrong))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
    }
}
