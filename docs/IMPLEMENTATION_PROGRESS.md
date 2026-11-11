# Implementation Progress

## Phase status
- [x] **Phase 0** Environment inspection, design docs, repo skeleton (no code; no tests applicable)
- [~] Phase 1 Android foundation: notification capture verified on device by the user (real and test data stored)
- [~] Phase 2 Spring Boot ingestion: built, 27 tests pass on H2; deployed on Render and writing to Aiven MySQL (user-observed); duplicate and encrypted-at-rest checks on the live DB still to be confirmed
- [~] Phase 3 Android cloud sync: built, 58 Android unit tests pass; paired and uploading to the Render backend from the phone (user-observed in the DB)
- [~] Phase 4 SMS + historical scan: code built, 31 unit tests pass; on-device verification pending
- [~] Phase 5 Gmail: bank alert e-mails (CSB, SBI NEFT and YONO) are read every 30 minutes on the phone, parsed and uploaded like SMS; unit-tested, not yet run live. Login expires every 7 days while the Google app is in Testing mode.
- [~] Phase 6 Normalization + dedup: parsers for SMS, notifications and e-mail; cross-channel `group_id` (reference match, then 3-minute match); `GET /api/v1/transactions` returns one merged entry per transaction. No categories yet; values are used as received (no name or account aliasing). Own-account transfers are simply a debit plus a credit.
- [ ] Phase 7 Kafka + outbox
- [ ] Phase 8 Financial features
- [ ] Phase 9 Oracle deployment
- [~] Phase 10 Web dashboard: React app served by the backend at `/` (login, Overview, Insights, Transactions, Data health), admin-cookie login, categories and analytics computed on the server. Backend 50 tests green; UI checked in a browser against the `demo` profile (made-up data); not yet run on Render against the real data. The phone app also has new Overview / Transactions tabs with the same categories and grouping.
- [ ] Phase 11 AI

## Environment (inspected 2026-11-10)
JDK 25.0.3, Node 24.16.0, Git 2.54, Docker CLI present (daemon not running; not required yet). Maven/Gradle/Android SDK/adb/Ollama not installed. Maven and Gradle wrappers will be used.

## Phase 1 verification
- Executed: `gradlew assembleDebug testDebugUnitTest` -> BUILD SUCCESSFUL. NotificationFilterTest 8/8, CollectionHealthTest 4/4.
- NOT executed: Room instrumented tests and on-device behavior (notification capture, offline persistence, listener rebind). Acceptance criterion is unverified until tested on the phone.
- Toolchain: AGP 9.5.1, Gradle 9.9.0, Kotlin 2.4.21, KSP 2.3.13, Room 2.8.5, Compose BOM 2026.09.00, compileSdk 37, targetSdk 35, minSdk 29, JDK 25.
- Build note: the corporate proxy stalls large downloads; build off VPN, or pre-fetch with BITS.

## Phase 4 verification
- Executed: `gradlew assembleDebug testDebugUnitTest` -> BUILD SUCCESSFUL. NotificationFilterTest 8, SmsClassifierTest 12, CollectionHealthTest 4, SmsHealthTest 7: all pass.
- Fixtures use the real CSB, Indian Bank and SBI formats with sanitized names, references and balances.
- NOT executed: live SMS receive, history scan against a real inbox, scan resume after kill, Room instrumented tests (none written yet for SMS dedupe/checkpoint).
- Known limits: sender allowlist defaults are CSBBNK, INDBNK, SBIUPI, SBIPSG; other SBI headers (e.g. SBIINB) must be added in Sources. Parsing of amounts/accounts is Phase 6; events are stored raw (UNPARSED).
- Gap: Google Pay does not notify for outgoing payments; outgoing UPI debits are covered by bank SMS (and later Gmail/CSV).

## Phase 2 verification
- Executed: `mvn test` -> 23 tests, 0 failures (PayloadCipherTest 5, IngestionFlowTest 15, DatabaseOutageTest 2, RateLimitTest 1). All on H2 in MySQL mode.
- Covered: pairing-code registration, token stored as hash, auth required, idempotent ingest (ACCEPTED then DUPLICATE, same server id), payload encrypted at rest, outbox row written with the event, per-item batch rejection, 100-item cap, no payload echo in errors, DB outage -> 503 and no ack, rate limit -> 429, device revoke, public health endpoint.
- NOT executed: any connection to Aiven MySQL. From this laptop the TLS handshake is reset/times out (corporate VPN/proxy path). The three ingestion tables were created manually from V1__ingestion_schema.sql; Flyway is set to baseline at version 1.
- Pending acceptance: start the app on a network that reaches Aiven, register a device, ingest an event twice, and read the encrypted row back from MySQL.
- Not built yet from the original API list: GET /events/pending, POST /events/{id}/reprocess, /sync/*, health/sources, health/sync.
- Maven wrapper could not download Maven (slow network); local Maven 3.9.16 is used.

## Phase 3 verification
- Executed: `gradlew assembleDebug testDebugUnitTest` -> BUILD SUCCESSFUL, 58 tests, 0 failures (new: UploadRunnerTest 12, BackoffAndApiTest 9, CloudHealthTest 6).
- Covered by fakes: unpaired -> nothing sent; ACCEPTED/DUPLICATE -> SYNCED only after ack; backend outage -> events stay queued (RETRY) with 15-30s first backoff, doubling, capped at 6h, jittered; recovery uploads the same events; 401 -> events return to PENDING, none lost; REJECTED -> NEEDS_REVIEW; missing ack -> retry; leftover UPLOADING reset after a kill; 4xx -> FAILED_PERMANENTLY (kept, retryable by button); 50-event batches, oldest first; request/response JSON matches the backend contract.
- NOT executed: any real network call, WorkManager scheduling on a device, Keystore token storage, the pairing screen, behavior with no connectivity/airplane mode, reboot recovery. Real-device acceptance needs a reachable HTTPS backend.
- Design notes: upload is triggered after each captured notification/SMS and after the history scan (WorkManager, network-constrained, not exact-time); a 15-minute periodic worker is a safety net. Device token is encrypted with an Android Keystore key. Only https:// backend URLs are accepted. The pairing code is never stored.

## Render deployment (2026-11-10)
- Free web service `finance-backend-9hjv` (Docker, 0.1 CPU/512 MB, sleeps after 15 min idle, cold start about 90 s), deployed manually from `master`.
- Startup log verified: truststore import, TLS to Aiven MySQL, Flyway baseline at version 1, `Started FinanceAggregatorApplication`.
- Failures met and fixed: a missing `PAYLOAD_ENCRYPTION_KEY` was used as the literal text `${...}` (now blank and rejected at startup); nested config validation did not run without `@Valid` (fixed, 4 tests added).
- First phone sync timed out (30 s read timeout against per-event transactions on a 0.1 CPU instance). Fixed with 10-event batches and a 120 s read timeout; the user then saw rows arriving in MySQL.
- RISK: the encryption key and pairing code in use were placeholders (all-zero key). Rows stored so far are protected only by that weak key and need re-encryption or deletion before real data is relied on.

## Web dashboard (2026-11-12)
- Served by the same Spring Boot service (single Render service, no extra cost). `backend/dashboard` (Vite, React, TypeScript, Recharts) builds into `backend/src/main/resources/static`; the Dockerfile has a Node build stage, so nothing generated is committed.
- Login: `POST /api/admin/login` with `ADMIN_USERNAME` and `ADMIN_PASSWORD_HASH` (bcrypt) from the environment. Session is a random token in an HttpOnly, SameSite=Strict, Secure cookie, kept in memory on the server (sessions end on restart or after 12 hours). Login is limited to 5 tries per minute per IP, same as device registration. Without these two variables the login answers 503 and the dashboard stays closed. Phone tokens cannot open the dashboard API and the dashboard cookie cannot call phone endpoints.
- Generate the hash: `cd backend; mvn -q -DskipTests package` then `java "-Dloader.main=com.personal.finance.tools.HashPassword" -cp target/finance-aggregator-backend-0.1.0-SNAPSHOT.jar org.springframework.boot.loader.launch.PropertiesLauncher` (typed at a hidden prompt; at least 12 characters).
- API (admin cookie): `GET /api/admin/summary?from&to`, `/api/admin/transactions` (search, direction, category, source, amount range, sort, paging), `/api/admin/health`.
- Categories are display-only labels from keyword rules on the payee and note (no stored renaming); unknown payees stay "Other". Days follow the date in the message, otherwise India time.
- Try it without real data: `java -jar target/finance-aggregator-backend-0.1.0-SNAPSHOT.jar --spring.profiles.active=demo --server.port=8099`, open http://localhost:8099, user `demo`, password `demo-password` (in-memory database with made-up transactions; never use in production).
- Frontend dev: `cd backend/dashboard; npm install; npm run dev` (proxies `/api` to localhost:8080).
- Verified: backend 50 tests; `npm run build` clean; every page loaded in a browser against the demo profile with no console errors. NOT verified: behaviour on Render with real data and a real password.

## Decisions
- Temporary analysis export: `message_staging` table (V3, drop when done) receives full SMS (alphanumeric senders only, OTP digits masked) and Gmail mail (finance-related query, job portals/YouTube/Google notifications filtered, Google Pay and Play receipts kept, body capped at 8000 chars, newest first, resumable). Gmail is read on the phone through the Google Authorization API (`gmail.readonly`, Android OAuth client by package + SHA-1); no secrets in the app. Verified by unit tests only (backend 32, Android 85); not yet run on the phone or against Google.
- Messages are parsed on the phone; only extracted fields (amount, direction, account last 4, counterparty, reference, balance, date, note) reach the server. Backend V2 migration wipes pre-parsing rows, drops `raw_payload` and adds `financial_transactions`. Unrecognised messages stay on the phone as NEEDS_REVIEW. Verified: 29 backend tests, 68 Android tests (9 parser tests on the real CSB, Indian Bank and SBI formats). NOT yet verified on the phone or live DB.
- Existing running Aiven MySQL is the source of truth (free MySQL tier is retired for new services).
- Outbox-poller drives normalization; Kafka is an optional sink added in Phase 7.
- `raw_payload` is encrypted at the application layer.
- Docker/Testcontainers deferred; decided in Phase 2.

## Known issues / risks
- No Android SDK on this machine: Android code cannot be compiled or tested here until the SDK is available. Phase 1 is not complete until it builds and runs.
- Gmail "Testing" refresh tokens expire in 7 days.
- Aiven Kafka free tier powers off when idle.

## Next actions
1. Replace the placeholder encryption key and pairing code, and decide what to do with rows already stored under the old key.
2. Confirm on the live DB that payloads start with `v1:` and each event has an outbox row.
3. Phase 6 (parsers) needs redacted real message examples; wait for approval before starting.
