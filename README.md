# Finance Aggregator

Personal-use finance collector: Android app (Kotlin) captures bank notifications, SMS and Gmail alerts into a local Room queue, uploads to a Spring Boot backend (MySQL), which normalizes and deduplicates them into single transactions.

- Android is the first durable store; the backend never is the only copy.
- Kafka is an optional async sink, never a record store.
- Free-tier only. No paid services.

Docs: see [docs/](docs/). Progress: [docs/IMPLEMENTATION_PROGRESS.md](docs/IMPLEMENTATION_PROGRESS.md).

Web dashboard: served by the backend at `/` (sign in with `ADMIN_USERNAME` and `ADMIN_PASSWORD_HASH`). To try it with made-up data: `cd backend`, build, then run the jar with `--spring.profiles.active=demo` and sign in as `demo` / `demo-password`. Details in the progress doc.

Configuration: copy `.env.example` to `.env` and fill in values. Never commit `.env`.
