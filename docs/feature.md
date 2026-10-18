# ledgerline — Feature Specification (v0.1)

> `ledgerline` is a reference implementation of a double-entry ledger service in Java 21 / Spring Boot 3 / PostgreSQL. Its goal is to **demonstrate, with tests and measurements, how to keep a ledger correct under concurrency, retries and partial failures.**
>
> It is not a production payments system. Every guarantee below is tied to a test that proves it (see the failure-mode table in section 9).

---

## 0. Scope

### In scope for v0.1
- Double-entry journal with a zero-sum invariant enforced in code **and** in the database
- Transfers between accounts (2 or more legs), single currency per journal entry
- Concurrency control with three benchmarked strategies and one default
- Idempotent API (database is the source of truth, Redis is an optional fast path)
- Transactional outbox with a relay to Kafka
- Self-audit / reconciliation job with checkpoints, plus matching against one simple CSV settlement file
- Chaos tests: Kafka outage and one Toxiproxy scenario against PostgreSQL
- Basic metrics (Micrometer/Prometheus), MDC log correlation, k6 baseline benchmark

### Explicitly out of scope (deferred, see section 10)
Debezium CDC, MT940 / CAMT.053 parsing, multi-currency FX clearing, Grafana dashboards, 2,000 TPS spike testing, multi-tenant admin features.

---

## 1. Guarantee Language (what we claim, precisely)

| Claim | What it actually means |
|---|---|
| **Atomic** | A journal entry (all its postings, its idempotency record and its outbox event) commits entirely or not at all. |
| **Zero-sum** | For every committed journal entry, the sum of debits equals the sum of credits. Enforced by domain validation and a deferred database constraint trigger. |
| **Effectively-once** | A client retrying with the same `Idempotency-Key` and the same payload causes at most one ledger effect. Delivery to Kafka is **at-least-once**; consumers deduplicate using `event_id`. |
| **No lost updates** | Under concurrent transfers, final balances equal the sum of committed postings. Some requests may be rejected under contention; none may be silently lost or double-applied. |

> We never use the term "exactly-once" for delivery. It is an overclaim.

---

## 2. Accounting Model

### 2.1 Entities
- `accounts` — id (UUID), type, currency, `disallow_negative_balance` flag, `balance` (cached projection), `version`
- `journal_entries` — id, idempotency reference, description, created_at
- `postings` — id, journal_entry_id, account_id, direction (`DEBIT` / `CREDIT`), amount (BIGINT minor units), currency, created_at
- `outbox_events`, `idempotency_records`, `reconciliation_checkpoints` (see later sections)

### 2.2 Rules
- Every journal entry has at least 2 postings; all postings in an entry share one currency.
- `SUM(debits) = SUM(credits)` per journal entry. Rejected with a domain exception, and the DB transaction rolls back.
- **Immutability:** `postings` and `journal_entries` are append-only. `UPDATE`/`DELETE` are blocked by DB triggers and by revoking privileges from the application role. Corrections are made only by compensating (reversal) entries.
- **Balance is a projection.** `accounts.balance` is a cached value derived from postings. It is the only mutable financial column, and the audit job (section 6) verifies it.

### 2.3 Account types
`ASSET`, `LIABILITY`, `EQUITY`, `REVENUE`, `EXPENSE`, each with a normal balance side. Optional `disallow_negative_balance` is checked inside the same transaction that applies the postings.

### 2.4 Money
- Stored as `BIGINT` integer minor units (no floats, no `NUMERIC` ambiguity).
- ISO-4217 currency code on every account and posting.
- Cross-currency postings in one entry are hard-rejected. FX is out of scope for v0.1.

---

## 3. Concurrency Control

### 3.1 Strategies implemented behind one interface
| Strategy | Mechanism | Expected profile |
|---|---|---|
| **A. Ordered pessimistic locks** (candidate default) | `SELECT ... FOR UPDATE` on all involved accounts, in ascending UUID order, then apply postings | Correct and simple, and deadlock-free thanks to ordering. Throughput on one hot account is limited by lock hold time. |
| **B. Optimistic locking** | `@Version` check, retry with exponential backoff + full jitter, max 5 retries | Good under low contention. Degrades and rejects requests under heavy single-account contention. |
| **C. Atomic conditional update** | `UPDATE accounts SET balance = balance + :delta WHERE id = :id AND (balance + :delta >= 0 OR NOT disallow_negative_balance)` | Typically the highest throughput on a hot row. Needs care to keep multi-leg atomicity. |

The **default strategy is chosen from benchmark data**, not assumed in advance. The comparison and results are published as ADR-002.

### 3.2 Deadlock prevention
- Any strategy that takes multiple row locks acquires them in ascending account ID order.
- Test: bidirectional transfers (A→B and B→A) at high volume must produce zero `40P01` deadlock errors.

### 3.3 Backpressure
- Retry exhaustion returns a clear, retryable error (`409`/`503` with `Retry-After`), never a partial commit.

### 3.4 Hot-account striping (stretch goal)
- Only for **credit-only aggregate accounts** (e.g. platform fee revenue) that do not need balance checks.
- Split into N sub-accounts, pick one randomly per transfer, aggregate in a read model.
- Not valid for accounts with `disallow_negative_balance`.

---

## 4. Idempotency

### 4.1 Design (database-first)
1. Request carries an `Idempotency-Key` header, scoped by `(client_id, idempotency_key)`.
2. The service computes `SHA-256(canonical request body)`.
3. In the **same DB transaction** as the transfer, it inserts into `idempotency_records` with a unique constraint on `(client_id, idempotency_key)`.
   - Insert succeeds → this request is the owner. Execute the transfer, store the status and response body, commit.
   - Unique violation → look up the existing record:
     - Same hash, completed → replay the stored response with header `X-Cache-Lookup: HIT-IDEMPOTENT`.
     - Same hash, still in progress → `409 Conflict` with `Retry-After`.
     - Different hash → `422 Unprocessable Entity` (key reused with different parameters).

### 4.2 Redis fast path (optional)
- `SET key hash NX EX 60` absorbs duplicate stampedes before they reach Postgres.
- Redis is **only an optimization**. Correctness never depends on it. If Redis is down or the lease expires mid-request, the DB constraint still guarantees a single ledger effect.
- Fallback when Redis is unavailable: go straight to the DB path (circuit breaker optional).

### 4.3 Required behavior to document in ADR-004
- What happens if the process crashes after the transfer commits but before the response is sent (the retry gets the stored response).
- What happens if the Redis lease expires while the first request is still running.

---

## 5. Transactional Outbox

- Domain events (`TransferCompleted`, `AccountFrozen`) are inserted into `outbox_events` in the same transaction as the postings.
- A relay worker polls with `SELECT ... FOR UPDATE SKIP LOCKED`, publishes to Kafka, then marks the row as published.
- Delivery is **at-least-once**. Each event carries `event_id`, a monotonic sequence number and the source journal entry ID so consumers can deduplicate.
- Topics (v0.1): `ledger.transfers.v1`, `ledger.accounts.v1`.
- Failure behavior: if Kafka is down, events stay in the table and are published after recovery, with no events lost.

---

## 6. Audit & Reconciliation

### 6.1 Internal self-audit
- Periodic job verifies `account.balance = checkpoint_balance + SUM(postings since checkpoint)`.
- `reconciliation_checkpoints` stores a verified balance and the last posting ID, so the job never rescans full history.
- On mismatch: freeze the account, emit a `LEDGER_DRIFT_DETECTED` event through the outbox, and log structured details (account, expected, actual, delta).

### 6.2 External settlement matching (simple version)
- Ingest one CSV settlement file format.
- Match settlement rows against internal postings by reference and amount.
- Bucket results as **Matched**, **Unmatched**, **Amount mismatch**.
- Timing differences and 3-way bank matching are deferred.

---

## 7. Fault Tolerance & Chaos Testing

Scope for v0.1 is deliberately small, with two scenarios that each verify an invariant:

1. **Kafka outage:** broker stopped for 30s during transfers. Verify that all events are published after recovery and none are lost.
2. **PostgreSQL connection reset (Toxiproxy):** TCP reset mid-transaction under load. Verify no partial commits and no balance drift, and that the audit job reports zero discrepancies afterwards.

Optional stretch: latency injection on Postgres, Resilience4j circuit breaker around Redis.

---

## 8. Observability

- Micrometer + Prometheus metrics:
  - `ledger.transfer.duration` (p50/p95/p99)
  - `ledger.concurrency.retries`
  - `ledger.idempotency.hits` / `ledger.idempotency.misses`
  - `ledger.audit.discrepancies` (should always be 0)
- MDC log fields: `trace_id`, `idempotency_key`, `source_account_id`, `target_account_id`.
- OpenTelemetry tracing is a stretch goal.

---

## 9. Failure-Mode Table (README centerpiece)

| Failure | Expected behavior | Proving test |
|---|---|---|
| Imbalanced journal entry | Rejected, nothing committed | `ZeroSumBalanceInvariantIntegrationTest` |
| Crash mid multi-leg transfer | No partial postings | Atomicity test |
| 500 concurrent transfers on a shared account | No lost updates, no drift | `ConcurrentTransferStressTest` |
| Bidirectional transfer storm | Zero deadlocks | Deadlock test |
| 500 duplicate requests with one key | Exactly one ledger effect, others replay | `IdempotencyDuplicateHammerTest` |
| Same key, different payload | `422` | Idempotency mismatch test |
| Redis down | Falls back to DB path, still correct | Redis-failure test |
| Kafka down 30s | Events buffered and published after recovery | `TransactionalOutboxResilienceTest` |
| DB connection reset under load | No partial commit, no drift | `ToxiproxyConnectionResetTest` |
| Injected balance drift | Detected, account frozen, alert event emitted | `ChaosDriftDetectionTest` |

---

## 10. Deferred / Future Work
Debezium CDC option, MT940/CAMT.053 ingestion, 3-way bank matching, FX clearing accounts, Grafana dashboards, 2,000 TPS spike scenario, multi-tenant management, OpenTelemetry tracing, hot-account striping (if not completed as a stretch goal).