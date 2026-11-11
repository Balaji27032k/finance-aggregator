package com.personal.finance.service;

import com.personal.finance.dto.StagedMessageItem;
import com.personal.finance.dto.StagingBatchResponse;
import com.personal.finance.exception.ServiceUnavailableException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.support.TransactionTemplate;

/** Stores full message text for format analysis. One database round trip per batch; repeats are ignored. */
@Service
public class MessageStagingService {

    private static final String INSERT = "INSERT IGNORE INTO message_staging (device_id, channel, external_id, sender, "
            + "subject, received_at, body, body_truncated, labels, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public MessageStagingService(JdbcTemplate jdbc, TransactionTemplate transactions, Clock clock) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.clock = clock;
    }

    public StagingBatchResponse store(Long deviceId, List<StagedMessageItem> items) {
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        try {
            int[][] results = transactions.execute(status -> jdbc.batchUpdate(INSERT, items, items.size(), (ps, m) -> {
                ps.setLong(1, deviceId);
                ps.setString(2, m.channel().name());
                ps.setString(3, m.externalId());
                ps.setString(4, m.sender());
                ps.setString(5, m.subject());
                ps.setObject(6, LocalDateTime.ofInstant(m.receivedAt(), ZoneOffset.UTC));
                ps.setString(7, m.body());
                ps.setBoolean(8, m.bodyTruncated());
                ps.setString(9, m.labels());
                ps.setObject(10, now);
            }));
            int stored = 0;
            for (int[] chunk : results) {
                for (int r : chunk) {
                    if (r != 0) {
                        stored++;
                    }
                }
            }
            return new StagingBatchResponse(items.size(), stored, items.size() - stored);
        } catch (DataAccessResourceFailureException | TransientDataAccessException | CannotCreateTransactionException e) {
            throw new ServiceUnavailableException(e);
        }
    }
}
