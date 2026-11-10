# API Specification (v1)

Base: `/api/v1`. HTTPS only. Device endpoints use `Authorization: Bearer <device-token>`; dashboard endpoints use a separate admin credential.

## Endpoints
- Devices: `POST /devices/register` (pairing code), `POST /devices/heartbeat`, `POST /devices/revoke`
- Events: `POST /events/ingest`, `POST /events/batch`, `GET /events/pending`, `GET /events/{eventId}`, `POST /events/{eventId}/reprocess`
- Transactions: `GET /transactions`, `GET /transactions/{id}`, `POST /transactions/manual`, `PUT /transactions/{id}`, `GET /transactions/summary`, `GET /transactions/search`, `POST /transactions/{id}/categorize`
- Accounts: `GET /accounts`, `POST /accounts`, `PUT /accounts/{id}`
- Sync: `POST /sync/start`, `GET /sync/status`, `GET /sync/history`, `POST /sync/reconcile`
- Analytics: `GET /analytics/monthly`, `/categories`, `/income-expense`, `/merchants`
- Health: `GET /actuator/health`, `GET /health/sources`, `GET /health/sync`

List endpoints are paginated (`page`, `size` max 100) with filters.

## Ingestion contract
Request (`/events/batch`, max 100 items):
```json
{ "events": [ {
  "localEventId": "uuid", "sourceType": "NOTIFICATION|SMS|GMAIL|MANUAL",
  "sourceApplication": "com.google.android.apps.nbu.paisa.user", "sourceSender": null,
  "sourceEventId": "stable-id", "eventTimestamp": "2026-11-10T08:15:00Z",
  "capturedTimestamp": "2026-11-10T08:15:02Z", "payload": "relevant text only",
  "parserStatus": "PARSED|NEEDS_REVIEW", "parserVersion": "1" } ] }
```
Response:
```json
{ "results": [ { "localEventId": "uuid", "status": "ACCEPTED|DUPLICATE|REJECTED",
  "serverEventId": 123, "reason": null } ] }
```
- Android marks SYNCED only for ACCEPTED or DUPLICATE. REJECTED -> NEEDS_REVIEW / FAILED_PERMANENTLY.
- `GET /events/{id}` returns `processingStatus` (RECEIVED, PROCESSING, NORMALIZED, NEEDS_REVIEW, FAILED) separately from the ack.

## Errors
RFC 7807 `application/problem+json`. 400 validation, 401 bad/revoked token, 429 rate limit, 503 + `Retry-After` for DB unavailability (event NOT stored). Raw payloads and tokens are never logged.
