package com.personal.finance;

import static com.personal.finance.TestSupport.batch;
import static com.personal.finance.TestSupport.event;
import static com.personal.finance.TestSupport.getWith;
import static com.personal.finance.TestSupport.ingest;
import static com.personal.finance.TestSupport.register;
import static com.personal.finance.TestSupport.uniqueSourceId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.personal.finance.TestSupport.Device;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
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
class IngestionFlowTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void registrationWithWrongPairingCodeIsRejected() throws Exception {
        mvc.perform(post("/api/v1/devices/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pairingCode\":\"wrong-code-000\",\"deviceName\":\"x\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void registrationReturnsATokenThatIsNotStoredInPlainText() throws Exception {
        Device device = register(mvc);
        assertThat(device.token()).hasSizeGreaterThan(30);
        Integer plainMatches = jdbc.queryForObject(
                "select count(*) from sync_devices where token_hash = ?", Integer.class, device.token());
        assertThat(plainMatches).isZero();
    }

    @Test
    void ingestionRequiresAValidToken() throws Exception {
        mvc.perform(post("/api/v1/events/ingest").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/events/ingest").header("Authorization", "Bearer not-a-real-token")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unknownPathsAreDeniedWithoutAuthentication() throws Exception {
        mvc.perform(get("/api/something-else")).andExpect(status().isUnauthorized());
    }

    @Test
    void firstUploadIsAcceptedAndRepeatIsADuplicateWithTheSameServerId() throws Exception {
        Device device = register(mvc);
        String sourceId = uniqueSourceId();
        String json = event("local-1", sourceId, "Rs 500 debited from A/c XX0000");

        String first = mvc.perform(ingest(device, json)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED")).andReturn().getResponse().getContentAsString();
        Number serverId = JsonPath.read(first, "$.serverEventId");

        mvc.perform(ingest(device, json)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DUPLICATE"))
                .andExpect(jsonPath("$.serverEventId").value(serverId.intValue()));

        Integer rows = jdbc.queryForObject(
                "select count(*) from raw_financial_events where source_event_id = ?", Integer.class, sourceId);
        assertThat(rows).isEqualTo(1);
        Integer outboxRows = jdbc.queryForObject(
                "select count(*) from outbox_events where aggregate_id = ?", Integer.class, serverId.longValue());
        assertThat(outboxRows).isEqualTo(1);
    }

    @Test
    void duplicateUploadsDoNotCreateASecondTransaction() throws Exception {
        Device device = register(mvc);
        String json = event("local-dup", uniqueSourceId(), "SAMPLE STORES");
        mvc.perform(ingest(device, json)).andExpect(jsonPath("$.status").value("ACCEPTED"));
        mvc.perform(ingest(device, json)).andExpect(jsonPath("$.status").value("DUPLICATE"));

        Integer rows = jdbc.queryForObject("select count(*) from financial_transactions t join raw_financial_events e "
                + "on e.id = t.raw_event_id where e.local_event_id = 'local-dup'", Integer.class);
        assertThat(rows).isEqualTo(1);
    }

    @Test
    void extractedFieldsAreStoredAsColumnsAndNoMessageTextIsKept() throws Exception {
        Device device = register(mvc);
        String body = mvc.perform(ingest(device, event("local-2", uniqueSourceId(), "777.25", "SAMPLE PERSON")))
                .andReturn().getResponse().getContentAsString();
        Number serverId = JsonPath.read(body, "$.serverEventId");

        var row = jdbc.queryForMap("select * from financial_transactions where raw_event_id = ?", serverId.longValue());
        assertThat(row.get("direction")).isEqualTo("DEBIT");
        assertThat(row.get("amount").toString()).isEqualTo("777.25");
        assertThat(row.get("currency")).isEqualTo("INR");
        assertThat(row.get("account_last4")).isEqualTo("0000");
        assertThat(row.get("counterparty")).isEqualTo("SAMPLE PERSON");
        assertThat(row.get("payment_mode")).isEqualTo("UPI");
        assertThat(row.get("reference_no")).isEqualTo("000000000003");
        assertThat(row.get("balance_after").toString()).isEqualTo("1200.50");
        assertThat(row.get("transaction_date").toString()).isEqualTo("2026-10-30");
        assertThat(row.get("bank_code")).isEqualTo("SBIUPI");
        assertThat(row.get("note")).isEqualTo("sample note");

        Integer messageColumns = jdbc.queryForObject("select count(*) from information_schema.columns "
                + "where table_name = 'raw_financial_events' and column_name = 'raw_payload'", Integer.class);
        assertThat(messageColumns).isZero();

        String outboxStatus = jdbc.queryForObject("select status from outbox_events where aggregate_id = ?",
                String.class, serverId.longValue());
        assertThat(outboxStatus).isEqualTo("PENDING");
        String processing = jdbc.queryForObject("select processing_status from raw_financial_events where id = ?",
                String.class, serverId.longValue());
        assertThat(processing).isEqualTo("RECEIVED");
    }

    @Test
    void aTransactionWithoutAPositiveAmountOrDetailsIsRejected() throws Exception {
        Device device = register(mvc);
        mvc.perform(ingest(device, event("local-neg", uniqueSourceId(), "-5.00", "X")))
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.reason").value("invalid fields: transaction.amount"));
        String full = event("local-none", uniqueSourceId(), "X");
        String noTransaction = full.substring(0, full.indexOf("\"transaction\"")) + full.substring(full.indexOf("\"parserStatus\""));
        mvc.perform(ingest(device, noTransaction)).andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.reason").value("invalid fields: transaction"));
    }

    @Test
    void aBatchAcceptsGoodItemsAndRejectsBadOnesIndividually() throws Exception {
        Device device = register(mvc);
        String good = event("local-3", uniqueSourceId(), "Rs 10 debited");
        String bad = event("local-4", " ", "Rs 20 debited");
        mvc.perform(batch(device, "{\"events\":[" + good + "," + bad + "]}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].status").value("ACCEPTED"))
                .andExpect(jsonPath("$.results[1].status").value("REJECTED"))
                .andExpect(jsonPath("$.results[1].reason").value("invalid fields: sourceEventId"));
    }

    @Test
    void rejectionReasonsNeverEchoPayloadContent() throws Exception {
        Device device = register(mvc);
        String bad = event("local-5", "", "SECRET-PAYLOAD-TEXT debited");
        String body = mvc.perform(ingest(device, bad)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED")).andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("SECRET-PAYLOAD-TEXT");
    }

    @Test
    void eventsDatedFarInTheFutureAreRejected() throws Exception {
        Device device = register(mvc);
        String json = event("local-6", uniqueSourceId(), "Rs 1 debited").replace("2026-11-10T08:15:00Z", "2099-01-01T00:00:00Z");
        mvc.perform(ingest(device, json)).andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.reason").value("eventTimestamp is in the future"));
    }

    @Test
    void oversizedBatchesAreRejectedAsABadRequest() throws Exception {
        Device device = register(mvc);
        String items = IntStream.range(0, 101)
                .mapToObj(i -> event("l" + i, uniqueSourceId(), "Rs 1 debited")).collect(Collectors.joining(","));
        mvc.perform(batch(device, "{\"events\":[" + items + "]}")).andExpect(status().isBadRequest());
        mvc.perform(batch(device, "{\"events\":[]}")).andExpect(status().isBadRequest());
    }

    @Test
    void malformedJsonIsABadRequestWithoutLeakingTheBody() throws Exception {
        Device device = register(mvc);
        String body = mvc.perform(ingest(device, "{\"payload\": \"TOP-SECRET\" nope"))
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("TOP-SECRET");
    }

    @Test
    void processingStatusIsVisibleToTheOwningDeviceOnly() throws Exception {
        Device owner = register(mvc);
        Device other = register(mvc);
        String body = mvc.perform(ingest(owner, event("local-7", uniqueSourceId(), "Rs 5 debited")))
                .andReturn().getResponse().getContentAsString();
        Number id = JsonPath.read(body, "$.serverEventId");

        mvc.perform(getWith(owner, "/api/v1/events/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.processingStatus").value("RECEIVED"));
        mvc.perform(getWith(other, "/api/v1/events/" + id)).andExpect(status().isNotFound());
    }

    @Test
    void theSameSourceEventFromTwoDevicesIsStoredSeparately() throws Exception {
        Device a = register(mvc);
        Device b = register(mvc);
        String json = event("local-8", uniqueSourceId(), "Rs 5 debited");
        mvc.perform(ingest(a, json)).andExpect(jsonPath("$.status").value("ACCEPTED"));
        mvc.perform(ingest(b, json)).andExpect(jsonPath("$.status").value("ACCEPTED"));
    }

    @Test
    void heartbeatWorksAndRevocationStopsTheToken() throws Exception {
        Device device = register(mvc);
        mvc.perform(post("/api/v1/devices/heartbeat").header("Authorization", device.bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.serverTime").exists());
        mvc.perform(post("/api/v1/devices/revoke").header("Authorization", device.bearer()))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/devices/heartbeat").header("Authorization", device.bearer()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void healthEndpointIsPublicAndRevealsNoDetails() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP")).andExpect(jsonPath("$.components").doesNotExist());
    }
}
