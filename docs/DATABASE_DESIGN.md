# Database Design (MySQL, Flyway-managed)

Money: `DECIMAL(19,4)`. Timestamps: UTC `DATETIME(3)`. Single-user; no tenant column.

## Tables
| Table | Key columns / constraints |
|---|---|
| sync_devices | id, name, token_hash (SHA-256), created_at, last_seen_at, revoked_at |
| source_configurations | id, source_type, sender_or_package, enabled, parser_hint |
| parser_versions | id, parser_name, version, released_at |
| financial_accounts | id, name, institution, account_suffix, type, UNIQUE(institution, account_suffix) |
| raw_financial_events | id, device_id FK, source_event_id, source_type, source_application, source_sender, external_reference, event_timestamp, captured_timestamp, payload_hash, parser_status, parser_version, processing_status, created_at, updated_at. Ingestion ledger only: no message text (V2 dropped `raw_payload`). **UNIQUE(device_id, source_type, source_event_id)**; INDEX(payload_hash), INDEX(processing_status), INDEX(event_timestamp) |
| financial_transactions | id, raw_event_id FK UNIQUE, device_id, direction, amount, currency, account_last4, counterparty, counterparty_account_last4, payment_mode, reference_no, balance_after, transaction_date, bank_code, note, event_timestamp, created_at, **source_type, group_id, match_basis** (V4). One row per ingested bank message, extracted on the phone (V2). Phase 6 normalization will build `transactions` from these |
| outbox_events | id, aggregate_id (raw event id), event_type, schema_version, status, attempts, next_attempt_at, last_error, created_at. UNIQUE(aggregate_id, event_type); INDEX(status, next_attempt_at) |
| transaction_categories | id, name, parent_id, is_system |
| merchants | id, canonical_name UNIQUE, default_category_id FK, rule_enabled |
| transactions | id, account_id, transaction_reference, transaction_type, direction, amount, currency, merchant_id, merchant_name, description, transaction_timestamp, transaction_date, category_id, payment_method, confidence_score, review_required, reconciliation_status, manually_edited, transfer_pair_id, refund_of_transaction_id, created_at, updated_at. INDEX(account_id, transaction_date), INDEX(transaction_reference) |
| transaction_sources | id, transaction_id, raw_event_id, source_type, matching_confidence (HIGH/MEDIUM/LOW), created_at. UNIQUE(raw_event_id), UNIQUE(transaction_id, raw_event_id) |
| transaction_audit_history | id, transaction_id, action, before_json, after_json, actor, created_at (append-only) |
| sync_checkpoints | id, device_id, source_type, checkpoint_value, updated_at. UNIQUE(device_id, source_type) |
| sync_jobs | id, device_id, job_type, status, started_at, finished_at, counts, error |

Enums: transaction_type = INCOME, EXPENSE, INTERNAL_TRANSFER, REFUND, REVERSAL, CASH_WITHDRAWAL, CASH_DEPOSIT, INVESTMENT, UNKNOWN. Internal transfers and reversals are excluded from income/expense analytics.

## Rules
- Same real transaction, several channels: every channel keeps its own `financial_transactions` row, and rows describing one transaction share `group_id` (the id of the first row). A shared reference number (UPI RRN, UTR, IMPS ref, at least 9 characters) with the same direction and amount joins a group (`match_basis = REF`). Without a reference, a row from a different channel with the same direction and amount within 3 minutes and a compatible counterparty joins (`TIME`). Opposite directions are never merged: they are the two legs of a transfer.
- Phone parsers: `TransactionParser` (bank SMS, bank-app and payment-app notifications) and `EmailTransactionParser` (CSB alert e-mails, SBI NEFT and YONO transfer e-mails). Anything unrecognised stays on the phone.
- `manually_edited = true` transactions are never overwritten by reprocessing.
- Full message text is never stored on the server; the phone extracts the fields and uploads only those. `PAYLOAD_ENCRYPTION_KEY` and `PayloadCipher` are currently unused (kept for a possible later need).
- Ambiguous or LOW-confidence matches set `review_required`; they are never silently merged.
- Aiven free/developer MySQL is a single node with limited storage: retention/cleanup of operational tables (sync_jobs, processed outbox rows) is required.
