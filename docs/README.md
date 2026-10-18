# ledgerline

> A verified reference implementation of a double-entry ledger in **Java 21**, **Spring Boot 3**, and **PostgreSQL**. Demonstrates, through empirical benchmarks and automated chaos tests, how to maintain financial correctness under high concurrency, retries, and partial infrastructure failures.

---

## Core Guarantees

| Guarantee | Mechanism | Verification |
|---|---|---|
| **Atomic** | Multi-leg journal entries, idempotency keys, and outbox events commit together | Atomicity rollback test |
| **Zero-Sum** | $\sum \text{Debits} = \sum \text{Credits}$ enforced in domain and via DB deferred trigger | `ZeroSumBalanceInvariantIntegrationTest` |
| **Effectively-Once** | DB-first idempotency + at-least-once outbox with consumer deduplication | `IdempotencyDuplicateHammerTest` |
| **No Lost Updates** | Tested under 500-thread concurrent transfer storms across 3 locking models | `ConcurrentTransferStressTest` |

---

## Failure-Mode Verification Matrix

The centerpiece of `ledgerline` is proving that failure scenarios do not corrupt ledger state:

| Failure Scenario | Expected Behavior | Proving Test |
|---|---|---|
| Imbalanced journal entry | Rejected; transaction rolled back | `ZeroSumBalanceInvariantIntegrationTest` |
| Process crash mid-transfer | No orphaned or partial postings | Transaction atomicity test |
| 500 concurrent transfers on one account | Zero lost updates; balance matches sum of postings | `ConcurrentTransferStressTest` |
| Bidirectional transfer storm (A↔B) | Zero `40P01` database deadlocks | Deadlock isolation test |
| 500 duplicate requests with identical key | Exactly 1 ledger effect; 499 replayed responses | `IdempotencyDuplicateHammerTest` |
| Key reuse with mutated request body | `422 Unprocessable Entity` | Parameter tampering test |
| Redis node failure | Seamless fallback to PostgreSQL relational constraint | Cache-degradation test |
| Kafka broker down for 30s | Events safely buffered in outbox table and published on recovery | `TransactionalOutboxResilienceTest` |
| Database connection reset mid-transaction | Zero partial commits; zero ledger drift | `ToxiproxyConnectionResetTest` |
| Injected balance drift | Detected by audit worker; account frozen; alert event emitted | `ChaosDriftDetectionTest` |

---

## Benchmarked Locking Strategies (ADR-002)

Rather than assuming a locking strategy, `ledgerline` implements three models behind a common interface and benchmarks them under heavy single-account contention:
1. **Ordered Pessimistic Locking** (`SELECT ... FOR UPDATE` ordered by ascending UUID)
2. **Optimistic Locking with Exponential Full Jitter** (`@Version` with max 5 retries)
3. **Atomic Conditional Updates** (`UPDATE accounts SET balance = balance + :delta WHERE ...`)

*The default strategy is selected based on measured throughput and rejection rates on target hardware.*

---

## Documentation

* **[Engineering Delivery Tracker (48-Hour Plan)](tracker.md)**: 8-week breakdown, hourly allocations, and verification gates.
* **[Complete Feature Specification](feature.md)**: Detailed domain entities, database triggers, and outbox relay semantics.

---

## Tech Stack

* **Language & Runtime**: Java 21 (LTS)
* **Framework**: Spring Boot 3.3.x (Spring Data JPA, Spring Web, Spring Validation)
* **Datastores**: PostgreSQL 16 (Source of Truth), Redis 7 (Optional Fast Path)
* **Messaging**: Apache Kafka (At-least-once domain events)
* **Migrations**: Flyway
* **Testing & Chaos**: JUnit 5, AssertJ, Testcontainers, Toxiproxy, jqwik (Property-based tests), k6