# Locking strategy benchmark (raw data for ADR-002)

Measured 2026-10-20 with `./gradlew benchmark` ([LockingStrategyBenchmark](../../src/test/java/com/ledgerline/integration/LockingStrategyBenchmark.java)).
Two back-to-back runs on the same machine. "Rejected" = strategy B gave up after 5 retries (would be HTTP 409 + Retry-After).

## Environment
- Workers: 32 threads, Hikari pool 32, 3000 measured transfers per row (+300 warm-up)
- CPU cores visible to JVM: 12, max heap: 512 MB
- OS: Windows 11 10.0 (amd64)
- JVM: Eclipse Adoptium 21.0.10
- Database: PostgreSQL 16.15 (Debian 16.15-1.pgdg13+2) on x86_64-pc-linux-gnu, compiled by gcc (Debian 14.2.0-19) 14.2.0, 64-bit (Testcontainers; on Windows/macOS this runs inside the Docker VM)

## Run 1

| Scenario | Strategy | Succeeded | Rejected | Rejection % | Throughput (tx/s) | p50 ms | p95 ms | p99 ms | max ms |
|---|---|---|---|---|---|---|---|---|---|
| Hot account (1 source -> 10 sinks) | ORDERED_PESSIMISTIC | 3000 | 0 | 0.0 | 151 | 218.5 | 346.1 | 495.5 | 800.8 |
| Hot account (1 source -> 10 sinks) | OPTIMISTIC_LOCKING | 1164 | 1836 | 61.2 | 89 | 143.1 | 280.3 | 359.7 | 584.4 |
| Hot account (1 source -> 10 sinks) | ATOMIC_CONDITIONAL | 3000 | 0 | 0.0 | 221 | 98.7 | 430.3 | 666.3 | 1167.1 |
| Low contention (random pairs of 100) | ORDERED_PESSIMISTIC | 3000 | 0 | 0.0 | 1556 | 16.1 | 46.2 | 65.1 | 115.5 |
| Low contention (random pairs of 100) | OPTIMISTIC_LOCKING | 2964 | 36 | 1.2 | 1037 | 16.6 | 88.6 | 154.5 | 216.0 |
| Low contention (random pairs of 100) | ATOMIC_CONDITIONAL | 3000 | 0 | 0.0 | 1795 | 14.5 | 35.3 | 55.1 | 163.2 |

## Run 2

| Scenario | Strategy | Succeeded | Rejected | Rejection % | Throughput (tx/s) | p50 ms | p95 ms | p99 ms | max ms |
|---|---|---|---|---|---|---|---|---|---|
| Hot account (1 source -> 10 sinks) | ORDERED_PESSIMISTIC | 3000 | 0 | 0.0 | 149 | 227.0 | 346.1 | 400.2 | 678.9 |
| Hot account (1 source -> 10 sinks) | OPTIMISTIC_LOCKING | 1079 | 1921 | 64.0 | 92 | 137.3 | 219.2 | 251.0 | 320.2 |
| Hot account (1 source -> 10 sinks) | ATOMIC_CONDITIONAL | 3000 | 0 | 0.0 | 211 | 83.9 | 486.6 | 756.4 | 1197.5 |
| Low contention (random pairs of 100) | ORDERED_PESSIMISTIC | 3000 | 0 | 0.0 | 1515 | 16.3 | 49.2 | 79.0 | 124.7 |
| Low contention (random pairs of 100) | OPTIMISTIC_LOCKING | 2972 | 28 | 0.9 | 1017 | 18.3 | 88.8 | 163.0 | 241.9 |
| Low contention (random pairs of 100) | ATOMIC_CONDITIONAL | 3000 | 0 | 0.0 | 1275 | 19.7 | 55.4 | 93.0 | 217.3 |

## Reading the numbers

- **Hot account (both runs agree):** C (atomic conditional) has ~1.4x the throughput of A (211–221 vs 149–151 tx/s)
  and a lower median, but a **worse tail** (p99 666–756 ms vs 400–495 ms). B (optimistic) collapses: 61–64% of
  requests rejected and the lowest throughput, because every concurrent writer of the hot row but one loses each round.
- **Low contention:** A and C swapped places between runs (1556/1795 then 1515/1275 tx/s), so the difference is
  within noise; no winner can be claimed. B still rejects ~1% and has 2–3x the p99 of the others.
- **Caveats:** single laptop, PostgreSQL inside the Docker Desktop VM (commit fsync goes through the VM, which
  caps hot-row throughput), closed-loop load with 32 workers. Absolute numbers will differ elsewhere; the
  ranking on the hot account is the finding.

---

## Re-run after Weeks 4–6 (2026-10-20)

Same harness and machine. What changed in the code since runs 1–2: the optimistic retry loop moved from strategy B into `TransferService` (around the whole transaction), and every transfer now also writes an outbox event (JSON) inside its transaction.

### Run 3

| Scenario | Strategy | Succeeded | Rejected | Rejection % | Throughput (tx/s) | p50 ms | p95 ms | p99 ms | max ms |
|---|---|---|---|---|---|---|---|---|---|
| Hot account (1 source -> 10 sinks) | ORDERED_PESSIMISTIC | 3000 | 0 | 0.0 | 102 | 321.9 | 533.7 | 717.8 | 1202.7 |
| Hot account (1 source -> 10 sinks) | OPTIMISTIC_LOCKING | 1109 | 1891 | 63.0 | 64 | 207.7 | 359.1 | 444.8 | 605.0 |
| Hot account (1 source -> 10 sinks) | ATOMIC_CONDITIONAL | 3000 | 0 | 0.0 | 128 | 68.7 | 1790.7 | 3130.9 | 6621.1 |
| Low contention (random pairs of 100) | ORDERED_PESSIMISTIC | 3000 | 0 | 0.0 | 1160 | 21.4 | 62.6 | 87.2 | 186.5 |
| Low contention (random pairs of 100) | OPTIMISTIC_LOCKING | 2969 | 31 | 1.0 | 806 | 19.9 | 112.6 | 219.4 | 308.5 |
| Low contention (random pairs of 100) | ATOMIC_CONDITIONAL | 3000 | 0 | 0.0 | 1284 | 20.3 | 51.8 | 78.6 | 151.4 |

### Run 4

| Scenario | Strategy | Succeeded | Rejected | Rejection % | Throughput (tx/s) | p50 ms | p95 ms | p99 ms | max ms |
|---|---|---|---|---|---|---|---|---|---|
| Hot account (1 source -> 10 sinks) | ORDERED_PESSIMISTIC | 3000 | 0 | 0.0 | 101 | 296.1 | 539.2 | 879.5 | 4028.0 |
| Hot account (1 source -> 10 sinks) | OPTIMISTIC_LOCKING | 1192 | 1808 | 60.3 | 63 | 219.1 | 411.5 | 535.7 | 872.8 |
| Hot account (1 source -> 10 sinks) | ATOMIC_CONDITIONAL | 3000 | 0 | 0.0 | 137 | 116.6 | 835.6 | 1661.8 | 3278.3 |
| Low contention (random pairs of 100) | ORDERED_PESSIMISTIC | 3000 | 0 | 0.0 | 1156 | 21.5 | 63.3 | 90.4 | 148.5 |
| Low contention (random pairs of 100) | OPTIMISTIC_LOCKING | 2966 | 34 | 1.1 | 703 | 24.3 | 139.3 | 231.9 | 311.7 |
| Low contention (random pairs of 100) | ATOMIC_CONDITIONAL | 3000 | 0 | 0.0 | 1313 | 19.9 | 49.4 | 76.5 | 133.0 |

### What changed, and why

- **Hot-account throughput fell ~33% for every strategy** (A: ~150 → ~101 tx/s). Each transfer now does more work
  while it holds the account lock (outbox INSERT + JSON, one more deferred FK check at commit), and on a hot row
  throughput is 1 / lock-hold-time. Ranking unchanged.
- **C's tail on the hot account got much worse**: p99 1.7–3.1 s (A: 0.7–0.9 s). Lock waiters queue longer behind
  longer transactions, and C's queueing is the least fair.
- **Low contention: C now beats A in both runs** (1284–1313 vs 1156–1160 tx/s, p99 77–79 vs 87–90 ms), about 11%.
  In runs 1–2 the two were within noise.
- **B** still rejects 60–63% on a hot account.
- The ADR-002 decision (default A) stands: the hot-account tail is the deciding risk, and balance rules stay in one
  place. The low-contention gap is now real but small; it is recorded in ADR-002 as the trade-off being accepted.
