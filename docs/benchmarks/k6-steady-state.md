# k6 steady-state baseline (HTTP, end to end)

Measured 2026-10-20 with [perf/k6/steady-state.js](../../perf/k6/steady-state.js). Raw output, summary JSON and server-side metric snapshots are in [perf/k6/results/](../../perf/k6/results/).

## Hardware and setup

| | |
|---|---|
| Host | Windows 11 laptop, AMD Ryzen 7 7445HS (6 cores / 12 threads), 15.2 GB RAM |
| App | `./gradlew bootRun` on the host, Java 21 (Temurin 21.0.10), default Hikari pool (10), default strategy A |
| PostgreSQL 16 + Kafka 3.8 | `docker compose` (Docker Desktop VM: 12 CPUs, 7.9 GB) |
| Load generator | `grafana/k6:0.54.0` in Docker, calling `host.docker.internal:8080` |
| Background work during the run | Outbox relay publishing every event to Kafka; audit every 10 s (checkpoint lag 5 s) |

## Scenario

* **Setup:** create 1 equity + 50 asset accounts over the API, and fund each one with 10,000,000 through a journal entry.
* **Warm-up:** 15 s at 50 req/s (excluded from thresholds and the numbers below).
* **Steady state:** 60 s at a **constant arrival rate of 200 req/s** (open model, so slow responses don't slow the arrivals and hide latency).
* Each request is a transfer between two random accounts. **5% re-send an earlier key and body** (exercising idempotent replay under load).

## Results (steady phase, 12,001 requests per run)

| Run | Threshold | p50 | p90 | p95 | **p99** | max | Failed | Dropped | Replays |
|---|---|---|---|---|---|---|---|---|---|
| 1 | none (measuring) | 14.2 ms | 18.0 ms | 19.8 ms | **25.3 ms** | 3.7 s | 0 | 0 | 597 |
| 2 | `p(99) < 50 ms` ✅ | 14.4 ms | 18.7 ms | 20.8 ms | **28.5 ms** | 3.7 s | 0 | 0 | 606 |

**p99 target: 50 ms**, set *after* run 1 (tracker rule 4) at ~2× the measured p99 for headroom. Run 2 enforced it, and it passed. All correctness thresholds passed in both runs: steady-phase failure rate < 0.1% (actual 0%), checks > 99.9% (actual 100%), zero dropped iterations.

## Server-side cross-check (Micrometer, `/actuator/prometheus`)

Cumulative over both runs (setup + warm-up + steady):

| Metric | Value | Cross-check |
|---|---|---|
| `ledger_idempotency_misses_total` | 36,550 | = journal entries in the DB (36,550) |
| `ledger_idempotency_hits_total` | 1,854 | replays across the three k6 executions (651 + 597 + 606) |
| `ledger_outbox_published_total` | 36,550 | = every committed transfer reached Kafka |
| `ledger_outbox_backlog` / `publish_failures` | 0 / 0 | |
| `ledger_audit_discrepancies` | 0 | audit ran every 10 s during load |
| `ledger_transfer_duration` (histogram) | p50 ≤ 14.0 ms, p95 ≤ 22.4 ms, p99 ≤ 28.0 ms | none > 1 s, one > 250 ms |

Mean committed-transfer time inside the service was 14.8 ms, against a 16.8 ms client-side mean, so about 2 ms is HTTP and the network hop.

## Ledger invariants after the load (SQL on the live database)

| Check | Result |
|---|---|
| Journal entries not zero-sum or with < 2 legs | 0 |
| Duplicate idempotency references | 0 |
| Accounts whose balance ≠ sum of postings | 0 |
| Σ asset balances − Σ equity balances | 0 |
| Unpublished outbox events / frozen accounts | 0 / 0 |

## Anomalies, reported rather than hidden

* **max = 3.7 s in both runs, but not in the service.** The service histogram shows no transfer above 1 s. The stall is on the client → `host.docker.internal` network path (Docker Desktop on Windows).
* **2 `dial: i/o timeout` errors during warm-up** in one execution: the TCP connection from the k6 container to the host never opened, so no request reached the app. The same execution had 0 failures in steady state. An earlier, discarded execution (before the steady-phase sub-metric was added) had 0 failures.
* Both anomalies point at the Docker Desktop networking layer. A Linux host, or k6 running natively, should remove them. The p99 numbers are unaffected.

## How to reproduce

```bash
docker compose up -d
./gradlew bootRun --args='--spring.datasource.url=jdbc:postgresql://localhost:5432/ledgerline --spring.datasource.username=ledgerline --spring.datasource.password=ledgerline_secret --ledgerline.audit.interval-ms=10000 --ledgerline.audit.checkpoint-lag=5s'
docker run --rm -v "$PWD/perf/k6:/scripts" -e BASE_URL=http://host.docker.internal:8080 -e P99_MS=50 \
  grafana/k6:0.54.0 run --summary-export /scripts/results/summary.json /scripts/steady-state.js
curl -s localhost:8080/actuator/prometheus | grep '^ledger_'
```

---

## Run 3 — after API-key auth, ownership and multi-currency (v0.1.0 hardening, 2026-10-20 evening)

Same scenario and setup, now authenticated (the admin issues a key in `setup()`; every request carries `Authorization: Bearer`).

| Run | Threshold | p50 | p90 | p95 | **p99** | max | Failed | Dropped |
|---|---|---|---|---|---|---|---|---|
| 3 | `p(99) < 50 ms` | 23.9 ms | 35.1 ms | 42.8 ms | **74.4 ms ❌** | 246 ms | 0 | 0 |

**Correctness held:** 0 failures, 0 dropped, 667 replays = 667 idempotency hits, every committed transfer published (backlog 0), 0 audit discrepancies. **The latency target failed**, and it was not loosened to make it pass.

### Investigation

| Experiment | Result | Conclusion |
|---|---|---|
| Service-side histogram vs HTTP total | service p50 22.4 ms / p99 61.5 ms | The time is inside the service (DB work), not the security filter or HTTP |
| Old (single-sum) zero-sum trigger swapped into the live DB, same load | p50 24.2 ms, p99 81 ms | The new per-currency trigger is **not** the cause |
| Hikari pool 10 → 20 | p99 193 ms (worse) | Not pool starvation; more concurrency hurts, which suggests a CPU-bound host |
| Control: in-process locking benchmark (no HTTP, no auth, no ownership), old vs new trigger back to back | A low-contention 818 vs 883 tx/s; **both ~25–30% below the 1,156 tx/s measured earlier the same day** | The machine itself was slower: the host CPU was at ~43% load from other applications before any test started |

**Conclusion:** the regression is dominated by the measurement environment, not by the new code. The new code adds two indexed lookups per request (API key, ownership), which can't account for +10 ms. Still, the published p99 target is not met on this run.

**To do (owner):** re-run on a quiet machine (or a Linux host). If p99 is still over 50 ms, add a short-TTL cache for API-key lookups (ADR-011 consequence) and re-measure.

Raw data: `perf/k6/results/run3-auth-*`.
