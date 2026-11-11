package com.personal.finance;

import static com.personal.finance.TestSupport.ingest;
import static com.personal.finance.TestSupport.register;
import static com.personal.finance.TestSupport.uniqueSourceId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import com.personal.finance.TestSupport.Device;
import com.personal.finance.service.TransactionMatcher;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** The same real transaction seen through SMS, e-mail and notification ends up under one group id. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TransactionGroupingTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    private static String json(String localId, String sourceType, String time, String direction, String amount,
                               String counterparty, String reference) {
        return """
                {"localEventId":"%s","sourceType":"%s","sourceApplication":"APP","sourceSender":null,
                 "sourceEventId":"%s","eventTimestamp":"%s","capturedTimestamp":"%s","payloadHash":"%s",
                 "transaction":{"direction":"%s","amount":"%s","currency":"INR","accountLast4":"0000",
                  "counterparty":%s,"paymentMode":"UPI","referenceNo":%s,"balanceAfter":null,
                  "transactionDate":"2026-11-10","bankCode":"TEST","note":null},
                 "parserStatus":"PARSED","parserVersion":"parser-3"}"""
                .formatted(localId, sourceType, uniqueSourceId(), time, time, TestSupport.HASH, direction, amount,
                        counterparty == null ? "null" : "\"" + counterparty + "\"",
                        reference == null ? "null" : "\"" + reference + "\"");
    }

    private void send(Device d, String body) throws Exception {
        mvc.perform(ingest(d, body)).andExpect(jsonPath("$.status").value("ACCEPTED"));
    }

    private Map<String, Object> row(String localId) {
        return jdbc.queryForMap("select t.id, t.group_id, t.match_basis from financial_transactions t "
                + "join raw_financial_events e on e.id = t.raw_event_id where e.local_event_id = ?", localId);
    }

    @Test
    void anSmsAndAnEmailWithTheSameReferenceShareAGroup() throws Exception {
        Device d = register(mvc);
        send(d, json("g1-sms", "SMS", "2026-11-10T08:15:00Z", "DEBIT", "501.01", "SAMPLE SHOP", "611100000001"));
        send(d, json("g1-mail", "GMAIL", "2026-11-10T08:40:00Z", "DEBIT", "501.01", "Sample Shop Pvt", "611100000001"));

        assertThat(row("g1-mail").get("group_id")).isEqualTo(row("g1-sms").get("id"));
        assertThat(row("g1-mail").get("match_basis")).isEqualTo("REF");
        assertThat(row("g1-sms").get("match_basis")).isEqualTo("NEW");
    }

    @Test
    void theTwoLegsOfATransferAreNotMergedAsDuplicates() throws Exception {
        Device d = register(mvc);
        send(d, json("g2-out", "SMS", "2026-11-10T09:00:00Z", "DEBIT", "502.02", "OWN", "611100000002"));
        send(d, json("g2-in", "SMS", "2026-11-10T09:00:05Z", "CREDIT", "502.02", "OWN", "611100000002"));

        assertThat(row("g2-in").get("group_id")).isEqualTo(row("g2-in").get("id"));
        assertThat(row("g2-in").get("group_id")).isNotEqualTo(row("g2-out").get("group_id"));
    }

    @Test
    void aNotificationWithoutAReferenceJoinsTheSmsSeenSecondsLater() throws Exception {
        Device d = register(mvc);
        send(d, json("g3-notif", "NOTIFICATION", "2026-11-10T10:00:00Z", "DEBIT", "503.03", "Sample Cafe", null));
        send(d, json("g3-sms", "SMS", "2026-11-10T10:00:40Z", "DEBIT", "503.03", "SAMPLE CAFE", "611100000003"));

        assertThat(row("g3-sms").get("group_id")).isEqualTo(row("g3-notif").get("id"));
        assertThat(row("g3-sms").get("match_basis")).isEqualTo("TIME");
    }

    @Test
    void sameAmountFromTheSameChannelOrFarApartOrWithAnotherPayeeStaysSeparate() throws Exception {
        Device d = register(mvc);
        send(d, json("g4-a", "SMS", "2026-11-10T11:00:00Z", "DEBIT", "504.04", "Shop A", "611100000004"));
        send(d, json("g4-b", "SMS", "2026-11-10T11:01:00Z", "DEBIT", "504.04", "Shop A", "611100000005"));
        send(d, json("g4-n-late", "NOTIFICATION", "2026-11-10T11:30:00Z", "DEBIT", "504.04", "Shop A", null));
        send(d, json("g4-n-other", "NOTIFICATION", "2026-11-10T11:00:30Z", "DEBIT", "504.04", "Tea Stall", null));

        for (String id : new String[] {"g4-b", "g4-n-late", "g4-n-other"}) {
            assertThat(row(id).get("group_id")).as(id).isEqualTo(row(id).get("id"));
        }
        assertThat(row("g4-a").get("group_id")).isEqualTo(row("g4-a").get("id"));
    }

    @Test
    void counterpartyNamesMatchLoosely() {
        assertThat(TransactionMatcher.compatible("SAMPLE SHOP", "Sample Shop Pvt Ltd")).isTrue();
        assertThat(TransactionMatcher.compatible(null, "Anything")).isTrue();
        assertThat(TransactionMatcher.compatible("Tea Stall", "Shop A")).isFalse();
    }
}
