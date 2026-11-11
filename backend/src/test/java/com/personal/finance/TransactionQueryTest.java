package com.personal.finance;

import static com.personal.finance.TestSupport.ingest;
import static com.personal.finance.TestSupport.register;
import static com.personal.finance.TestSupport.uniqueSourceId;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.personal.finance.TestSupport.Device;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TransactionQueryTest {

    @Autowired
    MockMvc mvc;

    private static String json(String localId, String sourceType, String time, String amount, String counterparty,
                               String note, String ref) {
        return """
                {"localEventId":"%s","sourceType":"%s","sourceApplication":"APP","sourceSender":null,
                 "sourceEventId":"%s","eventTimestamp":"%s","capturedTimestamp":"%s","payloadHash":"%s",
                 "transaction":{"direction":"DEBIT","amount":"%s","currency":"INR","accountLast4":"0000",
                  "counterparty":%s,"paymentMode":"UPI","referenceNo":"%s","balanceAfter":null,
                  "transactionDate":"2025-03-05","bankCode":"TEST","note":%s},
                 "parserStatus":"PARSED","parserVersion":"parser-3"}"""
                .formatted(localId, sourceType, uniqueSourceId(), time, time, TestSupport.HASH, amount,
                        counterparty == null ? "null" : "\"" + counterparty + "\"", ref,
                        note == null ? "null" : "\"" + note + "\"");
    }

    @Test
    void oneEntryPerTransactionWithDetailsMergedAcrossChannels() throws Exception {
        Device d = register(mvc);
        mvc.perform(ingest(d, json("q-sms", "SMS", "2025-03-05T08:00:00Z", "811.11", null, null, "711100000001")));
        mvc.perform(ingest(d, json("q-mail", "GMAIL", "2025-03-05T08:20:00Z", "811.11", "Sample Shop", "lunch", "711100000001")));
        mvc.perform(ingest(d, json("q-other", "SMS", "2025-03-05T09:00:00Z", "822.22", "Another", null, "711100000002")));

        mvc.perform(TestSupport.getWith(d, "/api/v1/transactions?from=2025-03-05&to=2025-03-05"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].amount").value(822.22))
                .andExpect(jsonPath("$[1].amount").value(811.11))
                .andExpect(jsonPath("$[1].counterparty").value("Sample Shop"))
                .andExpect(jsonPath("$[1].note").value("lunch"))
                .andExpect(jsonPath("$[1].sources.length()").value(2))
                .andExpect(jsonPath("$[1].sources[0].type").value("GMAIL"));
    }

    @Test
    void theListNeedsADeviceTokenAndIsEmptyOutsideTheRange() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/transactions"))
                .andExpect(status().isUnauthorized());
        Device d = register(mvc);
        mvc.perform(TestSupport.getWith(d, "/api/v1/transactions?from=1999-01-01&to=1999-01-02"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
    }
}
