package com.personal.finance.service;

import com.personal.finance.dto.Profile;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/** Keeps the single person's profile as JSON in one row. */
@Service
public class ProfileService {

    private static final int ROW = 1;

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final JsonMapper mapper = JsonMapper.builder().build();

    public ProfileService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public Optional<Profile> find() {
        List<String> rows = jdbc.queryForList("SELECT body FROM money_profile WHERE id = ?", String.class, ROW);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(mapper.readValue(rows.get(0), Profile.class));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    public Profile get() {
        return find().orElseGet(Profile::empty);
    }

    public void save(Profile profile) {
        String body = mapper.writeValueAsString(profile);
        Timestamp now = Timestamp.from(clock.instant());
        int updated = jdbc.update("UPDATE money_profile SET body = ?, updated_at = ? WHERE id = ?", body, now, ROW);
        if (updated == 0) {
            jdbc.update("INSERT INTO money_profile (id, body, updated_at) VALUES (?, ?, ?)", ROW, body, now);
        }
    }
}
