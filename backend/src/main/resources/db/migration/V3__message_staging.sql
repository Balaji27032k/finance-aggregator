-- Temporary analysis table: full SMS/e-mail text uploaded on request, to study bank message formats. Drop it when done.
CREATE TABLE IF NOT EXISTS message_staging (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    device_id      BIGINT       NOT NULL,
    channel        VARCHAR(8)   NOT NULL,
    external_id    VARCHAR(128) NOT NULL,
    sender         VARCHAR(255),
    subject        VARCHAR(500),
    received_at    DATETIME(3)  NOT NULL,
    body           MEDIUMTEXT   NOT NULL,
    body_truncated TINYINT(1)   NOT NULL DEFAULT 0,
    labels         VARCHAR(255),
    created_at     DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_staging_message UNIQUE (device_id, channel, external_id),
    CONSTRAINT fk_staging_device FOREIGN KEY (device_id) REFERENCES sync_devices (id),
    KEY idx_staging_channel_sender (channel, sender),
    KEY idx_staging_received (received_at)
);
