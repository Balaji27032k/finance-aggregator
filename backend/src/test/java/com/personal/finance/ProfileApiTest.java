package com.personal.finance;

import static com.personal.finance.TestSupport.register;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.personal.finance.TestSupport.Device;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class ProfileApiTest extends AdminTestBase {

    private static final String BODY = "{\"age\":32,\"occupation\":\"SALARIED\",\"monthlyIncome\":80000,\"incomeStability\":\"STABLE\","
            + "\"dependents\":2,\"riskComfort\":\"MEDIUM\",\"experience\":\"SOME\",\"taxRegime\":\"NEW\",\"emergencyFund\":150000,"
            + "\"goals\":[{\"name\":\"House\",\"amount\":1500000,\"year\":2032,\"priority\":\"HIGH\"}],"
            + "\"investments\":[{\"type\":\"PPF\",\"amount\":300000,\"monthly\":5000}],"
            + "\"loans\":[{\"type\":\"Car loan\",\"outstanding\":400000,\"emi\":12000,\"rate\":9.5}],"
            + "\"assumptions\":{\"inflation\":6,\"equity\":12},\"notes\":\"Planning for a house\"}";

    @Test
    void theProfileIsSavedFromThePhoneAndReadBackOnBothSides() throws Exception {
        Device device = register(mvc);
        mvc.perform(get("/api/v1/profile").header("Authorization", device.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.age").isEmpty());

        mvc.perform(put("/api/v1/profile").header("Authorization", device.bearer()).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/profile").header("Authorization", device.bearer()))
                .andExpect(jsonPath("$.age").value(32))
                .andExpect(jsonPath("$.goals[0].name").value("House"))
                .andExpect(jsonPath("$.investments[0].type").value("PPF"))
                .andExpect(jsonPath("$.loans[0].emi").value(12000))
                .andExpect(jsonPath("$.assumptions.equity").value(12.0));
        mvc.perform(get("/api/admin/profile").cookie(signIn()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.monthlyIncome").value(80000));
    }

    @Test
    void impossibleValuesAndMissingSignInAreRejected() throws Exception {
        Device device = register(mvc);
        mvc.perform(put("/api/v1/profile").header("Authorization", device.bearer()).contentType(MediaType.APPLICATION_JSON).content("{\"age\":3}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/v1/profile").header("Authorization", device.bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"riskComfort\":\"YOLO\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/v1/profile").header("Authorization", device.bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assumptions\":{\"equity\":90}}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/profile")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/profile")).andExpect(status().isUnauthorized());
    }
}
