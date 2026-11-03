# Changelog

## [0.1.0] — 2026-10-20

First complete release of the v0.1 scope ([documentation](docs/README.md)).

### Added
- **Double-entry ledger:** accounts, journal entries, postings, `BIGINT` minor units, one currency per entry.
- **Zero-sum enforced twice:** in the domain (`JournalEntry.create`) and by a `DEFERRABLE INITIALLY DEFERRED` PostgreSQL constraint trigger. Postings and journal entries are immutable (triggers).
- **Three concurrency strategies** behind one interface, all proven by stress and deadlock tests:
  - ordered pessimistic locking (**default**)
  - optimistic + full-jitter retry
  - atomic conditional update

  The default was chosen from measured benchmark data (ADR-002).
- **DB-first idempotency:** `Idempotency-Key` is required, scoped to the authenticated client. Concurrent duplicates wait and replay (`X-Cache-Lookup: HIT-IDEMPOTENT`). A reused key with a different payload returns 422.
- **Transactional outbox:** `TransferCompleted` / `LedgerDriftDetected` are written in the ledger transaction and published to Kafka at-least-once with `event_id` for consumer dedup.
- **Self-audit:** incremental checkpoints, one `REPEATABLE READ` snapshot per account. A drifted account is frozen (`423`) and alerted once.
- **Retention:** published outbox rows and idempotency keys older than 7 days are purged; ledger history never is.
- **Observability:**
  - Prometheus metrics (`/actuator/prometheus`)
  - MDC log correlation (`trace_id`, `idempotency_key`, accounts)
  - `X-Trace-Id`
  - health probes
- **Chaos and load proof:**
  - Kafka outage (30 s)
  - Toxiproxy connection resets
  - injected drift
  - k6 steady state: p99 25–28 ms at 200 req/s before auth; target p99 < 50 ms (latest authenticated run 74 ms on a slowed host, re-measurement pending)
- **One-command run:** `docker compose up --build` starts PostgreSQL, Kafka and the app, then a walkthrough that checks each guarantee.
- **API-key authentication** (Spring Security, stateless): only a SHA-256 of each key is stored, roles are CLIENT/ADMIN, revocation is immediate, and there's a bootstrap admin from configuration.
- **Owner-only accounts:** a client can only read or move its own accounts; anything else is a 404, and idempotency keys are scoped per client.
- **Multi-currency transfers** (`POST /api/v1/fx-transfers`):
  - zero-sum enforced per currency (domain and database)
  - conversion through per-currency FX clearing accounts at a client-supplied rate
  - exact minor-unit maths, half-even rounding, every conversion recorded
- **Account statement** (`GET /api/v1/accounts/{id}/postings`): newest first, keyset pagination, and the balance after every line from one consistent snapshot.
- **Admin repair and unfreeze:**
  - freeze, rebuild the balance from postings, or book a missing posting against an adjustment account
  - unfreeze only when the account audits clean
  - every action audited with who and why, and published as an event
- **Docs:**
  - 13 ADRs
  - architecture diagrams
  - flows
  - learning notes
  - benchmark data with hardware specs
  - bug write-ups

### Deferred (see architecture roadmap)
Redis idempotency fast path, CSV settlement matching, hot-account striping, OpenTelemetry tracing, Debezium CDC, per-client rate limiting, key rotation endpoint.
