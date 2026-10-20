# ledgerline — Engineering Learning & Architectural Guide

Welcome to the comprehensive engineering guide for **`ledgerline`**. This document breaks down the mental models, distributed systems principles, concurrency patterns, and financial data integrity invariants implemented in this codebase.

---

## 1. Core Mental Models of Financial Systems

### Why Single-Entry Systems Fail
In a junior implementation of a payments system, a transfer from Alice to Bob is often modeled as two separate SQL updates:
```sql
UPDATE accounts SET balance = balance - 100 WHERE id = 'alice';
UPDATE accounts SET balance = balance + 100 WHERE id = 'bob';
```
While simple, this naive single-entry approach suffers from fatal flaws in production:
1. **Orphaned State on Partial Failure**: If the application server crashes, the database connection drops, or a constraint error occurs between statement 1 and 2, \$100 vanishes from Alice without ever reaching Bob.
2. **Zero Auditability**: If Alice asks *"Why is my balance \$400 instead of \$500?"*, an update query overwrites the prior state in-place. You cannot reconstruct which transaction caused the change without scraping raw database binlogs.
3. **No Invariant Proof**: There is no mathematical constraint preventing a bug from minting or destroying money.

### The Double-Entry Guarantee
Invented by merchant guilds in 14th-century Venice, **double-entry bookkeeping** guarantees that every transaction is zero-sum:
$$\sum \text{Debits} = \sum \text{Credits}$$

In `ledgerline`, money is never updated directly in isolation. Instead, every money movement creates an immutable **`JournalEntry`** containing two or more **`Postings`** (legs).
* Money never appears or disappears; it only shifts across accounts.
* An imbalanced transfer is mathematically impossible to commit.

### Normal Balance Sides by Account Type
In financial accounting, accounts fall into 5 fundamental categories, each with a natural "normal balance side":

| Account Type | Normal Balance | What increases it? | What decreases it? | Example |
|---|---|---|---|---|
| **ASSET** | **DEBIT** | Debit ($+$) | Credit ($-$) | Customer bank balance, cash reserve |
| **LIABILITY** | **CREDIT** | Credit ($+$) | Debit ($-$) | Merchant escrow balance, accounts payable |
| **EQUITY** | **CREDIT** | Credit ($+$) | Debit ($-$) | Retained earnings, share capital |
| **REVENUE** | **CREDIT** | Credit ($+$) | Debit ($-$) | Platform transaction fees earned |
| **EXPENSE** | **DEBIT** | Debit ($+$) | Credit ($-$) | Payment processing fees paid to Stripe |

#### Example: Customer Transfers \$50 to Merchant with \$2 Platform Fee
* **Customer Asset Account**: `CREDIT $50` (Decreases customer asset by \$50)
* **Merchant Asset Account**: `DEBIT $48` (Increases merchant asset by \$48)
* **Platform Fee Revenue Account**: `DEBIT $2` (Wait: if platform bank account receives cash, Platform Asset: `DEBIT $2`; if fee liability/revenue, normal accounting records Revenue: `CREDIT $2` offset by Merchant receiving \$48 Net).
* Sum of Debits (\$48 + \$2) = Sum of Credits (\$50). The journal entry is in perfect equilibrium.

---

## 2. Minor Units vs. Floating-Point Pitfalls

### The IEEE 754 Floating-Point Hazard
In software engineering, floating-point numbers (`float`, `double`, `Float64`) represent numbers using binary fractions (base 2). Because base 10 fractions like $0.1$ or $0.01$ cannot be represented precisely in base 2, arithmetic introduces minute rounding inaccuracies:

```java
double a = 0.1;
double b = 0.2;
System.out.println(a + b); // Output: 0.30000000000000004 !
```

In high-volume financial ledgers executing millions of transfers, fractions of a cent compound rapidly into significant discrepancies, causing balance reconciliations to fail and triggering regulatory audits.

### The Minor Units Standard (BIGINT)
`ledgerline` strictly enforces the **minor units pattern**:
* All monetary values are represented as integers (`long` in Java, `BIGINT` in PostgreSQL).
* Amounts represent the smallest un-divisible minor unit of the currency:
  * For USD, EUR, GBP: 1 unit = 1 cent ($10.50 \rightarrow 1050$).
  * For JPY, KRW: 1 unit = 1 yen ($1000 \rightarrow 1000$).
* Integer arithmetic is deterministic, exact, and never subject to rounding drift:
  $$\text{1000 cents} + \text{2000 cents} = \text{3000 cents (Exact)}$$

---

## 3. Concurrency, Deadlocks & Coffman Conditions

### The Bidirectional Deadlock Storm (A $\leftrightarrow$ B)
Imagine two users simultaneously initiate transfers:
* **Thread 1**: Transfers \$100 from Account A to Account B.
* **Thread 2**: Transfers \$50 from Account B to Account A.

If Thread 1 locks Account A and waits for Account B, while Thread 2 simultaneously locks Account B and waits for Account A, neither thread can proceed. PostgreSQL detects this circular wait condition and aborts one transaction with error:
`40P01: deadlock detected`.

### The 4 Coffman Conditions for Deadlocks
For a deadlock to occur in any concurrent system, all 4 Coffman conditions must hold simultaneously:
1. **Mutual Exclusion**: Resources cannot be shared simultaneously (row locks).
2. **Hold and Wait**: A thread holds one resource while requesting another.
3. **No Preemption**: Resources cannot be forcibly revoked from a thread.
4. **Circular Wait**: Thread 1 waits for Thread 2, and Thread 2 waits for Thread 1.

### Breaking Circular Wait via Canonical Resource Ordering
To eliminate deadlocks, we don't need distributed coordination or complicated lock managers. We simply break **Condition #4 (Circular Wait)** by enforcing a global resource ordering rule:

> **Rule**: When acquiring locks on multiple accounts, always acquire them in **ascending UUID order**, regardless of transfer direction.

In [TransferPlan.java](../src/main/java/com/ledgerline/application/strategy/TransferPlan.java) and [OrderedPessimisticStrategy.java](../src/main/java/com/ledgerline/application/strategy/OrderedPessimisticStrategy.java):
```java
List<UUID> ids = request.legs().stream()
        .map(TransferLegRequest::accountId)
        .distinct()
        .sorted(LOCK_ORDER)          // UUID::toString, same order as PostgreSQL
        .toList();

accountRepository.findByIdInOrderByIdAscForUpdate(ids);   // one statement: ORDER BY id ... FOR UPDATE
```

Because both Thread 1 and Thread 2 are forced to lock Account A *before* Account B, whichever thread acquires Account A first will proceed to Account B without blocking, while the second thread waits cleanly for Account A. Circular wait becomes mathematically impossible.

### The Java `UUID.compareTo` Gotcha
A crucial real-world trap encountered during testing:
`java.util.UUID.compareTo` compares the two internal `long` fields as **signed 64-bit integers**:
```java
// If mostSignificantBits has the high bit set (0x80000000... to 0xFFFFFFFF...),
// Java evaluates it as a NEGATIVE number!
```
Therefore, `ffffffff-ffff-ffff-ffff-ffffffffffff` sorts *before* `00000000-0000-0000-0000-000000000001` in Java's in-memory sort, while PostgreSQL (unsigned bytes) sorts it last.

When does that matter? Only when **Java's order is the lock order**:
* Strategy A locks every row in *one* SQL statement, so PostgreSQL's `ORDER BY` decides and the Java sort is irrelevant.
* Strategy C issues one `UPDATE` per account, so Java's iteration order *is* the lock order. If two transactions sorted differently, they could deadlock.

The fix is `Comparator.comparing(UUID::toString)`: canonical lowercase hex compares character by character exactly like unsigned bytes, so Java and PostgreSQL agree.

---

## 4. Defense-in-Depth Invariant Enforcement

### Tier 1: Application Domain Invariants
Fast failure saves database resources. If a caller submits an imbalanced payload, `JournalEntry.create(...)` immediately throws an `ImbalancedJournalEntryException` before acquiring locks or initiating database roundtrips.

### Tier 2: PostgreSQL Deferred Constraint Triggers
Why can't we rely solely on standard PostgreSQL row triggers (`AFTER INSERT FOR EACH ROW`)?
In a relational database, multi-leg postings must be inserted one row at a time.
1. Application executes `INSERT INTO postings (account_id, 'DEBIT', 100)`.
2. At this moment, the journal entry has only 1 leg and net balance is $+100$ (imbalanced). A standard row trigger checking balance would immediately throw an error and abort!
3. Only after `INSERT INTO postings (account_id, 'CREDIT', 100)` does the entry balance out to zero.

### The Solution: `DEFERRABLE INITIALLY DEFERRED`
In [V1__init_ledger_schema.sql](../src/main/resources/db/migration/V1__init_ledger_schema.sql):
```sql
CREATE CONSTRAINT TRIGGER trg_postings_zero_sum
AFTER INSERT ON postings
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION check_journal_entry_zero_sum();
```
By declaring the trigger as a `CONSTRAINT TRIGGER` that is `DEFERRED`:
* PostgreSQL allows the application to insert all individual legs during the transaction.
* When the application calls `commit()`, PostgreSQL halts execution and evaluates the trigger function for all inserted rows.
* If $\sum \text{Debits} \ne \sum \text{Credits}$, the transaction is forcibly aborted and rolled back.

---

## 5. Balance as a Cached Projection

In modern ledger architectures, there is a strict separation between **immutable facts** and **projected state**:
* **Immutable Facts**: `postings` and `journal_entries`. These are append-only. No `UPDATE` or `DELETE` is ever allowed (enforced by the PostgreSQL immutability trigger `prevent_financial_record_modifications`).
* **Cached Projection**: `accounts.balance`. This is a materialized view of the sum of postings, stored for fast $O(1)$ read performance.

### Reconciliation Worker
Because projections are updated concurrently, distributed systems employ an asynchronous reconciliation auditor (planned for Week 6) that continuously cross-references:
$$\text{Account.balance} \stackrel{?}{=} \text{Last Checkpoint Balance} + \sum \text{Uncheckpointed Postings}$$
If a discrepancy is detected, the account is immediately flagged as drifted and frozen.

---

## 6. Hexagonal Clean Layering & ArchUnit

To prevent business logic from becoming tangled with framework code or database schemas, `ledgerline` enforces Hexagonal Architecture (Ports and Adapters):

```
       Infrastructure (Web Controllers, REST APIs)
                           │
                           ▼
       Application (Use-Case Services, Orchestration)
                           │
                           ▼
       Domain (Entities, Value Objects, Domain Exceptions, Repository Ports)
                           ▲
                           │
       Infrastructure (JPA Repositories, Database Adapters)
```

### Dependency Inversion in Action
* The strategies and `TransferService` (Application layer) need to load accounts, save entries and claim idempotency keys.
* Instead of importing `org.springframework.data.jpa.repository.JpaRepository`, it depends on clean interface ports in `com.ledgerline.domain.repository`:
  * `AccountRepository`
  * `JournalEntryRepository`
  * `IdempotencyRepository`
* In `com.ledgerline.infrastructure.persistence`, `JpaAccountRepository` implements the domain interface while extending Spring Data's `JpaRepository`, and `JdbcIdempotencyRepository` implements its port with plain JDBC (it needs `ON CONFLICT`, which JPA cannot express). The application never knows which technology is behind a port.
* **ArchUnit Verification**: [LayerArchitectureTest.java](../src/test/java/com/ledgerline/architecture/LayerArchitectureTest.java) executes automated static analysis on every build to guarantee that no class in `domain` or `application` ever imports anything from `infrastructure`, that the domain never imports Spring, and that the application never imports JPA, Hibernate, JDBC, Spring Data or HTTP classes.

---

## 7. Key Takeaways for Senior Engineering Interviews

1. **How do you prevent deadlocks in bidirectional transfers?**
   * *"We enforce canonical resource ordering: sorting account IDs in ascending order prior to taking `PESSIMISTIC_WRITE` locks (`SELECT ... FOR UPDATE`), which mathematically eliminates Coffman's circular wait condition."*
2. **Why use double-entry instead of updating balances directly?**
   * *"Double-entry guarantees mathematical conservation of funds ($\sum \text{Debits} = \sum \text{Credits}$), prevents lost updates, and produces a complete, append-only immutable audit trail."*
3. **How do you validate zero-sum at the database level if rows are inserted sequentially?**
   * *"We use a PostgreSQL `CONSTRAINT TRIGGER ... DEFERRABLE INITIALLY DEFERRED`. This permits row-by-row insertion within the transaction but enforces the zero-sum invariant across all legs at transaction commit time."*
4. **Why use integer minor units instead of floating point?**
   * *"IEEE 754 binary floating-point representation causes inexact fractions (e.g. $0.1 + 0.2 \ne 0.3$), leading to compounding balance drift. Storing cents as `BIGINT` ensures exact, deterministic arithmetic."*

---

## 8. Week 3: Three Ways to Stop Lost Updates

All three strategies solve the same problem: two transfers read the hot account's balance (say 1000), each subtracts 100, and each writes back 900. One withdrawal vanishes. The difference between the strategies is **when** each one finds out about the conflict.

| | A: Ordered pessimistic | B: Optimistic + jitter | C: Atomic conditional |
|---|---|---|---|
| When conflict is detected | Before reading (wait for the lock) | At commit (version mismatch) | Inside the UPDATE (DB re-checks the row) |
| What the loser does | Waits | Throws away all work and retries | Waits, then the guard re-runs on fresh data |
| Who does the arithmetic | Java | Java | PostgreSQL |
| Hot account result (Week 3 data) | ~150 tx/s, 0% rejected | ~90 tx/s, **~62% rejected** | ~215 tx/s, 0% rejected, worst p99 |

### Why B collapses on a hot row
With 32 writers on one row, each round has 1 winner and 31 losers. Each loser re-reads, recomputes and collides again. Retries add load at exactly the moment the system is saturated. Optimistic locking is a bet that conflicts are rare. On a hot account they are constant, so the bet loses. On random pairs of 100 accounts it does better (~1% rejected), but its p99 is still 2–3x the others'.

### Why the retry loop must sit outside the transaction
When a version conflict happens, Spring marks the transaction rollback-only. Retrying inside the same `@Transactional` method would reuse a doomed transaction and the same stale persistence context. The retry loop therefore sits in `TransferService`, *around* a `TransactionTemplate`: each attempt gets a fresh transaction and fresh reads. Since Week 4 that transaction also contains the idempotency claim, so a retry re-runs claim + transfer together (see §9).

### Why full jitter
Fixed backoff (always wait 10 ms) makes every loser wake up together and collide again. Exponential backoff spreads the rounds out, but losers of the same round still retry together. **Full jitter** sleeps a uniformly random time in `[0, min(cap, base·2^attempt)]`, which breaks that lockstep. (AWS Architecture Blog, "Exponential Backoff and Jitter".)

### Why C needs no retries
`UPDATE accounts SET balance = balance + :delta WHERE id = :id AND (balance + :delta >= 0 OR NOT disallow_negative_balance)`

Under READ COMMITTED, if another transaction holds the row, PostgreSQL waits for it to commit and then **re-evaluates the WHERE clause against the new row version**. The overdraft guard therefore always sees the latest balance, which is why the overdraft race test gets exactly 100 successes out of 500 attempts. Multi-leg atomicity still comes from the transaction: if leg 2's guard fails, leg 1's UPDATE rolls back.

### Why C has the worst tail latency
C holds each row lock for less time (one UPDATE, then inserts and commit), so throughput is higher. But under a hot row the waiters queue on the lock, and some transactions sit behind long chains of waiters. Mean and median improve while p99 gets worse. That is the throughput-vs-tail-latency trade-off ADR-002 decided **in favour of A**: predictable p99, and every balance rule stays in `Account.applyPosting` instead of being duplicated in SQL.

### Lock order: three strategies, three mechanisms
- **A:** one statement locks every row, and PostgreSQL's `ORDER BY id` decides the order.
- **C:** one UPDATE per row, so **Java's iteration order is the lock order**. It must match across all transactions, hence `TransferPlan.LOCK_ORDER`.
- **B:** Hibernate issues the UPDATEs at flush time. `hibernate.order_updates=true` sorts them by primary key.

The evidence that this matters: with C's ordering removed, the A↔B storm produced **471 deadlocks out of 500** transfers.

### Interview one-liner
"Pessimistic waits before it reads, optimistic pays after it writes, and a conditional update makes the database do the read-check-write in one step. On a hot account I measured optimistic rejecting about 60% of requests, while the conditional update had roughly 1.4x the throughput of pessimistic but a worse p99."

---

## 9. Week 4: Idempotency, or "Effectively-Once" Without Distributed Locks

### The problem
The client sends "move $25", the server commits, and the response is lost (timeout, dropped Wi-Fi, load balancer reset). The client cannot tell "never happened" from "happened, reply lost", so it retries. Without protection, that retry moves money a second time. An **idempotency key** is the client saying "this is the same request as before".

### Why the key is claimed *inside* the transfer transaction
If you record the key in one transaction and do the transfer in another, there is always a gap. Crash between them and either the key is recorded but no money moved (every retry is wrongly refused), or money moved but no key was recorded (the retry moves it again). Putting the claim, the postings and the balance updates in **one** transaction removes the gap: they commit together or vanish together.

### How a concurrent duplicate is handled: `ON CONFLICT` waits
```sql
INSERT INTO idempotency_records (client_id, idempotency_key, request_hash)
VALUES (?, ?, ?) ON CONFLICT (client_id, idempotency_key) DO NOTHING
```
An uncommitted row is invisible to other transactions, but its **unique-index entry is not**. When a duplicate hits that entry, PostgreSQL can't yet tell whether it conflicts (the original might roll back), so it **waits** for the original transaction to finish:
* original commits → the duplicate inserts 0 rows → it reads the record and replays the response
* original rolls back → the duplicate inserts its row and becomes the new owner

This is why the hammer test gets exactly 1 effect and 499 *replays*, not 499 errors.

### Why `ON CONFLICT DO NOTHING` and not "catch the unique violation"
In PostgreSQL, any error inside a transaction **aborts the whole transaction**: every later statement fails with "current transaction is aborted". After a plain `INSERT` hits a duplicate, you could not even `SELECT` the existing record to compare hashes. `ON CONFLICT DO NOTHING` turns the conflict into "0 rows affected", which is not an error.

### Why store a request hash
A client bug could reuse key `abc` for a *different* transfer. Replaying the old answer would silently drop the new request; executing it would break the key's promise. So we fingerprint the request (SHA-256 of a canonical form) and answer `422` if it doesn't match.

### Why only successes are recorded
A failed transfer (e.g. insufficient funds) rolls back everything, including the claim. Retrying the same key later is therefore a real new attempt, which is what a user who just topped up their account expects. "At most one ledger effect" still holds.

### Why the replay is rebuilt instead of stored
Journal entries and postings are immutable (DB triggers enforce it), so `TransferResponse.from(entry)` produces exactly the original body. Storing JSON would add a serialisation layer for no gain. The replay test caught one subtle trap here: the JVM clock on Windows ticks in 100 ns steps, but PostgreSQL stores microseconds and **rounds**. The first response showed `.8898016`, the database stored `.889802`, and the replay disagreed. Entity timestamps are now truncated to microseconds when created (`LedgerTime`).

### Why there is no "lease expiry" problem
Redis-based idempotency uses a lease (`SET NX EX 60`): if the first request takes more than 60 s, the lease expires and a duplicate runs too. Here the "lock" is the original transaction itself. It can't expire while the transaction is alive, and if the process dies, PostgreSQL aborts its transaction and releases the key.

### Interview one-liner
"The idempotency claim is an `INSERT ... ON CONFLICT DO NOTHING` in the same transaction as the postings. Duplicates block on the unique index until the original commits, then replay the result. There's no lease to expire and no gap between recording the key and moving the money, and 500 concurrent duplicates produce exactly one journal entry."
