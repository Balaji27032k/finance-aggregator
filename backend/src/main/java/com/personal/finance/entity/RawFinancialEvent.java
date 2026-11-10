package com.personal.finance.entity;

import com.personal.finance.common.EncryptedStringConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "raw_financial_events")
public class RawFinancialEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "device_id", nullable = false, updatable = false)
    private Long deviceId;

    @Column(name = "local_event_id", nullable = false, updatable = false)
    private String localEventId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, updatable = false)
    private SourceType sourceType;

    @Column(name = "source_application", nullable = false, updatable = false)
    private String sourceApplication;

    @Column(name = "source_sender", updatable = false)
    private String sourceSender;

    @Column(name = "source_event_id", nullable = false, updatable = false)
    private String sourceEventId;

    @Column(name = "event_timestamp", nullable = false, updatable = false)
    private Instant eventTimestamp;

    @Column(name = "captured_timestamp", nullable = false, updatable = false)
    private Instant capturedTimestamp;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "raw_payload", nullable = false, updatable = false)
    private String rawPayload;

    @Column(name = "payload_hash", nullable = false, updatable = false)
    private String payloadHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "parser_status", nullable = false)
    private ParserStatus parserStatus;

    @Column(name = "parser_version", nullable = false)
    private String parserVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "processing_status", nullable = false)
    private ProcessingStatus processingStatus;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected RawFinancialEvent() {
    }

    public RawFinancialEvent(Long deviceId, String localEventId, SourceType sourceType, String sourceApplication,
                             String sourceSender, String sourceEventId, Instant eventTimestamp,
                             Instant capturedTimestamp, String rawPayload, String payloadHash,
                             ParserStatus parserStatus, String parserVersion, Instant now) {
        this.deviceId = deviceId;
        this.localEventId = localEventId;
        this.sourceType = sourceType;
        this.sourceApplication = sourceApplication;
        this.sourceSender = sourceSender;
        this.sourceEventId = sourceEventId;
        this.eventTimestamp = eventTimestamp;
        this.capturedTimestamp = capturedTimestamp;
        this.rawPayload = rawPayload;
        this.payloadHash = payloadHash;
        this.parserStatus = parserStatus;
        this.parserVersion = parserVersion;
        this.processingStatus = ProcessingStatus.RECEIVED;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public Long getId() {
        return id;
    }
}
