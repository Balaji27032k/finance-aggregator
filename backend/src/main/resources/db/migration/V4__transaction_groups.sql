-- Groups rows that describe the same real transaction seen through different channels (SMS, e-mail, notification).
ALTER TABLE financial_transactions ADD COLUMN source_type VARCHAR(16);
ALTER TABLE financial_transactions ADD COLUMN group_id BIGINT;
ALTER TABLE financial_transactions ADD COLUMN match_basis VARCHAR(8);

UPDATE financial_transactions SET group_id = id, match_basis = 'NEW';
UPDATE financial_transactions
SET source_type = (SELECT r.source_type FROM raw_financial_events r WHERE r.id = financial_transactions.raw_event_id);

CREATE INDEX idx_transactions_group ON financial_transactions (group_id);
CREATE INDEX idx_transactions_match ON financial_transactions (direction, amount, event_timestamp);
