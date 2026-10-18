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

In [TransferService.java](file:///c:/dungeon/ledgerline/src/main/java/com/ledgerline/application/service/TransferService.java):
```java
List<UUID> sortedAccountIds = request.legs().stream()
        .map(TransferLegRequest::accountId)
        .distinct()
        .sorted()
        .toList();

List<Account> lockedAccounts = accountRepository.findByIdInOrderByIdAscForUpdate(sortedAccountIds);
```

Because both Thread 1 and Thread 2 are forced to lock Account A *before* Account B, whichever thread acquires Account A first will proceed to Account B without blocking, while the second thread waits cleanly for Account A. Circular wait becomes mathematically impossible.

### The Java `UUID.compareTo` Gotcha
A crucial real-world trap encountered during testing:
`java.util.UUID.compareTo` compares the two internal `long` fields as **signed 64-bit integers**:
```java
// If mostSignificantBits has the high bit set (0x80000000... to 0xFFFFFFFF...),
// Java evaluates it as a NEGATIVE number!
```
Therefore, `ffffffff-ffff-ffff-ffff-ffffffffffff` sorts *before* `00000000-0000-0000-0000-000000000001` in Java's in-memory sort! Both your application sorting and repository ordering must remain synchronized to preserve the deadlock guarantee.

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
In [V1__init_ledger_schema.sql](file:///c:/dungeon/ledgerline/src/main/resources/db/migration/V1__init_ledger_schema.sql):
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
* The `TransferService` (Application layer) needs to load accounts and save entries.
* Instead of importing `org.springframework.data.jpa.repository.JpaRepository`, it depends on clean interface ports in `com.ledgerline.domain.repository`:
  * `AccountRepository`
  * `JournalEntryRepository`
* In `com.ledgerline.infrastructure.persistence`, `JpaAccountRepository` implements the domain interface while extending Spring Data's `JpaRepository`.
* **ArchUnit Verification**: [LayerArchitectureTest.java](file:///c:/dungeon/ledgerline/src/test/java/com/ledgerline/architecture/LayerArchitectureTest.java) executes automated static analysis on every build to guarantee that no class in `domain` or `application` ever imports anything from `infrastructure`.

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
