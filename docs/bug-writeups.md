# Bugs I hit, and how they were found and fixed

Real bugs from building Ledger Line, written up the way I would explain them in a post-mortem or an interview: symptom → investigation → root cause → fix → what prevents it from coming back.

---

## 1. The API returned a timestamp the database never stored

**Week 4 · found by:** `TransferApiIdempotencyTest.retry_replaysOriginalResponse` · **fix:** `LedgerTime`

### Symptom
The idempotency test sends the same transfer twice and asserts that the replay's body is **byte-for-byte identical** to the original response. It failed. The two bodies were identical except for one field:

```
original : "createdAt":"2026-10-20T07:36:44.889801600Z"
replay   : "createdAt":"2026-10-20T07:36:44.889802Z"
```

### Investigation
* The replay is rebuilt from the stored journal entry (ADR-006), so the replay shows **what the database holds**.
* The original response is built from the in-memory entity right after `save`, so it showed **what Java generated**.
* So the question became: why does Java's value differ from the stored one?

### Root cause
Two precision rules met:
1. `Instant.now()` on Windows ticks in **100 ns** steps (`.8898016`, 7 fractional digits).
2. PostgreSQL `timestamptz` stores **microseconds**, and it **rounds** finer values rather than truncating: `.8898016` → `.889802`.

The first response therefore reported a timestamp that **never existed in the database**. Any client that stored it and later compared it with a read from the API (a reconciliation, a cursor, a cache key) would see a mismatch. Nothing ever errored. On Linux, where the clock usually ticks in microseconds or coarser, the bug wouldn't appear at all.

### Fix
Generate timestamps at the precision the database stores, at the source:

```java
final class LedgerTime {
    static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
```

All entities (`Account`, `JournalEntry`, `Posting`) use `LedgerTime.now()`. The value Java reports is now exactly representable in PostgreSQL, so the database stores it unchanged.

### Why this was the right fix, and alternatives rejected
* *Re-read the entity after commit and return that.* That costs an extra query per transfer to paper over a mismatch that shouldn't exist.
* *Compare timestamps loosely in the test.* That hides a real contract violation: "the response describes what was committed."
* *Make the column `timestamp(6)` with truncation semantics.* PostgreSQL has no truncate-on-store option; the rounding is built in.

### What prevents a regression
The byte-for-byte replay assertion stays. It is the only test that compares a response built from memory with one built from storage, which is exactly where this class of bug lives.

### Lesson
**A response must describe what was stored, not what was intended.** Precision mismatches between a runtime and its database are silent: no exception, just different bytes. The idempotent replay turned out to be an excellent probe, because it forces the system to produce the same answer twice from two different sources.

---

## 2. A Kafka outage caused already-delivered events to be sent again

**Week 5 · found by:** reading the relay's logs after `TransactionalOutboxResilienceTest` *passed* · **fix:** `OutboxRelay`

### Symptom
The 30-second Kafka outage test was green: no events lost. But the logs showed only **one** "Kafka did not ack" warning during the outage and twelve "relay pass failed" warnings. The code was supposed to log per event and keep the rows that had already been acknowledged.

### Root cause
`KafkaTemplate.send()` has **two** ways to fail. Normally it returns a future that fails later, and the relay handled that. But when the producer can't even fetch cluster metadata (broker unreachable), `send()` **throws immediately**. That exception escaped the per-row handling, propagated out of the transaction and **rolled back the whole batch**, including rows Kafka had already acknowledged. Those events would be published a second time.

Delivery was still at-least-once (consumers dedupe on `event_id`), which is why the test passed. But the relay was doing more duplicate work than its own contract promised ("mark the acknowledged prefix").

### Fix
Treat a synchronous `KafkaException` exactly like a missing ack: stop the batch, mark what was acknowledged, commit.

```java
try {
    inFlight.add(kafka.send(row.toRecord()));
} catch (KafkaException e) { // send() can fail synchronously, e.g. no metadata while the broker is down
    break;
}
```

### Lesson
**A green test is not the same as correct behaviour.** The invariant under test (no loss) held, but a secondary property (no needless duplicates) didn't. Reading the logs of a passing chaos test is part of the test.

A related follow-up from the same week: one-at-a-time "send, wait, send" was correct but far too slow to drain a backlog of thousands of events. The relay now pipelines the batch and awaits the acks in order.

---

## 3. A PostgreSQL internal error under contention: "new multixact has more than one updating member"

**v0.1.0 hardening · found by:** `ConcurrentTransferStressTest` (strategy B, hot account), about 1 run in 5 · **fix:** `OptimisticRetryStrategy`

### Symptom
After adding API-key ownership and multi-currency support, one full test run failed. The failure wasn't an assertion about money. Among the expected optimistic-lock conflicts there was a `JpaSystemException`:

```
ERROR: new multixact has more than one updating member: 0 3[8812 (nokeyupd), 8820 (keysh), 8820 (nokeyupd)]
```

It did not reproduce in the next four runs.

### Investigation
* It is a PostgreSQL **internal sanity check** in its row-lock bookkeeping ("multixacts" record several transactions holding locks on one row), not an application error.
* The lock list is the clue. Transaction 8820 held a **key-share** lock (`keysh`) **and** a no-key **update** (`nokeyupd`) on the same account row, while 8812 also updated it.
* Where does a key-share come from? A foreign-key check: inserting a posting locks the referenced account row `FOR KEY SHARE`.
* So 8820 had **inserted postings first and updated the account row second**. That is Hibernate's default flush order (inserts, then updates), and only strategy B reaches it: A locks the row with `FOR UPDATE` before anything else, and C issues its `UPDATE` before saving the entry.

### Root cause
Strategy B's flush order produced "key-share, then update the same row" while another transaction was updating it, a lock combination that occasionally trips PostgreSQL's multixact check.

### Fix
Flush B's version-checked balance `UPDATE`s **before** the postings are inserted (`saveAllAndFlush`). B now owns the row before inserting postings, like A and C. As a bonus, it detects conflicts earlier (before writing postings), and the ADR-008 cursor argument ("a posting only commits if its transaction owned the account row from INSERT to COMMIT") is now true for B by construction, not only via the version check.

### Evidence, stated honestly
Before the fix: 1 failure in about 5 runs. After: 0 in 6 consecutive stress + deadlock runs, plus every full build since. At that base rate, 6 clean runs alone would happen about 26% of the time by chance, so the fix rests mainly on the lock analysis, with the runs as supporting evidence.

### Lesson
**A rare failure is still a failure: read the lock state, not just the stack trace.** The single line listing lock modes (`keysh`, `nokeyupd`) pointed straight at the ordering problem.

---

## Smaller ones worth knowing

| Bug | Found by | Fix |
|---|---|---|
| Tests couldn't connect to PostgreSQL: `invalid value for parameter "TimeZone": "Asia/Calcutta"` | First Testcontainers run on Windows | The JVM reports a legacy zone alias that the Debian `postgres:16` image no longer ships. Tests run with `-Duser.timezone=UTC`. |
| Money could be created from nothing: `initialBalance` on account creation set a balance with no posting | Week 2 review | Removed. Accounts open at 0 and are funded through a journal entry. |
| The database zero-sum trigger had never actually been exercised | Week 2 review | Raw-JDBC tests that bypass Java validation. |
| My mutation-test regex "validated" 0 Mermaid diagrams on a CRLF file and reported success | Week 8, a suspicious empty output | Normalise line endings, and fail when nothing was checked. |
| The one-command build failed on a slow link: the Gradle wrapper defaults to a 10 s download timeout and 0 retries | Week 8 fresh-clone run | `networkTimeout=60000`, `retries=3`. |
| The demo reported an account's oldest balance as its newest | Fresh-copy walkthrough | Responses are single-line JSON and a greedy `sed` picked the **last** match; the extractor now takes the first. The API test using a real JSON parser was right all along. |
| The database zero-sum trigger summed every leg regardless of currency, so USD +500 / EUR −500 would have "balanced" | Designing multi-currency (ADR-012) | Harmless while every entry had one currency; the trigger now checks per currency (and that each posting matches its account's currency). |
