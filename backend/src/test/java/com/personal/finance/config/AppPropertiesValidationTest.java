package com.personal.finance.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class AppPropertiesValidationTest {

    @Configuration
    @EnableConfigurationProperties(AppProperties.class)
    static class Config {
    }

    private static final String KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Config.class)
            .withPropertyValues("app.rate-limit.anonymous-per-minute=5", "app.rate-limit.device-per-minute=300");

    @Test
    void validSecretsStart() {
        runner.withPropertyValues("app.security.pairing-code=0123456789ab", "app.security.payload-encryption-key=" + KEY)
                .run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @Test
    void aMissingEnvironmentVariableStopsStartupInsteadOfBecomingTheSecret() {
        // Same placeholder form as application-mysql.yml, with the variable absent.
        runner.withPropertyValues(
                        "app.security.pairing-code=${NO_SUCH_PAIRING_VARIABLE:}",
                        "app.security.payload-encryption-key=${NO_SUCH_KEY_VARIABLE:}")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void aMissingPairingCodeAloneAlsoStopsStartup() {
        runner.withPropertyValues("app.security.pairing-code=${NO_SUCH_PAIRING_VARIABLE:}", "app.security.payload-encryption-key=" + KEY)
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void aShortPairingCodeIsRejected() {
        runner.withPropertyValues("app.security.pairing-code=short", "app.security.payload-encryption-key=" + KEY)
                .run(ctx -> assertThat(ctx).hasFailed());
    }
}
