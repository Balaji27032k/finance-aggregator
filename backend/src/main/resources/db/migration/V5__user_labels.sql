-- What the user says a transaction was, sent from the phone. The message-derived columns stay untouched.
ALTER TABLE financial_transactions ADD COLUMN user_category VARCHAR(40) NULL;
ALTER TABLE financial_transactions ADD COLUMN user_label VARCHAR(100) NULL;
