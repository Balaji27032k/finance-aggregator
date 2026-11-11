package com.personal.finance;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.personal.finance.security.AdminSessionFilter;
import jakarta.servlet.http.Cookie;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** Shared set-up for tests that sign in to the dashboard API. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
abstract class AdminTestBase {

    static final String ADMIN_USER = "admin";
    static final String ADMIN_PASSWORD = "test-admin-password";

    @Autowired
    MockMvc mvc;

    @DynamicPropertySource
    static void adminProperties(DynamicPropertyRegistry registry) {
        registry.add("app.admin.username", () -> ADMIN_USER);
        registry.add("app.admin.password-hash", () -> new BCryptPasswordEncoder().encode(ADMIN_PASSWORD));
    }

    String loginSetCookie(String user, String password) throws Exception {
        return mvc.perform(post("/api/admin/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + user + "\",\"password\":\"" + password + "\"}"))
                .andReturn().getResponse().getHeader(HttpHeaders.SET_COOKIE);
    }

    Cookie signIn() throws Exception {
        String header = loginSetCookie(ADMIN_USER, ADMIN_PASSWORD);
        String pair = header.substring(0, header.indexOf(';'));
        return new Cookie(AdminSessionFilter.COOKIE, pair.substring(pair.indexOf('=') + 1));
    }
}
