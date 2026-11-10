# Implementation Progress

## Phase status
- [x] **Phase 0** Environment inspection, design docs, repo skeleton (no code; no tests applicable)
- [~] Phase 1 Android foundation: notification capture verified on device by the user (real and test data stored)
- [~] Phase 2 Spring Boot ingestion: built, 23 tests pass on H2; schema created by hand on Aiven MySQL; live end-to-end check pending (needs a network that can reach Aiven)
- [~] Phase 3 Android cloud sync: built, 58 Android unit tests pass; needs live test against a reachable HTTPS backend
- [~] Phase 4 SMS + historical scan: code built, 31 unit tests pass; on-device verification pending
- [ ] Phase 5 Gmail
- [ ] Phase 6 Normalization + dedup
- [ ] Phase 7 Kafka + outbox
- [ ] Phase 8 Financial features
- [ ] Phase 9 Oracle deployment
- [ ] Phase 10 React dashboard
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

## Decisions
- Existing running Aiven MySQL is the source of truth (free MySQL tier is retired for new services).
- Outbox-poller drives normalization; Kafka is an optional sink added in Phase 7.
- `raw_payload` is encrypted at the application layer.
- Docker/Testcontainers deferred; decided in Phase 2.

## Known issues / risks
- No Android SDK on this machine: Android code cannot be compiled or tested here until the SDK is available. Phase 1 is not complete until it builds and runs.
- Gmail "Testing" refresh tokens expire in 7 days.
- Aiven Kafka free tier powers off when idle.

## Next actions
1. Install the debug APK on the phone and run the Phase 1 acceptance test.
2. Fix issues found, then start Phase 2 (Spring Boot ingestion) after approval.
