# ledgerline — Architecture Decision Records (ADRs)

This document records the foundational architectural decisions made in `ledgerline`, outlining the problem context, chosen solution, alternatives considered, and engineering tradeoffs. It is the single source of truth for ADR numbers.

| ADR | Decision | Status | Proven by |
|---|---|---|---|
| [001](#adr-001-double-entry-bookkeeping-model-vs-single-entry-balances) | Double-entry bookkeeping, BIGINT minor units | Accepted | `JournalEntryTest` |
| [002](#adr-002-concurrency-strategy-and-canonical-lock-ordering) | Default concurrency strategy = ordered pessimistic locking; canonical lock order | Accepted (measured) | `ConcurrentTransferStressTest`, `BidirectionalDeadlockTest`, benchmark |
| [003](#adr-003-balance-as-a-cached-projection) | Balance is a cached projection of postings | Accepted (verified by the ADR-008 audit) | `assertBalanceMatchesPostings` in every integration test |
| [004](#adr-004-defense-in-depth-zero-sum-invariant-enforcement) | Zero-sum enforced in domain AND deferred DB trigger | Accepted | `ZeroSumBalanceInvariantIntegrationTest` |
| [005](#adr-005-hexagonal-clean-layering-via-domain-ports) | Hexagonal layering via domain ports | Accepted | `LayerArchitectureTest` |
| [006](#adr-006-db-first-idempotency-in-the-transfer-transaction) | DB-first idempotency in the transfer transaction | Accepted | `IdempotencyDuplicateHammerTest`, `TransferApiIdempotencyTest` |
| [007](#adr-007-transactional-outbox-with-at-least-once-delivery) | Transactional outbox, at-least-once delivery to Kafka | Accepted | `TransactionalOutboxResilienceTest`, `OutboxAtomicityTest` |
| [008](#adr-008-incremental-self-audit-with-checkpoints-and-account-freeze) | Incremental self-audit with checkpoints; freeze on drift | Accepted | `ChaosDriftDetectionTest`, `ToxiproxyConnectionResetTest` |
| [009](#adr-009-retention-for-outbox-and-idempotency-records) | Retention: purge published outbox rows and old idempotency keys, never ledger history | Accepted | `RetentionJobTest` |
| [010](#adr-010-observability-business-metrics-and-correlated-logs) | Observability: business metrics, Prometheus endpoint, MDC correlation, k6 baseline | Accepted | `ObservabilityTest`, k6 runs |
| [011](#adr-011-api-key-authentication-and-owner-only-accounts) | API-key authentication; accounts visible and movable by their owner only | Accepted | `SecurityApiTest` |
| [012](#adr-012-multi-currency-transfers-through-fx-clearing-accounts) | Multi-currency: zero-sum per currency, FX via clearing accounts | Accepted | `FxTransferApiTest`, `FxMathTest` |
| [013](#adr-013-repair-re-audit-then-unfreeze) | Admin repair (rebuild / book missing posting), unfreeze only when clean | Accepted | `AccountAdminApiTest` |

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

### Measurements ([full data](benchmarks/locking-strategies.md), two runs, 32 workers, PostgreSQL in Docker Desktop)

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

### Re-measured after Weeks 4–6 (runs 3–4, same machine)
Every transfer now also writes an outbox event inside its transaction, and B's retry loop moved into `TransferService`.

| | Hot account tx/s | Hot p99 | Low-contention tx/s | Low-contention p99 |
|---|---|---|---|---|
| A | 101–102 | 718–880 ms | 1156–1160 | 87–90 ms |
| B | 63–64 (60–63% rejected) | 445–536 ms | 703–806 | 219–232 ms |
| C | 128–137 | **1.7–3.1 s** | 1284–1313 | 77–79 ms |

* Hot-account throughput fell about 33% for all three strategies: more work happens while the lock is held. The ranking is unchanged.
* C's hot-account tail is now 2–3.5× worse than A's, which strengthens reason 1.
* **New:** at low contention C is consistently about 11% faster than A, with a slightly better p99 (before, the gap was within noise). This is the price of choosing A, accepted for reasons 1–2. If the workload is known to be low-contention only, C becomes the better choice.

### Consequences
* Throughput on one hot account is capped by lock hold time (~100 tx/s on this machine since the outbox was added).
* **Revisit when:** a production hot account needs more than A's measured ceiling and striping is not applicable. In that case, switch `DEFAULT_STRATEGY` to C and port the freeze guard into its `WHERE` clause.

### Canonical lock ordering (all strategies)
Every transaction locks the accounts it touches in the same global order (by account id), which removes Coffman condition #4 (circular wait):
`SELECT a FROM Account a WHERE a.id IN :ids ORDER BY a.id ASC FOR UPDATE`

### Critical Technical Nuance: Java `UUID.compareTo`
Java's `java.util.UUID.compareTo` compares `mostSignificantBits` / `leastSignificantBits` as **signed 64-bit longs**, so UUIDs starting `8`–`f` sort *before* those starting `0`–`7`. PostgreSQL sorts `uuid` as **unsigned bytes**, the opposite for those ids.

Where it matters (corrected 2026-10-20):
- **Strategy A:** it doesn't. All locks are taken by *one* `SELECT ... ORDER BY id FOR UPDATE`, and PostgreSQL locks rows in its own sort order. The Java-side sort only builds the parameter list.
- **Strategy C:** it does. Each `UPDATE` locks one row, so the order Java issues them in *is* the lock order. All strategies therefore sort with `TransferPlan.LOCK_ORDER` (`Comparator.comparing(UUID::toString)`): lowercase hex sorts exactly like PostgreSQL's unsigned bytes.
- **Strategy B:** balance UPDATEs are issued by Hibernate at flush time. `hibernate.order_updates=true` sorts them by primary key (Java's comparator); that is consistent between B transactions, which is all deadlock freedom needs. Since v0.1.0, B flushes those UPDATEs **before** inserting postings (`saveAllAndFlush`): the default inserts-first order produced a rare PostgreSQL internal error under contention (see [bug-writeups.md](bug-writeups.md) #3).
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
* `Application`: Orchestrates use cases (`TransferService`, `LedgerAuditService`, `AccountService`, the three `ConcurrencyStrategy` implementations). Depends on `Domain` plus Spring's transaction and exception abstractions, but **not** on JPA, Hibernate, JDBC, Spring Data or HTTP (enforced by ArchUnit).
* `Infrastructure`: Implements persistence adapters (`JpaAccountRepository`, `JdbcIdempotencyRepository`, `JdbcOutboxRepository`, `JdbcReconciliationRepository`), HTTP controllers (`TransferController`, `GlobalExceptionHandler`) and background workers (`OutboxRelay` → Kafka, `AuditScheduler`).
* **ArchUnit Verification**: `LayerArchitectureTest` runs on every `./gradlew check` and enforces three rules: layer direction, a Spring-free domain, and an application layer free of persistence and HTTP code.

---

## ADR-006: DB-First Idempotency in the Transfer Transaction

* **Status**: Accepted
* **Date**: 2026-10-20
* **Deciders**: Divyansh Kumar

### Context
Clients retry when a response is lost (timeout, dropped connection, crash after commit). Without protection, each retry moves money again. The guarantee we promise is **effectively-once**: the same `Idempotency-Key` with the same payload causes at most one ledger effect.

### Decision
1. **Headers, not body.** `POST /api/v1/transfers` requires `Idempotency-Key` (≤128 chars); missing → 400. *(Originally also `X-Client-Id` as a stand-in for authentication; since ADR-011 the client is the authenticated API-key owner.)* Keys are scoped per client: the primary key is `(client_id, idempotency_key)`.
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


---

## ADR-007: Transactional Outbox with At-Least-Once Delivery

* **Status**: Accepted
* **Date**: 2026-10-20
* **Deciders**: Divyansh Kumar

### Context
Other systems need to know when money moves (`TransferCompleted`) or when the ledger detects a problem (`LedgerDriftDetected`). The obvious approach, "commit to PostgreSQL, then publish to Kafka", is a **dual write** with two failure modes:
* Crash between the two steps → the transfer happened but the event is lost forever.
* Publish first, then the DB commit fails → consumers act on a transfer that never happened.

There is no transaction spanning PostgreSQL and Kafka.

### Decision
1. **Write the event to PostgreSQL in the ledger transaction.** `TransferService` appends `TransferCompleted` to `outbox_events` inside the same transaction as the postings, and the audit appends `LedgerDriftDetected` in the same transaction as the freeze. The event exists **if and only if** the change committed. Replays and failed transfers write nothing (`OutboxAtomicityTest`).
2. **A relay publishes afterwards** (`OutboxRelay`, every 200 ms):
   `SELECT ... WHERE published_at IS NULL ORDER BY sequence_number LIMIT 100 FOR UPDATE SKIP LOCKED` → send the whole batch → wait for the acks in order → mark the **acknowledged prefix** published → COMMIT.
3. **Delivery is at-least-once, never "exactly-once".** If the relay dies after Kafka acked but before COMMIT, those rows are sent again. Every message carries a stable `event_id` (header and envelope), so consumers deduplicate on it. A re-send reuses the same `event_id`.
4. **Ordering:** each message is keyed by its aggregate (journal entry, or account for drift events), so one aggregate's events land in one partition. The relay stops at the first failure instead of skipping ahead, so a single relay preserves order. `sequence_number` is monotonic per **insert**, not per commit: a lower number can commit later, and rollbacks leave gaps. It is an ordering hint, not a gap-free counter.
5. **Kafka is off the transfer path.** If the broker is down, transfers still commit; events wait in the table.
6. **Fail fast while the broker is down:** `max.block.ms=2000`, `request.timeout.ms=2000`, `delivery.timeout.ms=4000`. The relay holds a DB transaction (row locks on its batch) while it waits for acks, so it must never wait minutes.
7. **Topics:** `ledger.transfers.v1` (key: journal entry id), `ledger.accounts.v1` (key: account id). Created on first publish by broker auto-creation; there are no `NewTopic` beans, because `KafkaAdmin` would block every startup without a broker.

### Alternatives considered
* **Debezium / CDC** on the outbox table: no polling, but a Kafka Connect cluster to run. Deferred (feature.md §10).
* **Publishing from the request thread after commit**: loses events on a crash between commit and publish, which is exactly the problem we are solving.

### Consequences
* Consumers **must** be idempotent (dedup on `event_id`).
* Event latency is the poll interval plus the send time (~200 ms), not instant.
* The outbox grows forever. `ponytail:` add a purge of rows published more than N days ago when table size matters.
* `SKIP LOCKED` allows several relay instances, but then cross-instance order per key is no longer guaranteed. Run a single relay instance, or partition the outbox by key hash, if strict per-key order matters.

### Proof
`TransactionalOutboxResilienceTest`: the broker is paused for 30 s while 50 transfers commit; exactly those 50 events are buffered; after recovery every one of the 70 transfers is found on the topic, with exactly one distinct `event_id` each. Mutation-checked: a relay that marks events published without a real ack fails this test (0 buffered instead of 50).

Two relay bugs were found while building this:
1. `KafkaTemplate.send()` can throw **synchronously** when the broker is unreachable. That escaped the per-row handling and rolled back rows that had already been acked, so they were sent twice. It is now treated like a missing ack.
2. One-at-a-time "send, wait, send" was too slow to drain a backlog of thousands of events. The relay now pipelines the batch and marks the acknowledged prefix.

---

## ADR-008: Incremental Self-Audit with Checkpoints and Account Freeze

* **Status**: Accepted
* **Date**: 2026-10-20
* **Deciders**: Divyansh Kumar

### Context
`accounts.balance` is a cache (ADR-003). Anything that changes it without a posting (a bug, a manual SQL "fix", a bad migration) creates money from nothing, or destroys it, and nothing else would notice. Recomputing every balance from full history gets slower as the ledger grows.

### Decision
1. **Invariant checked:** `accounts.balance = checkpoint.verified_balance + Σ signed postings with seq > checkpoint.last_posting_seq`.
2. **Cursor:** `postings.seq` (identity column, V4) plus `reconciliation_checkpoints` (one row per account). An audit reads only the postings after the checkpoint (index `(account_id, seq)`).
3. **One snapshot per account.** Each account is audited in its own `REPEATABLE READ` transaction, so the balance and the postings come from the same snapshot. A concurrent transfer is either fully visible (balance and postings) or fully invisible. Reading them in two separate statements at `READ COMMITTED` would report a transfer that commits in between as drift.
4. **Why the per-account cursor is safe, and what the lag is for.** A posting for account X can only commit if its transaction owned X's row from before the posting's INSERT until COMMIT:
   * A locks the row first (`FOR UPDATE`).
   * C's `UPDATE` locks it before the posting is inserted.
   * B's version check fails the commit if anyone else committed to X since B read it.

   So for each account, posting `seq` order matches commit order, and a checkpoint can never skip past a posting that commits later. Measured: an audit running continuously against 2,000 concurrent transfers reported **zero** false alarms, with A and with B, and even with no lag (266 audit rounds).
   The `checkpoint-lag` (60 s; postings younger than this are verified but not yet folded into the checkpoint) is a **safety margin** against a future writer that inserts postings without owning the account row. It costs only checkpoint freshness, never correctness of the verification.
5. **On drift**, in the same transaction:
   * set `accounts.frozen = true` and bump `version`, so in-flight optimistic writers fail and re-read
   * append `LedgerDriftDetected {accountId, expectedBalance, actualBalance, delta}` to the outbox
   * log the details
   * do **not** advance the checkpoint

   An already-frozen account is reported again on every round but alerted only once.
6. **A frozen account takes part in no transfer, on either side.** A and B enforce it in `Account.applyPosting`. C needs its own check plus `AND NOT frozen` in its SQL, a concrete example of the rule duplication ADR-002 warned about. The API returns `423 Locked`.
7. **Schedule:** `AuditScheduler` runs every 60 s (`ledgerline.audit.interval-ms`). Accounts that hit a serialization conflict are skipped and retried next round.
8. ~~Unfreezing is a manual, out-of-band operation.~~ **Superseded by ADR-013:** admin endpoints repair the account (rebuild the cache or book the missing posting), and unfreeze is refused until it audits clean.

### Consequences
* Detection latency is up to one audit interval.
* Freezing is deliberately blunt: a drifted account stops moving money until a human looks.
* CSV settlement matching (feature.md §6.2) is deferred; this ADR covers internal self-audit only.

### Proof
* `ChaosDriftDetectionTest`:
  * 777 injected into a balance is reported exactly (expected 9,000, actual 9,777)
  * the account is frozen and all three strategies refuse it in both directions
  * one alert event is emitted, not one per round
  * checkpoints advance incrementally
  * there are no false alarms during concurrent load
* `ToxiproxyConnectionResetTest` (chaos): TCP resets on the database connection under load. Clients retry failed requests with the same idempotency key. Afterwards there is one journal entry per key, no journal entry with fewer than 2 legs or a non-zero sum, money is conserved, and the audit reports zero discrepancies. The test also observed a request that failed with a connection error but **had committed**: its retry replayed instead of moving money twice.

---

## ADR-009: Retention for Outbox and Idempotency Records

* **Status**: Accepted
* **Date**: 2026-10-20
* **Deciders**: Divyansh Kumar

### Context
`outbox_events` gains a row per transfer and `idempotency_records` a row per API request. Both grow without bound, but neither is ledger history, and both lose their value with age.

### Decision
`RetentionJob` (hourly cron, `ledgerline.retention.cron`; `-` disables it):
1. **Outbox:** delete rows **published** more than 7 days ago. Unpublished rows are never deleted, however old, because that would lose an event.
2. **Idempotency:** delete records created more than 7 days ago. After that, a retry with the same key **executes again**, so the retention is a promise: "retries within 7 days are safe". It must exceed every client's retry window. `journal_entries.idempotency_reference` keeps the `client:key` trace permanently.
3. **Ledger tables (`journal_entries`, `postings`) are never purged.** Immutability triggers would refuse it anyway.
4. Deletes run in batches of 5,000 (one short statement each) so they never hold long locks. Indexes on `published_at` and `created_at` (V5) keep them cheap.

### Proof
`RetentionJobTest`: old published events are deleted, recent and unpublished ones are kept, journal entries are untouched; old keys are deleted, recent keys still replay, and a purged key executes again (documenting the trade-off).

---

## ADR-010: Observability — Business Metrics and Correlated Logs

* **Status**: Accepted
* **Date**: 2026-10-20
* **Deciders**: Divyansh Kumar

### Decision
**Metrics** (Micrometer → Prometheus at `/actuator/prometheus`):

| Metric | Type | Meaning / alert |
|---|---|---|
| `ledger.transfer.duration{strategy,outcome}` | timer + histogram | outcome = committed / replayed / rejected / conflict / error. Histogram buckets let Prometheus compute p50/p95/p99 across instances. |
| `ledger.concurrency.retries{strategy}` | counter | optimistic retries (B only) |
| `ledger.idempotency.hits` / `.misses` / `.mismatches` | counters | replays / new transfers / 422s |
| `ledger.audit.discrepancies` | gauge | discrepancies in the last full audit. **Alert if > 0.** |
| `ledger.audit.drift.detected`, `ledger.audit.accounts.checked` | counters | newly frozen accounts / audit work done |
| `ledger.outbox.backlog` | gauge | events not yet acked by Kafka |
| `ledger.outbox.oldest.unpublished` | gauge (s) | **Alert on this**, not the backlog: a backlog that drains is fine, an oldest event that keeps aging means the relay or Kafka is stuck |
| `ledger.outbox.published`, `.publish.failures` | counters | relay throughput / failed passes |

* Tags are low-cardinality on purpose: no account ids or keys in tags (those belong in logs).
* Hit/miss counters are incremented **after** the transaction, so a rolled-back attempt never counts.
* The application layer uses `MeterRegistry` directly. Micrometer is a facade like SLF4J; wrapping it in a port would add code without decoupling anything real.

**Logs:** `CorrelationFilter` puts `trace_id` in the MDC: a caller's `X-Request-Id` if it matches `[A-Za-z0-9._-]{1,64}`, otherwise a fresh UUID, so callers can't inject text into logs. It is echoed back as `X-Trace-Id`. The controller adds `client_id`, `idempotency_key`, `source_account_id` (CREDIT legs) and `target_account_id` (DEBIT legs). Every log line carries them (`logging.pattern.level`), and the filter clears them because servlet threads are pooled.

**Baseline:** k6 steady state, 200 req/s open model, 60 s ([results](benchmarks/k6-steady-state.md)): p99 25–28 ms, 0 failures. **p99 target = 50 ms**, set after the first run and enforced by k6 in the second.

### Consequences
* The two outbox gauges run a SQL query on every scrape. They are cheap thanks to the partial index; move them to a cached value if the scrape interval becomes very short.
* OpenTelemetry tracing is still out of scope; `trace_id` is a log correlation id, not a distributed trace.

### Proof
`ObservabilityTest`: the MDC fields appear in a real log line, an unsafe request id is replaced, and the counters and timer move as expected. The live run reconciled the server metrics with the database exactly (36,550 misses = 36,550 journal entries = 36,550 published events).


---

## ADR-011: API-Key Authentication and Owner-Only Accounts

* **Status**: Accepted
* **Date**: 2026-10-20
* **Deciders**: Divyansh Kumar

### Context
Until this ADR the API trusted an `X-Client-Id` header: anyone could claim to be anyone and move any account. A ledger's callers are other services (a shop backend, a payments service), not browsers, and each must only touch its own money.

### Decision
1. **API keys, machine-to-machine (the Stripe model).**
   * Keys are 32 random bytes (`ll_` + base64url), sent as `Authorization: Bearer <key>`.
   * Only `SHA-256(key)` is stored (`api_clients.key_hash`, unique). The key is shown once, at creation.
   * A fast hash is correct here: bcrypt and argon2 exist to slow down guessing of *low-entropy human passwords*. A 256-bit random key cannot be guessed, and a fast hash keeps per-request authentication cheap.
2. **Roles:**
   * `CLIENT` uses `/api/**`.
   * `ADMIN` additionally uses `/api/v1/admin/**`.
   * `SYSTEM` owns ledger-internal accounts and **cannot authenticate** (no key, enforced by a CHECK constraint).
3. **Spring Security, stateless.** A custom filter resolves the key into an `ApiClient` principal. There are no sessions and no cookies, so CSRF protection (which defends cookie-authenticated browsers) does not apply. Every route is denied unless explicitly allowed. 401 and 403 are `application/problem+json`, and 401 carries `WWW-Authenticate: Bearer`.
4. **Ownership.**
   * Every account has `owner_client_id` (FK to `api_clients`).
   * A client can only read, list postings of, or move accounts it owns.
   * **Someone else's account returns 404, exactly like a non-existent one**, so account ids cannot be probed.
   * The check runs inside the transfer transaction, **before the idempotency claim**, so a rejected request never consumes a key.
   * It is a scalar query: loading `Account` entities before the strategy locks them would let the strategy see their pre-lock state. That's a subtle lost-update trap.
5. **Idempotency keys are scoped to the authenticated client** (no more `X-Client-Id` header).
6. **First key:** `ledgerline.security.bootstrap-admin-key` (from the environment) creates an `admin` client once, if none exists. docker-compose uses a development value.
7. **Revocation is immediate:** `active = false` takes effect on the next request (the key is looked up on every request; no cache).

### Consequences
* One indexed lookup per request. `ponytail:` cache with a short TTL (e.g. 5–30 s) if profiling shows it matters. The cost is that revocation becomes eventually consistent within that TTL.
* No per-client rate limiting yet; it belongs at the edge (gateway) or as a bucket per client.
* Key rotation is "create a new client or key, deactivate the old one". There is no rotate endpoint in v0.1.

### Proof
`SecurityApiTest`:
* 401 with no or an unknown key
* 403 for a client on admin endpoints
* an admin-issued key works, and only its hash is stored
* a deactivated key is refused at once
* another client's account returns 404 for read, statement and transfer, and no key is consumed
* unknown currencies are rejected

Mutation-checked: with the ownership check removed, the cross-client test fails.

---

## ADR-012: Multi-Currency Transfers through FX Clearing Accounts

* **Status**: Accepted
* **Date**: 2026-10-20
* **Deciders**: Divyansh Kumar

### Context
Each account has one currency, and until now each journal entry did too. Paying from a USD account into a EUR account was impossible. The naive fix ("debit 100 USD, credit 91.50 EUR, call it balanced") breaks double-entry: you cannot add dollars to euros.

### Decision
1. **Zero-sum per currency.** An entry may span currencies, but debits must equal credits **within each currency separately**. This is enforced in the domain (`JournalEntry.createMultiCurrency`) and in the database trigger, which now groups by currency. The old trigger summed every leg, so USD +500 / EUR −500 would have passed. A test proves it no longer does. The trigger also rejects a posting whose currency differs from its account's.
2. **Value crosses currencies only through FX clearing accounts**: one ledger-owned `ASSET` account per currency (`system_purpose = 'FX_CLEARING'`), created on first use with a concurrency-safe `INSERT ... ON CONFLICT DO NOTHING`. A conversion is four legs:

   | Leg | USD | EUR |
   |---|---|---|
   | Client USD account | CREDIT 10000 | |
   | FX clearing USD | DEBIT 10000 | |
   | FX clearing EUR | | CREDIT 9150 |
   | Client EUR account | | DEBIT 9150 |

   USD balances, EUR balances, and the clearing accounts hold the ledger's open currency position (what a treasury desk would hedge).
3. **The client supplies the rate** (`POST /api/v1/fx-transfers`, `rate` = target units per one source unit, in major units). The ledger records rates; it does not quote prices. Each conversion is stored in `fx_conversions` (rate, both amounts, rounding mode) next to the entry it produced.
4. **Minor units differ per currency** (USD 2 decimals, JPY 0, KWD 3, from `java.util.Currency`). Conversion is exact `BigDecimal` arithmetic, **rounded once at the end, half-even**, which has no systematic bias when many conversions are summed. A conversion that rounds to zero is rejected. Account creation now validates real ISO 4217 codes.
5. Same idempotency, ownership, locking and outbox path as any transfer. The four legs run through the default strategy, locking all four accounts in canonical order.

### Consequences
* Every FX transfer locks the clearing accounts, which are **hot accounts** by construction; the limits measured in ADR-002 apply. `ponytail:` stripe clearing accounts per currency (feature.md §3.4) if FX volume becomes significant.
* The rounding remainder stays in the clearing accounts' position. That is intended, and it is visible.
* No rate source, spread or fee logic: those belong to a pricing service that calls this API.

### Proof
* `FxTransferApiTest`:
  * 100.00 USD → 91.50 EUR with four legs, each currency netting to 0, and the clearing positions moving by +10000 USD and −9150 EUR
  * USD → JPY across minor-unit sizes
  * idempotent replay of the exact bytes; a different rate under the same key → 422
  * same currency → 400, overdraft → 422 with nothing moved, another client's account → 404
* `FxMathTest`: half-even cases and KWD/JPY.
* `ZeroSumBalanceInvariantIntegrationTest`: raw SQL cross-currency netting is rejected (mutation-checked against the old trigger).

---

## ADR-013: Repair, Re-Audit, then Unfreeze

* **Status**: Accepted (amends ADR-008 point 8)
* **Date**: 2026-10-20
* **Deciders**: Divyansh Kumar

### Context
ADR-008 froze drifted accounts with no way back except manual SQL, which is exactly the kind of untracked change that causes drift in the first place.

### Decision
Admin-only endpoints (`/api/v1/admin/accounts/{id}/...`). Each one locks the account like a transfer, requires a reason, writes an append-only `account_admin_actions` row (who, why, balance before/after, correcting entry) and publishes `AccountAdminActionTaken` through the outbox, all in one transaction.

| Action | When to use it | What it does |
|---|---|---|
| `audit` | Check now | Runs the ADR-008 audit for one account (freezing it on drift) |
| `freeze` | Suspected fraud, investigation | Stops all movement on the account |
| `rebuild-balance` | The **postings are right** (someone edited the balance) | Cached balance := Σ postings. No money moves. |
| `book-missing-posting` | The **balance is right** (a posting was lost) | Books the missing amount, **computed by the ledger** (balance − postings) and never typed in, as a journal entry against the per-currency `ADJUSTMENT` equity account. Only the adjustment account's cache moves. |
| `unfreeze` | After repair | **Refused with 409 unless the account audits clean right now** |

Drift means two numbers disagree, and only a human can decide which one is true, so there are two repairs, not one. Neither repair can make money appear silently: rebuild moves no money, and a booked posting is a balanced, permanent, explained journal entry.

### Proof
`AccountAdminApiTest`:
* drift → audit freezes → transfer 423 → unfreeze refused (409, discrepancy 777) → rebuild → unfreeze → transfers work, with the actions and events recorded
* book-missing-posting leaves the cache unchanged and makes the postings agree, against the `ADJUSTMENT` account; booking again → 400
* a manual freeze blocks both directions; the reason is mandatory; clients get 403

Mutation-checked: with the clean-audit guard removed, the unfreeze test fails.
