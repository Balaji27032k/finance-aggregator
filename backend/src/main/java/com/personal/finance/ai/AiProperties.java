package com.personal.finance.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Settings for the optional AI model. Without an API key the app answers with its built-in search only. */
@ConfigurationProperties(prefix = "app.ai")
public record AiProperties(String apiKey, String model, Integer dailyLimit) {

    public boolean enabled() {
        return apiKey != null && !apiKey.isBlank();
    }

    public String modelName() {
        return model != null && model.matches("[A-Za-z0-9._-]{3,60}") ? model : "gemini-flash-lite-latest";
    }

    /** The most model calls per day, kept well inside the free allowance. */
    public int limit() {
        return dailyLimit != null && dailyLimit > 0 ? dailyLimit : 150;
    }
}
