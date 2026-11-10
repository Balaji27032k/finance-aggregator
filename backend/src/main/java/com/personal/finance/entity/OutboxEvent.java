package com.personal.finance.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "outbox_events")
public class OutboxEvent {

    public enum Status { PENDING, PUBLISHED, FAILED }

    public static final String RAW_EVENT_RECEIVED = "RAW_EVENT_RECEIVED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "aggregate_id", nullable = false, updatable = false)
    private Long aggregateId;

    @Column(name = "event_type", nullable = false, updatable = false)
    private String eventType;

    @Column(name = "schema_version", nullable = false, updatable = false)
    private int schemaVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    protected OutboxEvent() {
    }

    public static OutboxEvent rawEventReceived(Long rawEventId, Instant now) {
        OutboxEvent e = new OutboxEvent();
        e.aggregateId = rawEventId;
        e.eventType = RAW_EVENT_RECEIVED;
        e.schemaVersion = 1;
        e.status = Status.PENDING;
        e.attempts = 0;
        e.nextAttemptAt = now;
        e.createdAt = now;
        return e;
    }

    public Long getId() {
        return id;
    }
}
