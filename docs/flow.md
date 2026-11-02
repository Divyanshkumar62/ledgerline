# ledgerline — System Flow & Code Review Guide

> How a request moves through the system, where every decision is made, and which test proves each step.
> Reflects the code as of v0.1.0 (2026-10-20). Diagrams: [architecture.md](architecture.md). Design rationale lives in [decision.md](decision.md); this file shows the *flow*.

---

## 1. Reading Order (Where to Start)

```
1. Core primitives        Money → PostingDirection → AccountType
2. Domain entities         Account → Posting → JournalEntry            (Tier-1 invariants)
3. Domain ports & events   AccountRepository → JournalEntryRepository → IdempotencyRepository → OutboxRepository
                           → ReconciliationRepository; events: TransferCompleted, LedgerDriftDetected
4. Use cases               TransferService (tx boundary, ownership, idempotency, retry, outbox, FX) → AccountService
                           → AccountAdminService → LedgerAuditService → ApiClientService
5. Strategies              TransferPlan → OrderedPessimistic (A) → OptimisticRetry (B) → AtomicConditional (C)
6. Adapters                JpaAccountRepository → JdbcIdempotencyRepository → JdbcOutboxRepository → JdbcReconciliationRepository
                           → TransferController → GlobalExceptionHandler
7. Background workers      OutboxRelay (→ Kafka) → AuditScheduler → RetentionJob
   Observability           CorrelationFilter (MDC) → metrics in TransferService / LedgerAuditService / OutboxMetrics
8. Schema                  V1 ledger + triggers → V2 idempotency → V3 outbox → V4 reconciliation → V5 retention → V6 auth/FX/admin
9. Proof                   see §11 (test map)
```

| # | File | What to notice |
|---|---|---|
| 1 | [Money.java](../src/main/java/com/ledgerline/domain/model/Money.java) | Amounts are `long` minor units + ISO-4217 code. No floating point anywhere. |
| 1 | [AccountType.java](../src/main/java/com/ledgerline/domain/model/AccountType.java) | Each type has a normal balance side; `calculateSignedDelta()` turns DEBIT/CREDIT into +/−. |
| 2 | [Account.java](../src/main/java/com/ledgerline/domain/model/Account.java) | `applyPosting()` is the one home for balance rules (sign, overdraft guard). Accounts open at 0; money only arrives via journal entries. |
| 2 | [JournalEntry.java](../src/main/java/com/ledgerline/domain/model/JournalEntry.java) | `create()` is the **Tier-1 gate**: ≥2 legs, one currency, Σ debits = Σ credits. |
| 4 | [TransferService.java](../src/main/java/com/ledgerline/application/service/TransferService.java) | Opens the only transaction; claims the idempotency key; runs the strategy; retries B outside the transaction. |
| 5 | [TransferPlan.java](../src/main/java/com/ledgerline/application/strategy/TransferPlan.java) | Shared steps + `LOCK_ORDER` (sorts UUIDs exactly like PostgreSQL). Validates *before* any balance is touched. |
| 6 | [JdbcIdempotencyRepository.java](../src/main/java/com/ledgerline/infrastructure/persistence/JdbcIdempotencyRepository.java) | `INSERT ... ON CONFLICT DO NOTHING`, the heart of idempotency. |
| 7 | [OutboxRelay.java](../src/main/java/com/ledgerline/infrastructure/messaging/OutboxRelay.java) | `SKIP LOCKED` batch, pipelined sends, mark only the acked prefix. At-least-once. |
| 4 | [LedgerAuditService.java](../src/main/java/com/ledgerline/application/service/LedgerAuditService.java) | One REPEATABLE READ snapshot per account; freeze + alert in the same transaction. |
| 8 | [V1__init_ledger_schema.sql](../src/main/resources/db/migration/V1__init_ledger_schema.sql) | **Tier-2 gate**: deferred zero-sum trigger + immutability triggers. |

---

## 2. End-to-End Request Lifecycle (`POST /api/v1/transfers`)

```
Client
  │  POST /api/v1/transfers
  │  Headers: Authorization: Bearer <key>, Idempotency-Key, (X-Request-Id)   Body: { description, currency, legs[] }
  ▼
CorrelationFilter
  │  ⓪ MDC trace_id = safe X-Request-Id or new UUID; response header X-Trace-Id
  ▼
ApiKeyAuthenticationFilter + SecurityConfig
  │  ⓪b Authorization: Bearer <key> → SHA-256 → api_clients (active)    unknown/missing ──► 401
  │      role check: /api/v1/admin/** needs ADMIN                       wrong role     ──► 403
  ▼
TransferController
  │  ① Headers present and within length?    no ──► 400 (nothing executes)
  │  ② @Valid body (≥2 legs, amounts > 0)?    no ──► 400 Validation Error
  │  ②b MDC += client_id, idempotency_key, source_account_id, target_account_id
  ▼
TransferService.executeIdempotent(clientId, key, request)
  │  ③ requestHash = SHA-256(canonical request)          (RequestFingerprint)
  │  ④ strategy = DEFAULT_STRATEGY (A, see ADR-002)
  │
  │  ┌──────────── retry loop (only if strategy.maxRetries() > 0, i.e. B) ────────────┐
  │  │ BEGIN                                                                          │
  │  │  ④b ownership: every named account owned by the caller?   no ──► 404 (no claim)│
  │  │  ⑤ claim key: INSERT idempotency_records ... ON CONFLICT DO NOTHING            │
  │  │       └─ not inserted ──► §3 replay / 422 (no transfer runs)                   │
  │  │  ⑥ strategy.executeTransfer(request, "client:key")      (joins this tx; §4)    │
  │  │  ⑦ UPDATE idempotency_records SET journal_entry_id = …                         │
  │  │  ⑦b INSERT outbox_events (TransferCompleted)          (published later, §7)    │
  │  │ COMMIT                                                                         │
  │  │  ⑧ Hibernate flush: INSERT journal_entries, postings; UPDATE accounts          │
  │  │  ⑨ deferred triggers: zero-sum per entry, FK record → entry                    │
  │  │       └─ violation ──► whole tx rolls back (postings, balances, claim)         │
  │  └── optimistic conflict at commit? ──► full-jitter sleep, new transaction ────────┘
  ▼
TransferService (after the transaction)
  │  ⑨b ledger.transfer.duration{outcome} recorded; idempotency hit/miss counted
  ▼
TransferController
  │  ⑩ log "Transfer committed|replayed" (with all MDC fields)
  │     201 Created + TransferResponse     (replay: + X-Cache-Lookup: HIT-IDEMPOTENT)
  ▼
Client
```

Everything between BEGIN and COMMIT is **one** PostgreSQL transaction. The idempotency record, the postings, the balance updates and the outbox event become visible together or not at all. Kafka is **not** on this path.

---

## 3. Idempotency Decision Flow (ADR-006)

```
                      claim(client, key, hash)
                 INSERT ... ON CONFLICT DO NOTHING
                                 │
         ┌───────────────────────┼─────────────────────────────┐
   no conflicting row     conflicting row is              conflicting row is
         │                UNCOMMITTED (in flight)          COMMITTED
         │                       │                              │
         │                 WAIT for that tx                     │
         │              ┌────────┴─────────┐                    │
         │          it commits       it rolls back              │
         │              │                  │                    │
         ▼              ▼                  ▼                    ▼
   1 row inserted   0 rows ────────►  1 row inserted       0 rows
   = OWNER          read record       = OWNER              read record
         │              │                  │                    │
   run transfer         └────────┬─────────┘────────────────────┘
   link entry                    ▼                     (owner paths ──► run transfer)
   COMMIT              stored hash == request hash ?
                         │                    │
                        yes                   no
                         ▼                    ▼
           rebuild response from       IdempotencyKeyReusedException
           immutable journal entry     → 422 Unprocessable Entity
           → 201 + HIT-IDEMPOTENT
```

Key properties:
- A duplicate **never** runs the transfer logic: it blocks at the claim.
- Failed transfers leave **no record** (the claim rolls back with them), so a later retry is a fresh attempt.
- There is no lease to expire: the "lock" is the original transaction itself. If that process dies, PostgreSQL aborts its transaction and frees the key.

---

## 4. Concurrency Strategy Flow (ADR-002)

All three strategies share the same front half ([TransferPlan](../src/main/java/com/ledgerline/application/strategy/TransferPlan.java)):

```
ids = distinct account ids, sorted by LOCK_ORDER
accounts = load(ids)                      ◄── differs per strategy (lock or not)
missing account? ──► AccountNotFoundException (404)
entry = JournalEntry.create(...)          ◄── Tier-1: legs, currency, zero-sum (400 if violated)
```

Then they differ in **how balances are written**:

```
A. ORDERED_PESSIMISTIC (default)    B. OPTIMISTIC_LOCKING              C. ATOMIC_CONDITIONAL
──────────────────────────────      ─────────────────────────────      ──────────────────────────────────
load = SELECT ... FOR UPDATE        load = plain SELECT (no lock)      load = plain SELECT (no lock)
       ORDER BY id                                                     
  (blocks until all rows ours)      applyPosting() in Java             for each account in LOCK_ORDER:
applyPosting() in Java                (funds check on what we read)      UPDATE accounts
  (funds check on locked row)       save → at flush:                       SET balance = balance + Δ
save                                  UPDATE ... WHERE version = ?        WHERE id = ? AND (funds guard)
                                    0 rows? → OptimisticLockFailure      0 rows? → InsufficientFunds (422)
                                      → TransferService retries
                                        (≤5, full jitter) → else 409
```

| | A | B | C |
|---|---|---|---|
| Conflict detected | before reading (waits for lock) | at commit (version) | inside the UPDATE (DB re-checks row) |
| Balance rules live in | `Account.applyPosting` | `Account.applyPosting` | SQL `WHERE` clause |
| Hot-account result | ~150 tx/s, 0% rejected | ~90 tx/s, ~62% rejected | ~215 tx/s, 0% rejected, worst p99 |

---

## 5. Deadlock Elimination (Canonical Lock Order)

### Without ordering
```
  Tx 1 (Alice → Bob)                    Tx 2 (Bob → Alice)
1. locks Alice                        1. locks Bob
2. wants Bob ... [BLOCKED by Tx 2]    2. wants Alice ... [BLOCKED by Tx 1]
            └──────── circular wait → PostgreSQL 40P01 ────────┘
```
Measured: with C's ordering removed, **471 of 500** A↔B transfers died with `40P01`.

### With ordering (every transaction locks in the same global order)
```
  Tx 1 (Alice → Bob)                    Tx 2 (Bob → Alice)
  order: [Alice, Bob]                   order: [Alice, Bob]
1. locks Alice                        1. wants Alice ... waits (no lock held)
2. locks Bob, transfers, COMMIT       2. locks Alice, locks Bob, transfers, COMMIT
```

How each strategy guarantees the order:
- **A**: one `SELECT ... ORDER BY id FOR UPDATE`; PostgreSQL locks rows in its own sort order.
- **C**: Java issues one UPDATE per account, so Java's order *is* the lock order. `LOCK_ORDER` = `UUID::toString`, which sorts exactly like PostgreSQL's unsigned byte order (`UUID.compareTo` is signed and differs).
- **B**: `hibernate.order_updates=true` makes Hibernate flush UPDATEs sorted by primary key.

---

## 6. Defense-in-Depth Invariants (ADR-004)

```
Transfer request / raw SQL
          │
          ▼
  Tier 1: JournalEntry.create()  ── imbalanced / <2 legs / mixed currency ──► exception, NOTHING written
          │ (only API path)
          ▼
  INSERT postings row by row (temporarily unbalanced, which is allowed)
          │
          ▼
  COMMIT → Tier 2: trg_postings_zero_sum (DEFERRABLE INITIALLY DEFERRED)
          │        ── net ≠ 0 or <2 legs ──► PostgreSQL aborts the whole transaction
          ▼
  Committed. Any later UPDATE/DELETE on postings or journal_entries
  ──► trg_immutability_* raises "Ledger records are immutable"
```

Tier 2 catches what Tier 1 cannot see: other services, bulk scripts, manual SQL. The raw-JDBC tests in `ZeroSumBalanceInvariantIntegrationTest` bypass Java entirely to prove it.

---

## 7. Outbox → Kafka Flow (ADR-007)

```
 Transfer transaction (TransferService)              Relay transaction (OutboxRelay, every 200 ms)
 ───────────────────────────────────────              ──────────────────────────────────────────────
 BEGIN                                                BEGIN
   claim key, run strategy, link key                    SELECT ... FROM outbox_events
   INSERT outbox_events (TransferCompleted)               WHERE published_at IS NULL
 COMMIT  ── event exists iff transfer committed ──►       ORDER BY sequence_number LIMIT 100
                                                          FOR UPDATE SKIP LOCKED
                                                        send all rows to Kafka (pipelined)
                                                        wait for acks IN ORDER ──┐
                                                                                 │ first failure? stop:
                                                        UPDATE published_at      │ later rows stay unpublished
                                                          for the acked PREFIX ◄─┘ and are retried next tick
                                                      COMMIT
```

| What can go wrong | What happens |
|---|---|
| Kafka down | Transfers still commit. Events accumulate (`published_at IS NULL`). Each poll fails fast (≤ ~4 s) and is retried. |
| Relay crashes after Kafka acked, before COMMIT | Those rows are sent again → a duplicate with the **same** `event_id`. Consumers dedupe (at-least-once). |
| Transfer rolls back | Its outbox row rolls back with it. No phantom event. |
| Two relay instances | `SKIP LOCKED` gives each a disjoint batch, so they don't block. Cross-instance per-key order is no longer guaranteed. |

Message: key = aggregate id; headers `event_id`, `event_type`, `sequence`, `journal_entry_id`; value =
`{"eventId", "sequence", "type", "journalEntryId", "occurredAt", "data": { ...event fields... }}`.

---

## 8. Audit / Reconciliation Decision Flow (ADR-008)

```
for each account (own REPEATABLE READ transaction = one consistent snapshot):

  checkpoint  = reconciliation_checkpoints[account]   or  (balance 0, seq 0)
  window      = postings of this account with seq > checkpoint.seq
  expected    = checkpoint.verified_balance + Σ signed(window)
  actual      = accounts.balance
                     │
          actual == expected ?
           │                 │
          yes                no ─────────────────────────────────┐
           │                                                     ▼
   newest posting older than                         already frozen?
   checkpoint-lag (60 s) exists?                      │            │
     │            │                                  yes           no
    yes           no                                  │            ▼
     ▼            ▼                              report only   UPDATE accounts SET frozen = true, version + 1
 move checkpoint  keep checkpoint               (no new alert) INSERT outbox LedgerDriftDetected
 to that seq                                                   (checkpoint NOT advanced)
                                                               report drift (expected, actual, delta)
```

After a freeze, every strategy refuses the account on both sides of a transfer → `423 Locked`.

---

## 8b. Housekeeping (ADR-009)

```
RetentionJob (hourly)
  ├─ DELETE outbox_events     WHERE published_at < now - 7d      (batches of 5,000; unpublished: never)
  └─ DELETE idempotency_records WHERE created_at < now - 7d      (after this, the same key executes again)
journal_entries, postings: never deleted
```

---

## 8c. Cross-Currency Transfer (ADR-012)

```
POST /api/v1/fx-transfers {from (USD), to (EUR), sourceAmountMinor 10000, rate 0.9150}
  │  ownership + idempotency claim as in §2
  │  same currency? ──► 400 (use /transfers)
  │  target = round_half_even(10000 / 10^2 × 0.9150 × 10^2) = 9150        (minor digits per currency)
  │  FX clearing USD / EUR: find-or-create (ON CONFLICT DO NOTHING)
  ▼
  4 legs, locked in canonical order by the default strategy:
     CREDIT client USD 10000   DEBIT clearing USD 10000     ← USD nets to 0
     CREDIT clearing EUR 9150  DEBIT client EUR 9150        ← EUR nets to 0
  + fx_conversions row (rate, amounts, rounding) + TransferCompleted (per-leg currency)
COMMIT → deferred trigger checks zero-sum PER CURRENCY and posting currency = account currency
```

## 8d. Repair and Unfreeze (ADR-013)

```
              frozen account (audit found drift, or admin froze it)
                                 │
                 which number is true?
          ┌──────────────────────┴───────────────────────┐
   postings are right                              cached balance is right
   (balance was edited)                            (a posting was lost)
          │                                              │
   rebuild-balance                                 book-missing-posting
   cache := Σ postings                             entry: account ⇄ ADJUSTMENT equity,
   (no money moves)                                amount = balance − postings (ledger-computed)
          └──────────────────────┬───────────────────────┘
                              unfreeze
                   audit clean now?  no ──► 409, stays frozen
                                     yes ──► frozen = false
every step: lock · reason required · account_admin_actions row · AccountAdminActionTaken event
```

---

## 9. Error → HTTP Status Map

| Exception | Status | When |
|---|---|---|
| No / unknown / deactivated API key | 401 | Security filter, before anything executes |
| Valid key, wrong role (client on `/admin`) | 403 | Security filter |
| Missing/oversized `Idempotency-Key`, invalid body, unknown currency, same-currency FX | 400 | Before anything executes |
| `ImbalancedJournalEntryException`, `InvalidJournalEntryException`, `CurrencyMismatchException` | 400 | Tier-1 rejection |
| `AccountNotFoundException` | 404 | Unknown account, **or one owned by another client** |
| `InsufficientFundsException` | 422 | Overdraft guard (`disallow_negative_balance`) |
| `AccountFrozenException` | 423 Locked | An account in the transfer is frozen (audit or admin) |
| `AccountStillDriftedException` | 409 | Admin unfreeze while the account still fails its audit |
| `IdempotencyKeyReusedException` | 422 | Same key, different payload |
| `ConcurrencyFailureException` (B retries exhausted, lock timeout, deadlock victim) | 409 + `Retry-After: 1` | Nothing committed; safe to retry with the same key |

---

## 10. Layering & Dependency Direction (ADR-005)

```
 Infrastructure: web             Infrastructure: persistence
 (TransferController,            (JpaAccountRepository, JpaJournalEntryRepository,
  GlobalExceptionHandler)         JpaPostingRepository, JdbcIdempotencyRepository,
 Infrastructure: workers          JdbcOutboxRepository, JdbcReconciliationRepository)
 (OutboxRelay → Kafka, AuditScheduler)
          │ calls                              │ implements
          ▼                                    ▼
 Application ───────── uses ──────────► Domain ports (AccountRepository, IdempotencyRepository, …)
 (TransferService, strategies,                 │
  AccountService, DTOs)                        ▼
          └───────── uses ──────────► Domain model (Account, JournalEntry, Posting, Money, exceptions)
```

Enforced by `LayerArchitectureTest` on every build:
1. Layer direction: Infrastructure → Application → Domain, never backwards.
2. Domain has **no Spring** dependency (it does use `jakarta.persistence` mapping annotations, by choice).
3. Application has **no** JPA, Hibernate, JDBC, Spring Data or HTTP dependency.

---

## 11. Test Map (Which Test Proves What)

| Claim | Test | Kind |
|---|---|---|
| Balanced entries only; ≥2 legs; one currency | `JournalEntryTest` | unit |
| Validate before mutate; lock list is sorted and deduplicated | `TransferServiceTest` | unit |
| Layer rules | `LayerArchitectureTest` | ArchUnit |
| Same request → same fingerprint; any change → different | `RequestFingerprintTest` | unit |
| Flyway applies the schema on PostgreSQL 16 | `LedgerlineApplicationTests` | Testcontainers |
| Zero-sum in domain AND DB trigger; immutability | `ZeroSumBalanceInvariantIntegrationTest` | Testcontainers |
| No lost updates, no overspend, no drift (×3 strategies) | `ConcurrentTransferStressTest` | Testcontainers |
| Zero `40P01` in A↔B storm (×3 strategies) | `BidirectionalDeadlockTest` | Testcontainers |
| 500 duplicates → 1 effect + 499 replays; 422; per-client scope; failure releases key | `IdempotencyDuplicateHammerTest` | Testcontainers |
| HTTP contract: 400 / 201 + `HIT-IDEMPOTENT` / 422 | `TransferApiIdempotencyTest` | Testcontainers + MockMvc |
| MDC fields in logs, safe trace ids, business metrics | `ObservabilityTest` | Testcontainers + MockMvc |
| Retention deletes only what is safe; never ledger history | `RetentionJobTest` | Testcontainers |
| 401/403, hash-only keys, revocation, owner-only accounts (404) | `SecurityApiTest` | Testcontainers + MockMvc |
| FX: four legs, per-currency zero-sum, clearing positions, replay, rejections | `FxTransferApiTest`, `FxMathTest` | Testcontainers + MockMvc / unit |
| Statement: newest-first keyset pagination, exact running balance | `AccountStatementApiTest` | Testcontainers + MockMvc |
| Repair → re-audit → unfreeze; audit trail; guards | `AccountAdminApiTest` | Testcontainers + MockMvc |
| Event exists iff its transfer committed; replays/failures add none | `OutboxAtomicityTest` | Testcontainers |
| Kafka down 30 s: transfers commit, events buffered, none lost after recovery | `TransactionalOutboxResilienceTest` | Testcontainers + Kafka |
| Exact drift detection, freeze, single alert, incremental checkpoints, no false alarms under load | `ChaosDriftDetectionTest` | Testcontainers |
| TCP resets under load: no partial commit, one effect per key after retries, zero drift | `ToxiproxyConnectionResetTest` | Testcontainers + Toxiproxy |
| Throughput / p99 / rejection per strategy | `LockingStrategyBenchmark` (`./gradlew benchmark`) | benchmark, not in `check` |
| HTTP steady state, p99 < 50 ms at 200 req/s | `perf/k6/steady-state.js` | k6, not in `check` |
