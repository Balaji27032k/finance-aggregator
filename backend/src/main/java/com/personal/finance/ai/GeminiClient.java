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
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
    private volatile String problem;

    public GeminiClient(AiProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public boolean configured() {
        return properties.enabled();
    }

    @Override
    public String lastProblem() {
        return problem;
    }

    @Override
    public Optional<String> generate(String system, String user, boolean json) {
        Map<String, Object> config = json
                ? Map.of("temperature", 0.2, "maxOutputTokens", 900, "responseMimeType", "application/json")
                : Map.of("temperature", 0.5, "maxOutputTokens", 700);
        return call(system, user, config, false, 25).map(root -> text(root)).filter(t -> !t.isBlank());
    }

    @Override
    public Optional<Grounded> generateGrounded(String system, String user) {
        Map<String, Object> config = Map.of("temperature", 0.1, "maxOutputTokens", 2500);
        return call(system, user, config, true, 60).map(root -> new Grounded(text(root), sources(root))).filter(g -> !g.text().isBlank());
    }

    private static String text(JsonNode root) {
        StringBuilder out = new StringBuilder();
        for (JsonNode part : root.path("candidates").path(0).path("content").path("parts")) {
            out.append(part.path("text").asString(""));
        }
        return out.toString();
    }

    private static List<Source> sources(JsonNode root) {
        List<Source> out = new ArrayList<>();
        for (JsonNode chunk : root.path("candidates").path(0).path("groundingMetadata").path("groundingChunks")) {
            String url = chunk.path("web").path("uri").asString("");
            if (!url.isBlank() && out.stream().noneMatch(s -> s.url().equals(url))) {
                out.add(new Source(chunk.path("web").path("title").asString(""), url));
            }
        }
        return out;
    }

    private Optional<JsonNode> call(String system, String user, Map<String, Object> config, boolean webSearch, int timeoutSeconds) {
        problem = null;
        if (!configured()) {
            problem = "No AI key is set on the server.";
            return Optional.empty();
        }
        if (!acquire()) {
            problem = "The AI is resting (daily limit or rate limit).";
            return Optional.empty();
        }
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("systemInstruction", Map.of("parts", List.of(Map.of("text", system))));
            body.put("contents", List.of(Map.of("role", "user", "parts", List.of(Map.of("text", user)))));
            body.put("generationConfig", config);
            if (webSearch) {
                body.put("tools", List.of(Map.of("google_search", Map.of())));
            }
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://generativelanguage.googleapis.com/v1beta/models/" + properties.modelName() + ":generateContent"))
                    .header("Content-Type", "application/json")
                    .header("x-goog-api-key", properties.apiKey())
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 429) {
                pause();
                problem = "The AI is rate limited right now. Try again in a minute.";
                log.warn("AI model rate limit reached; using the built-in search for a minute");
                return Optional.empty();
            }
            if (response.statusCode() != 200) {
                problem = webSearch && (response.statusCode() == 400 || response.statusCode() == 403)
                        ? "Web search is not available for this AI key or model (HTTP " + response.statusCode() + ")."
                        : "The AI answered HTTP " + response.statusCode() + ".";
                log.warn("AI model answered HTTP {}", response.statusCode());
                return Optional.empty();
            }
            return Optional.of(mapper.readTree(response.body()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (IOException | RuntimeException e) {
            problem = "The AI could not be reached.";
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
