package com.personal.finance.ai;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Calls Google's Gemini API with the free key. Prompts, replies and the key are never logged. */
@Component
public class GeminiClient implements AiModel {

    private static final Logger log = LoggerFactory.getLogger(GeminiClient.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final AiProperties properties;
    private final Clock clock;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final JsonMapper mapper = JsonMapper.builder().build();

    private LocalDate day;
    private int used;
    private Instant pausedUntil = Instant.EPOCH;

    public GeminiClient(AiProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public boolean configured() {
        return properties.enabled();
    }

    @Override
    public Optional<String> generate(String system, String user, boolean json) {
        if (!configured() || !acquire()) {
            return Optional.empty();
        }
        try {
            Map<String, Object> config = json
                    ? Map.of("temperature", 0.2, "maxOutputTokens", 900, "responseMimeType", "application/json")
                    : Map.of("temperature", 0.5, "maxOutputTokens", 700);
            String body = mapper.writeValueAsString(Map.of(
                    "systemInstruction", Map.of("parts", List.of(Map.of("text", system))),
                    "contents", List.of(Map.of("role", "user", "parts", List.of(Map.of("text", user)))),
                    "generationConfig", config));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://generativelanguage.googleapis.com/v1beta/models/" + properties.modelName() + ":generateContent"))
                    .header("Content-Type", "application/json")
                    .header("x-goog-api-key", properties.apiKey())
                    .timeout(Duration.ofSeconds(25))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 429) {
                pause();
                log.warn("AI model rate limit reached; using the built-in search for a minute");
                return Optional.empty();
            }
            if (response.statusCode() != 200) {
                log.warn("AI model answered HTTP {}", response.statusCode());
                return Optional.empty();
            }
            JsonNode text = mapper.readTree(response.body()).path("candidates").path(0).path("content").path("parts").path(0).path("text");
            String reply = text.asString("");
            return reply.isBlank() ? Optional.empty() : Optional.of(reply);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (IOException | RuntimeException e) {
            log.warn("AI model call failed: {}", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private synchronized boolean acquire() {
        Instant now = clock.instant();
        LocalDate today = LocalDate.now(clock.withZone(IST));
        if (!today.equals(day)) {
            day = today;
            used = 0;
        }
        if (now.isBefore(pausedUntil) || used >= properties.limit()) {
            return false;
        }
        used++;
        return true;
    }

    private synchronized void pause() {
        pausedUntil = clock.instant().plusSeconds(60);
    }
}
