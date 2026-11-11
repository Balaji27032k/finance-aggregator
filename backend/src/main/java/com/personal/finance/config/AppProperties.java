package com.personal.finance.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app")
public record AppProperties(@Valid Security security, @Valid RateLimit rateLimit, Admin admin) {

    /** Dashboard login. Without a password hash the dashboard login stays disabled. */
    public record Admin(String username, String passwordHash) {
        public boolean configured() {
            return username != null && !username.isBlank() && passwordHash != null && !passwordHash.isBlank();
        }
    }

    public record Security(
            @NotBlank @Size(min = 12) String pairingCode,
            @NotBlank String payloadEncryptionKey) {
    }

    public record RateLimit(@Min(1) int anonymousPerMinute, @Min(1) int devicePerMinute) {
    }
}
