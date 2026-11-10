CREATE TABLE sync_devices (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    name          VARCHAR(100) NOT NULL,
    app_version   VARCHAR(32),
    token_hash    CHAR(64)     NOT NULL,
    created_at    DATETIME(3)  NOT NULL,
    last_seen_at  DATETIME(3),
    revoked_at    DATETIME(3),
    PRIMARY KEY (id),
    CONSTRAINT uq_sync_devices_token_hash UNIQUE (token_hash)
);

CREATE TABLE raw_financial_events (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    device_id           BIGINT       NOT NULL,
    local_event_id      VARCHAR(64)  NOT NULL,
    source_type         VARCHAR(16)  NOT NULL,
    source_application  VARCHAR(128) NOT NULL,
    source_sender       VARCHAR(128),
    source_event_id     VARCHAR(128) NOT NULL,
    external_reference  VARCHAR(128),
    event_timestamp     DATETIME(3)  NOT NULL,
    captured_timestamp  DATETIME(3)  NOT NULL,
    raw_payload         LONGTEXT     NOT NULL,
    payload_hash        CHAR(64)     NOT NULL,
    parser_status       VARCHAR(16)  NOT NULL,
    parser_version      VARCHAR(32)  NOT NULL,
    processing_status   VARCHAR(16)  NOT NULL,
    created_at          DATETIME(3)  NOT NULL,
    updated_at          DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_raw_events_device FOREIGN KEY (device_id) REFERENCES sync_devices (id),
    CONSTRAINT uq_raw_events_source UNIQUE (device_id, source_type, source_event_id)
);

CREATE INDEX idx_raw_events_processing ON raw_financial_events (processing_status, id);
CREATE INDEX idx_raw_events_hash ON raw_financial_events (payload_hash);
CREATE INDEX idx_raw_events_event_ts ON raw_financial_events (event_timestamp);

CREATE TABLE outbox_events (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    aggregate_id     BIGINT       NOT NULL,
    event_type       VARCHAR(64)  NOT NULL,
    schema_version   INT          NOT NULL,
    status           VARCHAR(16)  NOT NULL,
    attempts         INT          NOT NULL DEFAULT 0,
    next_attempt_at  DATETIME(3)  NOT NULL,
    last_error       VARCHAR(255),
    created_at       DATETIME(3)  NOT NULL,
    published_at     DATETIME(3),
    PRIMARY KEY (id),
    CONSTRAINT fk_outbox_raw_event FOREIGN KEY (aggregate_id) REFERENCES raw_financial_events (id),
    CONSTRAINT uq_outbox_aggregate_type UNIQUE (aggregate_id, event_type)
);

CREATE INDEX idx_outbox_pending ON outbox_events (status, next_attempt_at);
