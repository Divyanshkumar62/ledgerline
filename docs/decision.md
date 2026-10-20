# ledgerline — Architecture Decision Records (ADRs)

This document records the foundational architectural decisions made in `ledgerline`, outlining the problem context, chosen solution, alternatives considered, and engineering tradeoffs. It is the single source of truth for ADR numbers.

| ADR | Decision | Status | Proven by |
|---|---|---|---|
| [001](#adr-001-double-entry-bookkeeping-model-vs-single-entry-balances) | Double-entry bookkeeping, BIGINT minor units | Accepted | `JournalEntryTest` |
| [002](#adr-002-concurrency-strategy-and-canonical-lock-ordering) | Default concurrency strategy = ordered pessimistic locking; canonical lock order | Accepted (measured) | `ConcurrentTransferStressTest`, `BidirectionalDeadlockTest`, benchmark |
| [003](#adr-003-balance-as-a-cached-projection) | Balance is a cached projection of postings | Accepted (audit job: Week 6) | `assertBalanceMatchesPostings` in every integration test |
| [004](#adr-004-defense-in-depth-zero-sum-invariant-enforcement) | Zero-sum enforced in domain AND deferred DB trigger | Accepted | `ZeroSumBalanceInvariantIntegrationTest` |
| [005](#adr-005-hexagonal-clean-layering-via-domain-ports) | Hexagonal layering via domain ports | Accepted | `LayerArchitectureTest` |
| [006](#adr-006-db-first-idempotency-in-the-transfer-transaction) | DB-first idempotency in the transfer transaction | Accepted | `IdempotencyDuplicateHammerTest`, `TransferApiIdempotencyTest` |
| 007 | Transactional outbox, at-least-once delivery | Planned (Week 5) | `TransactionalOutboxResilienceTest` |

---

## ADR-001: Double-Entry Bookkeeping Model vs. Single-Entry Balances

* **Status**: Accepted
* **Date**: 2026-10-18
* **Deciders**: Principal Backend Engineer, Product Analyst

### Context
In single-entry systems, money movements are represented by updating balance columns on accounts (`UPDATE accounts SET balance = balance - 100 WHERE id = 1`). Under partial network failures, concurrency race conditions, or unhandled exceptions, balances frequently drift from reality, and historical audits cannot reconstruct where money went or why it disappeared.

### Decision
Implement a strict **double-entry bookkeeping** ledger:
1. Every financial transaction is represented by a `JournalEntry` containing two or more `Posting` legs.
2. Every journal entry must satisfy the **zero-sum invariant**:
   $$\sum \text{Debits} - \sum \text{Credits} = 0$$
3. Amounts are strictly stored as `BIGINT` integer minor units (e.g. cents, paise) paired with an ISO-4217 3-letter currency code (`Money` value object). Floating-point (`FLOAT`, `DOUBLE`) and fractional types are forbidden.

### Consequences & Tradeoffs
* **Pros**:
  * Guarantees that money can neither be created out of thin air nor destroyed.
  * Every transaction provides an indisputable, immutable audit trail.
* **Cons**:
  * Higher storage volume (at least 2 postings per transfer).
  * Requires explicit multi-leg transaction orchestration.

---

## ADR-002: Concurrency Strategy and Canonical Lock Ordering

* **Status**: Accepted (default chosen from measured data on 2026-10-20)
* **Date**: 2026-10-18, revised 2026-10-20
* **Deciders**: Divyansh Kumar

### Context
Two concurrent transfers that read the same balance and both write back their result lose one update. Transfers that lock rows in different orders (A→B while B→A) deadlock (`40P01`). We built three strategies behind `ConcurrencyStrategy` and measured them instead of assuming a winner:

| Strategy | Mechanism |
|---|---|
| **A. Ordered pessimistic** | `SELECT ... FOR UPDATE` on all accounts in one statement ordered by id, then apply postings in Java |
| **B. Optimistic + full jitter** | Read without locks; `@Version` makes the write fail on conflict; retry the whole transaction up to 5 times with full-jitter backoff |
| **C. Atomic conditional update** | One `UPDATE accounts SET balance = balance + :delta WHERE id = :id AND <funds guard>` per account, in lock order |

All three pass the same proof tests: `ConcurrentTransferStressTest` (no lost updates, no overspend) and `BidirectionalDeadlockTest` (zero `40P01`).

### Measurements ([full data](benchmarks/week3-locking-strategies.md), two runs, 32 workers, PostgreSQL in Docker Desktop)

| Hot account | Throughput | Rejected | p99 |
|---|---|---|---|
| A | 149–151 tx/s | 0% | 400–495 ms |
| B | 89–92 tx/s | 61–64% | 251–360 ms |
| C | 211–221 tx/s | 0% | 666–756 ms |

With low contention (random pairs of 100 accounts), A and C swapped places between runs, so the difference is within noise. B rejected about 1% and had 2–3x the p99.

### Decision
**Default = A (ordered pessimistic locking)** (`TransferService.DEFAULT_STRATEGY`). B and C remain implemented and covered by tests.

Why A over C, even though C has about 1.4x the throughput on a hot account:
1. **Tail latency over peak throughput.** C's p99 is roughly 40–60% worse under contention. For a payments API, a predictable p99 matters more than peak transfers per second on a single row.
2. **One home for business rules.** With A, every balance rule (normal-balance sign, overdraft guard, and later the Week 6 account freeze) lives in `Account.applyPosting`. C duplicates the overdraft rule in SQL, and every future rule would need a SQL copy as well.
3. **The advantage only appears on a hot row,** and hot rows have a dedicated mitigation planned (credit-only hot-account striping, feature.md §3.4). With low contention there is no measurable winner.
4. **Measurement caveat:** commit fsync passes through the Docker VM, which inflates lock hold times. A and C only differ in how long they hold the lock, so this environment exaggerates C's advantage.

Why not B: on a hot account it rejects more than 60% of requests and has the lowest throughput. It is only suitable where conflicts are rare.

### Consequences
* Throughput on one hot account is capped by lock hold time (~150 tx/s on this machine).
* **Revisit when:** a production hot account needs more than A's measured ceiling and striping is not applicable. In that case, switch `DEFAULT_STRATEGY` to C and port the freeze guard into its `WHERE` clause.

### Canonical lock ordering (all strategies)
Every transaction locks the accounts it touches in the same global order (by account id), which removes Coffman condition #4 (circular wait):
`SELECT a FROM Account a WHERE a.id IN :ids ORDER BY a.id ASC FOR UPDATE`

### Critical Technical Nuance: Java `UUID.compareTo`
Java's `java.util.UUID.compareTo` compares `mostSignificantBits` / `leastSignificantBits` as **signed 64-bit longs**, so UUIDs starting `8`–`f` sort *before* those starting `0`–`7`. PostgreSQL sorts `uuid` as **unsigned bytes**, the opposite for those ids.

Where it matters (corrected 2026-10-20):
- **Strategy A:** it doesn't. All locks are taken by *one* `SELECT ... ORDER BY id FOR UPDATE`, and PostgreSQL locks rows in its own sort order. The Java-side sort only builds the parameter list.
- **Strategy C:** it does. Each `UPDATE` locks one row, so the order Java issues them in *is* the lock order. All strategies therefore sort with `TransferPlan.LOCK_ORDER` (`Comparator.comparing(UUID::toString)`): lowercase hex sorts exactly like PostgreSQL's unsigned bytes.
- **Strategy B:** balance UPDATEs are issued by Hibernate at flush time. `hibernate.order_updates=true` sorts them by primary key (Java's comparator); that is consistent between B transactions, which is all deadlock freedom needs.
- Proof: `BidirectionalDeadlockTest` passes for all three. With C's ordering removed, 471/500 transfers failed with `40P01`.

---

## ADR-003: Balance as a Cached Projection

* **Status**: Accepted
* **Date**: 2026-10-18
* **Deciders**: Principal Backend Engineer

### Context
Calculating account balance on-the-fly by summing all historical postings (`SELECT SUM(...) FROM postings WHERE account_id = ?`) becomes prohibitively slow as posting history grows to millions of rows, violating sub-50ms p95 latency SLAs.

### Decision
Treat `accounts.balance` as a **cached projection** of all committed postings:
1. `postings` and `journal_entries` are the immutable, append-only source of truth.
2. `accounts.balance` is updated atomically inside the same database transaction that persists the postings.
3. An audit/reconciliation worker periodically verifies:
   $$\text{account.balance} = \text{checkpoint\_balance} + \sum \text{postings since checkpoint}$$
   If any discrepancy is detected, the account is immediately frozen and an alert is raised.

---

## ADR-004: Defense-in-Depth Zero-Sum Invariant Enforcement

* **Status**: Accepted
* **Date**: 2026-10-18
* **Deciders**: Senior QA Lead, Principal Backend Engineer

### Context
Relying solely on application-level validation leaves the database vulnerable to bugs in future services, bulk data patches, or unvalidated manual scripts. Conversely, standard row-level triggers cannot validate multi-leg entries because postings are inserted row-by-row, leaving the entry temporarily imbalanced after the first leg is inserted.

### Decision
Implement two-tier defense-in-depth:
1. **Tier 1 (Application / Domain Layer)**: `JournalEntry.create(...)` validates the balance equation before touching the database, throwing `ImbalancedJournalEntryException` immediately to save database roundtrips.
2. **Tier 2 (Database Layer)**: A PostgreSQL `CONSTRAINT TRIGGER` configured as `DEFERRABLE INITIALLY DEFERRED`.
   ```sql
   CREATE CONSTRAINT TRIGGER trg_postings_zero_sum
   AFTER INSERT ON postings
   DEFERRABLE INITIALLY DEFERRED
   FOR EACH ROW
   EXECUTE FUNCTION check_journal_entry_zero_sum();
   ```
   This defers validation until the transaction issues `COMMIT`, ensuring that all multi-leg postings have landed before the database verifies the net sum is zero.

---

## ADR-005: Hexagonal Clean Layering via Domain Ports

* **Status**: Accepted
* **Date**: 2026-10-18
* **Deciders**: Principal Backend Engineer

### Context
Coupling domain entities and services directly to Spring Data or database-specific persistence structures creates leaky abstractions, making business rules difficult to unit-test in isolation.

### Decision
Adopt Hexagonal / Onion Architecture enforced by automated ArchUnit tests:
* `Domain`: Contains business logic, entities, value objects, exceptions, and repository interfaces (ports). It carries JPA mapping annotations (`jakarta.persistence`), a deliberate pragmatic trade-off that avoids a duplicate persistence model, but **no Spring** dependency (enforced by ArchUnit).
* `Application`: Orchestrates use cases (`TransferService`, `AccountService`, the three `ConcurrencyStrategy` implementations). Depends on `Domain` plus Spring's transaction and exception abstractions, but **not** on JPA, Hibernate, JDBC, Spring Data or HTTP (enforced by ArchUnit).
* `Infrastructure`: Implements persistence adapters (`JpaAccountRepository`, `JdbcIdempotencyRepository`) and HTTP controllers (`TransferController`, `GlobalExceptionHandler`).
* **ArchUnit Verification**: `LayerArchitectureTest` runs on every `./gradlew check` and enforces three rules: layer direction, a Spring-free domain, and an application layer free of persistence and HTTP code.

---

## ADR-006: DB-First Idempotency in the Transfer Transaction

* **Status**: Accepted
* **Date**: 2026-10-20
* **Deciders**: Divyansh Kumar

### Context
Clients retry when a response is lost (timeout, dropped connection, crash after commit). Without protection, each retry moves money again. The guarantee we promise is **effectively-once**: the same `Idempotency-Key` with the same payload causes at most one ledger effect.

### Decision
1. **Headers, not body.** `POST /api/v1/transfers` requires `Idempotency-Key` (≤128 chars) and `X-Client-Id` (≤64 chars; stands in for the authenticated principal until auth exists). Missing either one returns 400. Keys are scoped per client: the primary key is `(client_id, idempotency_key)`.
2. **The claim lives in the transfer's own transaction.** `TransferService` opens one transaction. Inside it:
   1. `INSERT INTO idempotency_records ... ON CONFLICT DO NOTHING` (the claim)
   2. the strategy executes the transfer (it joins the same transaction)
   3. the record is linked to the new journal entry
   4. COMMIT. Postings, balances and the record become visible together, or not at all.
3. **Concurrent duplicates wait, then replay (no "409 in progress").** An uncommitted claim is invisible, but its unique-index entry is not: a duplicate's `ON CONFLICT` waits for the original transaction to finish.
   * If the original commits, the duplicate gets 0 rows inserted, reads the committed record and replays it.
   * If the original rolls back, the duplicate inserts its own claim and becomes the new owner.
   * `ON CONFLICT DO NOTHING` is used instead of catching a unique violation, because a failed `INSERT` aborts the PostgreSQL transaction and the follow-up `SELECT` would be impossible.
4. **Payload check.** The record stores `SHA-256` of a canonical form of the request (currency normalised; description; legs in order). Same key with a different hash returns **422**.
5. **Replay is rebuilt, not stored.** The record stores `journal_entry_id`. Because journal entries and postings are immutable, `TransferResponse.from(entry)` reproduces the original body exactly. The replay is marked `X-Cache-Lookup: HIT-IDEMPOTENT`.
6. **Only successes are recorded.** A failed transfer (insufficient funds, imbalance, conflict) rolls back its claim along with everything else. A retry with the same key is then a fresh attempt. This preserves "at most one ledger effect", and a client can retry after topping up an account.
7. **Retries wrap the whole unit.** Strategy B's optimistic-conflict retry moved from the strategy into `TransferService`, outside the transaction, so each retry re-runs claim + transfer together.
8. **Redis fast path deferred** (cut line). Correctness never depended on it. It would only absorb duplicate stampedes before they reach PostgreSQL.

### Failure scenarios (what a client sees)
| Scenario | Outcome |
|---|---|
| Crash or timeout **after COMMIT**, before the response reaches the client | The retry finds the committed record and the original response is replayed. No second effect. |
| Crash **before COMMIT** | PostgreSQL aborts the transaction when the connection drops, which also removes the claim. The retry executes normally. |
| Original still running when the duplicate arrives | The duplicate waits on the claim's index entry, then replays. There is no lease that could expire while the original is alive: the "lock" is the original transaction itself. |
| Same key, different payload | 422 `Idempotency Key Reused` |
| Original failed (e.g. insufficient funds) | Nothing recorded; the retry is a new attempt |

### Consequences
* A duplicate holds a pooled connection while it waits. With a slow original and a large stampede, duplicates can queue on the Hikari pool. `ponytail:` add `lock_timeout` on the claim (returning 409 + Retry-After) or the Redis fast path if this shows up under load.
* Records are never purged yet. A retention job (e.g. 7 days) is future work. `journal_entries.idempotency_reference` (`client:key`) keeps traceability after purge.
* The FK `idempotency_records.journal_entry_id` is `DEFERRABLE INITIALLY DEFERRED`: the record is updated over JDBC before Hibernate flushes the journal entry INSERT at commit.

### Proof
`IdempotencyDuplicateHammerTest` (500 concurrent duplicates → 1 journal entry, 499 replays; mismatch, per-client scoping, failed-then-retried) and `TransferApiIdempotencyTest` (400 / 201 + `HIT-IDEMPOTENT` / 422 over HTTP). Mutation-checked: with the claim disabled, the hammer test fails.
