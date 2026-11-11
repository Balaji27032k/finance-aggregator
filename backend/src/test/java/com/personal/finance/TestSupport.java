package com.personal.finance;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Request helpers shared by the HTTP-level tests. */
final class TestSupport {

    static final String PAIRING_CODE = "test-pairing-code-123";

    private TestSupport() {
    }

    record Device(long id, String token) {
        String bearer() {
            return "Bearer " + token;
        }
    }

    static Device register(MockMvc mvc) throws Exception {
        String body = mvc.perform(post("/api/v1/devices/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pairingCode\":\"" + PAIRING_CODE + "\",\"deviceName\":\"test phone\",\"appVersion\":\"0.1\"}"))
                .andReturn().getResponse().getContentAsString();
        Number id = JsonPath.read(body, "$.deviceId");
        return new Device(id.longValue(), JsonPath.read(body, "$.deviceToken"));
    }

    static String uniqueSourceId() {
        return "src-" + UUID.randomUUID();
    }

    static final String HASH = "a".repeat(64);

    static String event(String localId, String sourceEventId, String counterparty) {
        return event(localId, sourceEventId, "500.00", counterparty);
    }

    static String event(String localId, String sourceEventId, String amount, String counterparty) {
        return """
                {"localEventId":"%s","sourceType":"SMS","sourceApplication":"SBIUPI","sourceSender":"VM-SBIUPI-S",
                 "sourceEventId":"%s","eventTimestamp":"2026-11-10T08:15:00Z","capturedTimestamp":"2026-11-10T08:15:02Z",
                 "payloadHash":"%s",
                 "transaction":{"direction":"DEBIT","amount":"%s","currency":"INR","accountLast4":"0000",
                  "counterparty":"%s","paymentMode":"UPI","referenceNo":"000000000003","balanceAfter":"1200.50",
                  "transactionDate":"2026-10-30","bankCode":"SBIUPI","note":"sample note"},
                 "parserStatus":"PARSED","parserVersion":"sms-1"}"""
                .formatted(localId, sourceEventId, HASH, amount, counterparty);
    }

    static MockHttpServletRequestBuilder ingest(Device device, String json) {
        return post("/api/v1/events/ingest").header("Authorization", device.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(json);
    }

    static MockHttpServletRequestBuilder batch(Device device, String json) {
        return post("/api/v1/events/batch").header("Authorization", device.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(json);
    }

    static MockHttpServletRequestBuilder getWith(Device device, String path) {
        return get(path).header("Authorization", device.bearer());
    }
}
