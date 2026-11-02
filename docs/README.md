# ledgerline — Documentation

> A reference double-entry ledger in **Java 21**, **Spring Boot 4** and **PostgreSQL 16** that demonstrates, with tests and measurements, how to keep financial data correct under concurrency, retries and partial failure.
> **v0.1.0: all eight weeks delivered.** Run everything with `docker compose up --build` (see the [root README](../README.md)).
> Status legend: ✅ implemented and proven by a passing test · 🗓️ deferred.

---

## Where to read what

| Document | Read it for |
|---|---|
| [architecture.md](architecture.md) | Diagrams: system context, layers, a transfer end to end, deployment |
| [tracker.md](tracker.md) | The 8-week plan, what is done, and the proof for each week |
| [feature.md](feature.md) | The full specification (what the system must do) |
| [decision.md](decision.md) | Architecture Decision Records: *why* each choice was made (ADR-001 … 013) |
| [flow.md](flow.md) | *How* things move: request lifecycle, idempotency, strategies, outbox relay and audit decision flows, error map, test map |
| [learning.md](learning.md) | The concepts behind the code: double-entry, deadlocks, locking strategies, idempotency, outbox, reconciliation, chaos |
| [bug-writeups.md](bug-writeups.md) | Real bugs from building this: symptom → root cause → fix → lesson |
| [benchmarks/](benchmarks/) | Measured data with hardware specs: locking strategies (4 runs), k6 HTTP steady state (2 runs) |

---

## Core Guarantees

| Guarantee | Mechanism | Status | Verification |
|---|---|---|---|
| **Atomic** | Postings, balance updates, the idempotency record and the outbox event commit in one transaction | ✅ | `IdempotencyDuplicateHammerTest`, `OutboxAtomicityTest`, `ToxiproxyConnectionResetTest` |
| **Zero-Sum** | Σ debits = Σ credits, enforced in the domain and by a deferred DB constraint trigger | ✅ | `ZeroSumBalanceInvariantIntegrationTest` |
| **Effectively-Once** | DB-first idempotency + at-least-once outbox with `event_id` for consumer dedup | ✅ | `IdempotencyDuplicateHammerTest`, `TransactionalOutboxResilienceTest` |
| **Self-Verifying** | Incremental audit; drifted accounts frozen and alerted | ✅ | `ChaosDriftDetectionTest` |
| **Observable** | Prometheus business metrics; correlated logs; k6 baseline (p99 target < 50 ms; latest authenticated run 74 ms on a slowed host, re-measurement pending) | ✅ / ⚠️ | `ObservabilityTest`, k6 |
| **Secure by default** | API keys (hash-only), roles, owner-only accounts | ✅ | `SecurityApiTest` |
| **Multi-currency** | Zero-sum per currency; FX via clearing accounts | ✅ | `FxTransferApiTest` |
| **No Lost Updates** | 500 concurrent transfers, three locking strategies | ✅ | `ConcurrentTransferStressTest` |

---

## Failure-Mode Verification Matrix

| Failure Scenario | Expected Behavior | Proving Test | Status |
|---|---|---|---|
| Imbalanced journal entry (API) | Rejected; nothing written | `ZeroSumBalanceInvariantIntegrationTest` | ✅ |
| Imbalanced postings via raw SQL | Rejected by the deferred trigger at COMMIT | `ZeroSumBalanceInvariantIntegrationTest` | ✅ |
| `UPDATE`/`DELETE` on ledger history | Rejected by immutability triggers | `ZeroSumBalanceInvariantIntegrationTest` | ✅ |
| 500 concurrent transfers on one account | Zero lost updates; balance = sum of postings | `ConcurrentTransferStressTest` | ✅ |
| 500 withdrawals against funds for 100 | Exactly 100 succeed; never negative | `ConcurrentTransferStressTest` | ✅ |
| Bidirectional transfer storm (A↔B) | Zero `40P01` deadlocks | `BidirectionalDeadlockTest` | ✅ |
| 500 duplicate requests with one key | Exactly 1 ledger effect; 499 replayed responses | `IdempotencyDuplicateHammerTest` | ✅ |
| Key reuse with a mutated body | `422 Unprocessable Entity` | `IdempotencyDuplicateHammerTest`, `TransferApiIdempotencyTest` | ✅ |
| Transfer fails, client retries the same key | Retry is a fresh attempt (failure left no record) | `IdempotencyDuplicateHammerTest` | ✅ |
| Connection lost mid-transfer, incl. lost COMMIT reply | No partial postings; same-key retry replays or executes, never doubles | `ToxiproxyConnectionResetTest` | ✅ |
| Redis node failure | Fall back to the DB path | Redis fast path deferred (cut line) | 🗓️ |
| Kafka broker down for 30s | Transfers keep committing; events buffered in the outbox, all published on recovery | `TransactionalOutboxResilienceTest` | ✅ |
| Transfer rolls back / replayed | No event written | `OutboxAtomicityTest` | ✅ |
| DB connection reset under load | Zero partial commits; zero drift (audited) | `ToxiproxyConnectionResetTest` | ✅ |
| Injected balance drift | Exact delta detected; account frozen; one alert event | `ChaosDriftDetectionTest` | ✅ |
| Audit running during heavy load | Zero false alarms | `ChaosDriftDetectionTest` | ✅ |
| Sustained HTTP load (200 req/s, 5% retries) | 0 failures, p99 < 50 ms, metrics = DB, no drift | k6 + SQL checks | ✅ |
| Old keys/events accumulate | Purged after 7 days; ledger history never | `RetentionJobTest` | ✅ |
| Client names another client's account | 404; nothing moves | `SecurityApiTest` | ✅ |
| Cross-currency netting via raw SQL | Rejected by the per-currency trigger | `ZeroSumBalanceInvariantIntegrationTest` | ✅ |
| Unfreeze while still drifted | 409; stays frozen | `AccountAdminApiTest` | ✅ |

---

## Benchmarked Locking Strategies (ADR-002)

Three models behind one `ConcurrencyStrategy` interface, measured under contention ([raw data](benchmarks/locking-strategies.md)):

| Strategy | Hot account throughput | Rejected | p99 |
|---|---|---|---|
| **A. Ordered pessimistic** (`SELECT ... FOR UPDATE` in canonical order): **default** | ~101 tx/s | 0% | 0.7–0.9 s |
| B. Optimistic + full-jitter retry (`@Version`, max 5 retries) | ~64 tx/s | 60–63% | 0.4–0.5 s |
| C. Atomic conditional update (`UPDATE ... SET balance = balance + :delta WHERE <guard>`) | ~133 tx/s | 0% | 1.7–3.1 s |

Latest runs (3–4, with the outbox in every transaction). A was chosen for its tail latency and because it keeps all balance rules in `Account.applyPosting`. At low contention C is ~11% faster, a cost accepted in ADR-002; C is the documented upgrade path.

---

## Tech Stack

* **Language & Runtime**: Java 21 (LTS)
* **Framework**: Spring Boot 4.0.x (Spring Data JPA, Spring MVC, Validation)
* **Datastore**: PostgreSQL 16 (source of truth), Flyway migrations
* **Messaging**: Apache Kafka via transactional outbox (at-least-once)
* **Testing**: JUnit 5, AssertJ, Awaitility, Testcontainers 2 (PostgreSQL, Kafka, Toxiproxy), ArchUnit, Checkstyle, k6
* **Observability**: Actuator, Micrometer + Prometheus, MDC
* **Planned**: Redis fast path (optional), CSV settlement matching (optional), OpenTelemetry tracing (stretch)
