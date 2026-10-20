# ledgerline — Engineering Delivery Tracker (v0.1)

> **Mission**: Build a reference implementation of a double-entry ledger in Java 21 / Spring Boot 4 that *proves*, with tests and benchmark data, how to stay correct under concurrency, retries and partial failures.
> **Budget**: 6 hours/week × 8 weeks = **48 hours**. Scope is sized to fit this.
> **Rules**:
> 1. I write the core code myself. AI is a reviewer and rubber duck, not the author.
> 2. Every phase ends with a passing test that proves the claim. No test, no checkmark.
> 3. The README only claims what exists and passes.
> 4. Numbers are measured, never pre-decided. Publish results with hardware specs.
>
> Companion docs: decisions and ADR numbering in [decision.md](decision.md), request and decision flows in [flow.md](flow.md), concepts in [learning.md](learning.md), raw benchmark data in [benchmarks/](benchmarks/).

---

## Roadmap

```
W1  Scaffold + schema
W2  Domain + zero-sum invariant + first transfer
W3  Concurrency: 3 strategies, stress test, benchmark comparison
W4  Idempotency (DB-first, optional Redis)
W5  Transactional outbox + Kafka outage test
W6  Audit / reconciliation + one Toxiproxy scenario
W7  Metrics, k6 baseline, ADRs
W8  README, polish, launch  (+ buffer)
```

---

## Week 1 — Scaffold & Schema (6h)
- [x] Gradle project, Java 21, Spring Boot 4.0.x (planned 3.3.x; moved to Boot 4 and Testcontainers 2) (1h)
- [x] Testcontainers for PostgreSQL 16; Redis and Kafka containers added later when needed (1h)
- [x] Flyway baseline; migrations for `accounts`, `journal_entries`, `postings` (BIGINT minor units) (2h)
- [x] GitHub Actions: `./gradlew check` on every PR (1h)
- [x] ArchUnit layering rule (Domain → Application → Infrastructure) (0.5h)
- [x] Checkstyle: small bug-focused ruleset in `config/checkstyle/checkstyle.xml`, zero warnings allowed (0.5h)
- **Proof:** ArchUnit test passes; `LedgerlineApplicationTests` boots PostgreSQL 16 via Testcontainers and asserts Flyway applied the schema.

## Week 2 — Domain, Zero-Sum Invariant, First Transfer (6h)
- [x] Domain: `Account`, `JournalEntry`, `Posting`, `Money` (2h)
- [x] Domain-level zero-sum validation with explicit exception (1h)
- [x] DB-level zero-sum: deferred constraint trigger (1h)
- [x] DB-level immutability: block `UPDATE`/`DELETE` on postings/journal entries (0.5h)
- [x] `TransferService` v1 using ordered pessimistic locks, plus REST endpoint (1.5h)
- **Proof:** `ZeroSumBalanceInvariantIntegrationTest` (runs against real PostgreSQL), `JournalEntryTest`, `TransferServiceTest`. An imbalanced entry fails in the domain AND, when inserted with raw SQL that bypasses Java, is rejected by the deferred trigger at COMMIT. Accounts are funded only through journal entries, so balance always equals the sum of postings.

## Week 3 — Concurrency (6h)
- [x] Strategy interface; implement A (ordered pessimistic locks), B (optimistic + full jitter, max 5 retries), C (atomic conditional update) (2.5h)
- [x] `ConcurrentTransferStressTest`: 500 concurrent transfers on a shared account, plus a 500-vs-funds-for-100 overdraft race; assert final balance equals the sum of committed postings (1.5h)
- [x] Bidirectional deadlock test (A↔B storm), expecting zero `40P01` (1h). Mutation-checked: with lock ordering removed from C, 471/500 transfers deadlocked.
- [x] Small benchmark harness comparing A/B/C: throughput, p99, rejection rate (1h). `./gradlew benchmark` → [`docs/benchmarks/week3-locking-strategies.md`](benchmarks/week3-locking-strategies.md)
- **Proof:** stress and deadlock tests pass for **all three** strategies, and a comparison table with real numbers (two runs, hardware listed) is saved for ADR-002.
- [x] **Decision:** default strategy = A (ordered pessimistic): best tail latency, balance rules in one place. C is the documented upgrade path. See ADR-002.
- **Stretch (only if ahead):** credit-only hot-account striping.

## Week 4 — Idempotency (6h)
- [x] `idempotency_records` migration (`V2`), primary key `(client_id, idempotency_key)` (0.5h)
- [x] DB-first flow: claim with `INSERT ... ON CONFLICT DO NOTHING` in the transfer's own transaction, replay, `422` hash mismatch (2.5h). **Changed:** no `409 in-progress`. An in-flight claim is invisible, so a duplicate waits for the original and then replays (ADR-006).
- [x] Replay with `X-Cache-Lookup: HIT-IDEMPOTENT` (0.5h). **Changed:** the body is rebuilt from the immutable journal entry instead of stored; same bytes, no serialisation layer.
- [ ] Optional Redis fast path (`SET NX EX 60`) with fallback to DB when Redis is down (1.5h). **Deferred** (second item on the cut line); correctness never depends on it.
- [x] Written notes: crash-after-commit and lease-expiry scenarios → ADR-006 failure table (1h)
- **Proof:** `IdempotencyDuplicateHammerTest`: 500 concurrent requests with one key produce exactly 1 journal entry and 499 replays (mutation-checked: fails with the claim disabled). Also passing: mismatched-payload `422`, per-client key scope, failed-then-retried. `TransferApiIdempotencyTest` covers the HTTP contract (400 / 201 + `HIT-IDEMPOTENT` / 422). Redis-down test N/A until the fast path exists.
- Side fix found by the replay test: entity timestamps are truncated to microseconds (`LedgerTime`). PostgreSQL rounds finer values, so the first response showed a time the DB never stored.

## Week 5 — Transactional Outbox (6h)
- [ ] `outbox_events` written in the same transaction as the transfer (1h)
- [ ] Relay worker using `FOR UPDATE SKIP LOCKED` → Kafka producer (2h)
- [ ] Event schema with `event_id`, sequence number and journal entry ID for consumer dedup (1h)
- [ ] Kafka Testcontainer outage test (1.5h)
- [ ] Notes for ADR-007, including why delivery is at-least-once (0.5h)
- **Proof:** `TransactionalOutboxResilienceTest`. With the broker down for 30s during transfers, no events are lost and all are published after recovery.

## Week 6 — Audit & One Chaos Scenario (6h)
- [ ] `reconciliation_checkpoints` + audit job (balance = checkpoint + postings since) (2h)
- [ ] Freeze account and emit drift event on mismatch (1h)
- [ ] `ChaosDriftDetectionTest`: inject drift in sandbox and verify the exact discrepancy is detected (1h)
- [ ] Toxiproxy: connection reset under load; assert no partial commits and zero audit discrepancies afterwards (1.5h)
- [ ] Simple CSV settlement matching (Matched / Unmatched / Amount mismatch), only if time allows (0.5h, otherwise defer)
- **Proof:** drift test and Toxiproxy reset test both pass.

## Week 7 — Metrics, Benchmark, ADRs (6h)
- [ ] Micrometer metrics (transfer duration, retries, idempotency hits/misses, audit discrepancies) + MDC fields (1.5h)
- [ ] k6 steady-state scenario; run and record real results (1.5h)
- [x] ADR-001 Double-entry vs single-entry (0.5h)
- [x] ADR-002 Locking strategies, using Week 3 data (1h)
- [ ] ADR-007 Transactional outbox (0.5h)
- [x] ADR-006 Idempotency architecture (1h)
- **Proof:** k6 summary file committed along with the hardware used to run it. The p99 target is set only after the first run.

## Week 8 — Launch (6h, includes buffer)
- [ ] README rewrite: what it is, the failure-mode table (see `feature.md` §9), benchmark chart, quick start (2h)
- [ ] `docker compose up` for ledgerline + Postgres + Redis + Kafka (1h)
- [ ] Architecture diagram (0.5h)
- [ ] Write-up of one real bug I hit and how I fixed it (1h)
- [ ] Tag `v0.1.0`; post on LinkedIn, r/java and relevant communities (1h)
- [ ] Buffer for anything that slipped (0.5h)
- **Proof:** a stranger can clone the repo, run one command and see the system work.

---

## Cut Line (if I fall behind)
Drop in this order: CSV settlement matching → Redis fast path → hot-account striping → k6 scenario beyond steady state → optional Toxiproxy latency scenario. **Never cut:** zero-sum invariant, stress test, idempotency hammer test, outbox outage test, ADR-002.

## Definition of Done for v0.1
- [ ] All proof tests pass in CI
- [ ] README failure-mode table links to the actual tests
- [ ] Benchmark numbers are measured, reproducible and carry hardware specs
- [ ] Four ADRs written
- [ ] I can explain every design decision without notes (practice this in Week 8)

## Interview Prep Checklist
- [ ] Why ordered locks prevent deadlocks
- [ ] Optimistic vs pessimistic: which won in my benchmark and why
- [ ] Why delivery is at-least-once, and how consumers deduplicate
- [ ] What happens if Redis fails or a lease expires mid-request
- [ ] Why a deferred constraint trigger is needed for zero-sum
- [ ] One bug I hit and the fix

---

## Weekly Progress Log

| Week | Target | Status | Key Deliverable | Verified By |
|---|---|---|---|---|
| 1 | Scaffold + schema | **COMPLETED** | Boot + Flyway + CI + ArchUnit | ArchUnit / `LedgerlineApplicationTests` |
| 2 | Domain + invariant | **COMPLETED** | Zero-sum enforced in code and DB | `ZeroSumBalanceInvariantIntegrationTest` / `JournalEntryTest` |
| 3 | Concurrency | **COMPLETED** (default = A) | 3 strategies + benchmark table | `ConcurrentTransferStressTest` / `BidirectionalDeadlockTest` |
| 4 | Idempotency | **COMPLETED** (Redis fast path deferred) | DB-first idempotency | `IdempotencyDuplicateHammerTest` / `TransferApiIdempotencyTest` |
| 5 | Outbox | **READY TO START** | Outbox + Kafka relay | `TransactionalOutboxResilienceTest` |
| 6 | Audit + chaos | Scheduled | Drift detection + Toxiproxy reset | `ChaosDriftDetectionTest` |
| 7 | Metrics + ADRs | Scheduled | k6 results + remaining ADRs | k6 summary |
| 8 | Launch | Scheduled | v0.1.0 release | Fresh-clone run |