package com.personal.finance;

import static com.personal.finance.TestSupport.event;
import static com.personal.finance.TestSupport.ingest;
import static com.personal.finance.TestSupport.register;
import static com.personal.finance.TestSupport.uniqueSourceId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.personal.finance.TestSupport.Device;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

class AdminAuthTest extends AdminTestBase {

    @Test
    void loginSetsAnHttpOnlyStrictCookieAndTheSessionWorks() throws Exception {
        String header = loginSetCookie(ADMIN_USER, ADMIN_PASSWORD);
        assertThat(header).contains("HttpOnly").contains("SameSite=Strict").contains("Path=/");

        mvc.perform(get("/api/admin/me").cookie(signIn())).andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(ADMIN_USER));
    }

    @Test
    void wrongPasswordOrUserIsRejected() throws Exception {
        mvc.perform(post("/api/admin/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"nope\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/admin/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"someone\",\"password\":\"" + ADMIN_PASSWORD + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void adminEndpointsNeedTheSessionCookie() throws Exception {
        mvc.perform(get("/api/admin/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/summary").cookie(new Cookie(com.personal.finance.security.AdminSessionFilter.COOKIE, "forged")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void logoutEndsTheSession() throws Exception {
        Cookie cookie = signIn();
        mvc.perform(post("/api/admin/logout").cookie(cookie)).andExpect(status().isNoContent())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().exists(HttpHeaders.SET_COOKIE));
        mvc.perform(get("/api/admin/me").cookie(cookie)).andExpect(status().isUnauthorized());
    }

    @Test
    void aPhoneTokenCannotUseTheDashboardAndTheDashboardCannotUsePhoneEndpoints() throws Exception {
        Device device = register(mvc);
        mvc.perform(get("/api/admin/summary").header("Authorization", device.bearer())).andExpect(status().isForbidden());

        mvc.perform(post("/api/v1/events/ingest").cookie(signIn()).contentType(MediaType.APPLICATION_JSON)
                        .content(event("l1", uniqueSourceId(), "x")))
                .andExpect(status().isForbidden());
        // The phone path itself still works alongside the dashboard.
        mvc.perform(ingest(device, event("l2", uniqueSourceId(), "x"))).andExpect(status().isOk());
    }
}
