-- Raw message text is no longer stored. Rows ingested before this migration have no structured data, so they are removed.
DELETE FROM outbox_events;
DELETE FROM raw_financial_events;

ALTER TABLE raw_financial_events DROP COLUMN raw_payload;

CREATE TABLE financial_transactions (
    id                         BIGINT         NOT NULL AUTO_INCREMENT,
    raw_event_id               BIGINT         NOT NULL,
    device_id                  BIGINT         NOT NULL,
    direction                  VARCHAR(8)     NOT NULL,
    amount                     DECIMAL(15, 2) NOT NULL,
    currency                   CHAR(3)        NOT NULL,
    account_last4              VARCHAR(8),
    counterparty               VARCHAR(200),
    counterparty_account_last4 VARCHAR(8),
    payment_mode               VARCHAR(16),
    reference_no               VARCHAR(64),
    balance_after              DECIMAL(15, 2),
    transaction_date           DATE,
    bank_code                  VARCHAR(32)    NOT NULL,
    note                       VARCHAR(255),
    event_timestamp            DATETIME(3)    NOT NULL,
    created_at                 DATETIME(3)    NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_transactions_raw_event FOREIGN KEY (raw_event_id) REFERENCES raw_financial_events (id),
    CONSTRAINT uq_transactions_raw_event UNIQUE (raw_event_id)
);

CREATE INDEX idx_transactions_date ON financial_transactions (transaction_date);
CREATE INDEX idx_transactions_reference ON financial_transactions (reference_no);
CREATE INDEX idx_transactions_event_ts ON financial_transactions (event_timestamp);
