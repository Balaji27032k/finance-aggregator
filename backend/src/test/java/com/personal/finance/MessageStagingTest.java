package com.personal.finance;

import static com.personal.finance.TestSupport.register;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.personal.finance.TestSupport.Device;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MessageStagingTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    private static String message(String channel, String externalId, String body) {
        return """
                {"channel":"%s","externalId":"%s","sender":"VM-SBIUPI-S","subject":null,
                 "receivedAt":"2026-11-10T08:15:00Z","body":"%s","bodyTruncated":false,"labels":null}"""
                .formatted(channel, externalId, body);
    }

    private static String batch(String... messages) {
        return "{\"messages\":[" + String.join(",", messages) + "]}";
    }

    @Test
    void messagesAreStoredAndRepeatsAreCountedAsDuplicates() throws Exception {
        Device device = register(mvc);
        String id = "stage-" + java.util.UUID.randomUUID();
        String json = batch(message("SMS", id, "sample text one"), message("EMAIL", id, "sample text two"));

        mvc.perform(post("/api/v1/staging/batch").header("Authorization", device.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isOk()).andExpect(jsonPath("$.received").value(2))
                .andExpect(jsonPath("$.stored").value(2)).andExpect(jsonPath("$.duplicates").value(0));
        mvc.perform(post("/api/v1/staging/batch").header("Authorization", device.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isOk()).andExpect(jsonPath("$.stored").value(0))
                .andExpect(jsonPath("$.duplicates").value(2));

        Integer rows = jdbc.queryForObject("select count(*) from message_staging where external_id = ?", Integer.class, id);
        assertThat(rows).isEqualTo(2);
        String body = jdbc.queryForObject("select body from message_staging where external_id = ? and channel = 'SMS'",
                String.class, id);
        assertThat(body).isEqualTo("sample text one");
    }

    @Test
    void theEndpointNeedsADeviceToken() throws Exception {
        mvc.perform(post("/api/v1/staging/batch").contentType(MediaType.APPLICATION_JSON)
                        .content(batch(message("SMS", "x", "y"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void invalidChannelsAndEmptyBatchesAreBadRequests() throws Exception {
        Device device = register(mvc);
        mvc.perform(post("/api/v1/staging/batch").header("Authorization", device.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(batch(message("FAX", "x", "y"))))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/staging/batch").header("Authorization", device.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"messages\":[]}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/staging/batch").header("Authorization", device.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(batch(message("SMS", "", "y"))))
                .andExpect(status().isBadRequest());
    }
}
