package com.personal.finance;

import static com.personal.finance.TestSupport.event;
import static com.personal.finance.TestSupport.ingest;
import static com.personal.finance.TestSupport.register;
import static com.personal.finance.TestSupport.uniqueSourceId;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.personal.finance.TestSupport.Device;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class UserLabelTest extends AdminTestBase {

    @Test
    void aCorrectionFromThePhoneChangesTheCategoryAndShowsTheNote() throws Exception {
        Device device = register(mvc);
        mvc.perform(ingest(device, event("lbl-1", uniqueSourceId(), "417.00", "ZZ Label Cafe"))).andExpect(status().isOk());
        Cookie cookie = signIn();

        mvc.perform(post("/api/v1/transactions/labels").header("Authorization", device.bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"labels\":[{\"localEventId\":\"lbl-1\",\"category\":\"Groceries\",\"label\":\"milk\"},"
                                + "{\"localEventId\":\"not-there\",\"category\":\"Other\",\"label\":null}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updated").value(1))
                .andExpect(jsonPath("$.missing[0]").value("not-there"));

        mvc.perform(get("/api/admin/transactions?from=2026-10-01&to=2026-11-30&q=zz label").cookie(cookie))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].category").value("Groceries"))
                .andExpect(jsonPath("$.items[0].label").value("milk"));

        // Clearing the correction brings the automatic category back.
        mvc.perform(post("/api/v1/transactions/labels").header("Authorization", device.bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"labels\":[{\"localEventId\":\"lbl-1\",\"category\":null,\"label\":null}]}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/admin/transactions?from=2026-10-01&to=2026-11-30&q=zz label").cookie(cookie))
                .andExpect(jsonPath("$.items[0].category").value("Food & drinks"))
                .andExpect(jsonPath("$.items[0].label").isEmpty());
    }

    @Test
    void ownTransfersAreLeftOutOfTheSummary() throws Exception {
        Device device = register(mvc);
        mvc.perform(ingest(device, event("lbl-2", uniqueSourceId(), "98765.00", "ZZ Own Move"))).andExpect(status().isOk());
        Cookie cookie = signIn();
        mvc.perform(post("/api/v1/transactions/labels").header("Authorization", device.bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"labels\":[{\"localEventId\":\"lbl-2\",\"category\":\"Own transfer\",\"label\":null}]}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/admin/summary?from=2026-10-01&to=2026-11-30").cookie(cookie))
                .andExpect(jsonPath("$.topPayees[?(@.key=='ZZ Own Move')]").isEmpty());
    }

    @Test
    void aSplitPaymentCountsEachPartOnItsOwnAndCanBeAskedAbout() throws Exception {
        Device device = register(mvc);
        mvc.perform(ingest(device, event("spl-1", uniqueSourceId(), "50.00", "ZZ Chai Stall"))).andExpect(status().isOk());
        Cookie cookie = signIn();
        String range = "from=2026-10-01&to=2026-11-30";

        mvc.perform(post("/api/v1/transactions/labels").header("Authorization", device.bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"labels\":[{\"localEventId\":\"spl-1\",\"category\":\"Food & drinks\",\"label\":null,"
                                + "\"splits\":[{\"category\":\"Food & drinks\",\"name\":\"Zzdosa\",\"amount\":20.00},{\"category\":\"Food & drinks\",\"name\":\"Zzchaya\",\"amount\":30.00}]}]}"))
                .andExpect(status().isOk());

        // Two parts in the same category are still told apart by what they were.
        mvc.perform(get("/api/admin/spend?term=zzdosa&" + range).cookie(cookie))
                .andExpect(jsonPath("$.total").value(20.00))
                .andExpect(jsonPath("$.items[0].transaction.splits[0].name").value("Zzdosa"));
        mvc.perform(get("/api/admin/spend?term=zzchaya&" + range).cookie(cookie)).andExpect(jsonPath("$.total").value(30.00));
        mvc.perform(get("/api/admin/spend?term=chai&" + range).cookie(cookie)).andExpect(jsonPath("$.count").value(0));

        mvc.perform(post("/api/v1/transactions/labels").header("Authorization", device.bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"labels\":[{\"localEventId\":\"spl-1\",\"category\":\"Food & drinks\",\"label\":null,"
                                + "\"splits\":[{\"category\":\"Zzcuppa\",\"amount\":20.00},{\"category\":\"Zzbiscuits\",\"amount\":30.00}]}]}"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/admin/spend?term=zzcuppa&" + range).cookie(cookie))
                .andExpect(jsonPath("$.total").value(20.00))
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.items[0].transaction.splits.length()").value(2));
        mvc.perform(get("/api/admin/spend?term=zzbiscuits&" + range).cookie(cookie)).andExpect(jsonPath("$.total").value(30.00));
        // The payee name does not count once the user has said what the money went on.
        mvc.perform(get("/api/admin/spend?term=chai&" + range).cookie(cookie)).andExpect(jsonPath("$.count").value(0));
        mvc.perform(get("/api/admin/transactions?category=Zzcuppa&" + range).cookie(cookie)).andExpect(jsonPath("$.total").value(1));

        // Parts that do not add up to the payment are ignored.
        mvc.perform(post("/api/v1/transactions/labels").header("Authorization", device.bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"labels\":[{\"localEventId\":\"spl-1\",\"category\":null,\"label\":null,"
                                + "\"splits\":[{\"category\":\"Zzcuppa\",\"amount\":20.00},{\"category\":\"Zzbiscuits\",\"amount\":20.00}]}]}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/admin/spend?term=zzcuppa&" + range).cookie(cookie)).andExpect(jsonPath("$.count").value(0));
        mvc.perform(get("/api/admin/spend?term=chai&" + range).cookie(cookie)).andExpect(jsonPath("$.total").value(50.00));
    }

    @Test
    void thePhoneTokenIsRequiredAndOversizedTextIsRejected() throws Exception {
        mvc.perform(post("/api/v1/transactions/labels").contentType(MediaType.APPLICATION_JSON).content("{\"labels\":[]}"))
                .andExpect(status().isUnauthorized());
        Device device = register(mvc);
        mvc.perform(post("/api/v1/transactions/labels").header("Authorization", device.bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"labels\":[{\"localEventId\":\"x\",\"category\":\"" + "a".repeat(60) + "\",\"label\":null}]}"))
                .andExpect(status().isBadRequest());
    }
}
