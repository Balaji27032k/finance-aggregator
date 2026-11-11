package com.personal.finance.controller;

import com.personal.finance.config.AppProperties;
import com.personal.finance.security.AdminPrincipal;
import com.personal.finance.security.AdminSessionFilter;
import com.personal.finance.security.AdminSessions;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
public class AdminAuthController {

    public record LoginRequest(@NotBlank @Size(max = 100) String username, @NotBlank @Size(max = 200) String password) {
    }

    private final AppProperties properties;
    private final AdminSessions sessions;
    private final PasswordEncoder encoder;

    public AdminAuthController(AppProperties properties, AdminSessions sessions, PasswordEncoder encoder) {
        this.properties = properties;
        this.sessions = sessions;
        this.encoder = encoder;
    }

    @PostMapping("/login")
    ResponseEntity<?> login(@Valid @RequestBody LoginRequest body, HttpServletRequest request) {
        AppProperties.Admin admin = properties.admin();
        if (admin == null || !admin.configured()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error", "Dashboard login is not configured"));
        }
        // Both checks always run so a wrong user name takes as long as a wrong password.
        boolean userOk = MessageDigest.isEqual(admin.username().getBytes(StandardCharsets.UTF_8),
                body.username().getBytes(StandardCharsets.UTF_8));
        boolean passwordOk = encoder.matches(body.password(), admin.passwordHash());
        if (!(userOk && passwordOk)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Wrong user name or password"));
        }
        String token = sessions.create(admin.username());
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cookie(token, request, AdminSessions.TTL.toSeconds()).toString())
                .body(Map.of("username", admin.username()));
    }

    @PostMapping("/logout")
    ResponseEntity<Void> logout(HttpServletRequest request) {
        sessions.revoke(AdminSessionFilter.tokenFrom(request));
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookie("", request, 0).toString()).build();
    }

    @GetMapping("/me")
    Map<String, String> me(@AuthenticationPrincipal AdminPrincipal principal) {
        return Map.of("username", principal.username());
    }

    private static ResponseCookie cookie(String value, HttpServletRequest request, long maxAgeSeconds) {
        boolean secure = request.isSecure() || "https".equalsIgnoreCase(request.getHeader("X-Forwarded-Proto"));
        return ResponseCookie.from(AdminSessionFilter.COOKIE, value).httpOnly(true).secure(secure).sameSite("Strict")
                .path("/").maxAge(maxAgeSeconds).build();
    }
}
