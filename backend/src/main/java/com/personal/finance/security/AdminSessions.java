package com.personal.finance.security;

import com.personal.finance.common.Hashing;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** Dashboard sessions kept in memory. A restart signs everyone out, which is fine for a single-user service. */
@Component
public class AdminSessions {

    public static final Duration TTL = Duration.ofHours(12);

    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();

    private record Session(String username, Instant expiresAt) {
    }

    public AdminSessions(Clock clock) {
        this.clock = clock;
    }

    /** Returns the new token; only its hash is stored. */
    public String create(String username) {
        sessions.values().removeIf(s -> s.expiresAt().isBefore(clock.instant()));
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        sessions.put(Hashing.sha256Hex(token), new Session(username, clock.instant().plus(TTL)));
        return token;
    }

    /** The username of a live session, or null. */
    public String usernameFor(String token) {
        if (token == null || token.isBlank() || token.length() > 128) {
            return null;
        }
        Session s = sessions.get(Hashing.sha256Hex(token));
        return s != null && s.expiresAt().isAfter(clock.instant()) ? s.username() : null;
    }

    public void revoke(String token) {
        if (token != null) {
            sessions.remove(Hashing.sha256Hex(token));
        }
    }
}
