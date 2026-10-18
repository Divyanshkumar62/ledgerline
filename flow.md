# ledgerline — System Flow & Code Review Guide

> This document is your comprehensive operational blueprint for `ledgerline`. It details how data flows across layers, how concurrency locks are acquired, how zero-sum invariants are enforced in two tiers, and how you should navigate and review the codebase.

---

## 1. Code Review & Navigation Guide (Where to Start)

If you are reviewing or auditing this codebase for the first time, follow this specific reading sequence to understand the system from the inside out:

```
┌─────────────────────────────────────────────────────────────────────────────┐
│ 1. Core Primitives                                                          │
│    Money.java → PostingDirection.java → AccountType.java                    │
└──────────────────────────────────────┬──────────────────────────────────────┘
                                       │
                                       ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ 2. Domain Entities & Invariants                                             │
│    Account.java → Posting.java → JournalEntry.java                          │
└──────────────────────────────────────┬──────────────────────────────────────┘
                                       │
                                       ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ 3. Domain Ports (Decoupled Persistence Contracts)                           │
│    AccountRepository.java → JournalEntryRepository.java                     │
└──────────────────────────────────────┬──────────────────────────────────────┘
                                       │
                                       ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ 4. Application Orchestration                                                │
│    TransferService.java (Canonical UUID sorting + Pessimistic Locking)      │
└──────────────────────────────────────┬──────────────────────────────────────┘
                                       │
                                       ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ 5. Database Schema & Defense-in-Depth Triggers                              │
│    V1__init_ledger_schema.sql (Deferred Zero-Sum + Immutability Triggers)   │
└──────────────────────────────────────┬──────────────────────────────────────┘
                                       │
                                       ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ 6. Invariant & Boundary Verification                                        │
│    JournalEntryTest.java → TransferServiceTest.java → LayerArchitectureTest │
└─────────────────────────────────────────────────────────────────────────────┘
```

### File-by-File Review Walkthrough

1. **[`Money.java`](src/main/java/com/ledgerline/domain/model/Money.java)**:
   * **Why start here**: Understand how monetary amounts are represented. Notice that amounts are strictly stored as `long` integer minor units (e.g., cents, paise) alongside an ISO-4217 currency string. Floating-point numbers (`double`, `float`) are forbidden.
2. **[`PostingDirection.java`](src/main/java/com/ledgerline/domain/model/PostingDirection.java) & [`AccountType.java`](src/main/java/com/ledgerline/domain/model/AccountType.java)**:
   * See how the 5 accounting account types (`ASSET`, `LIABILITY`, `EQUITY`, `REVENUE`, `EXPENSE`) map to their **normal balance sides** (`DEBIT` or `CREDIT`).
   * Inspect `AccountType.calculateSignedDelta()`, which determines whether a posting increases or decreases the projected balance.
3. **[`Account.java`](src/main/java/com/ledgerline/domain/model/Account.java)**:
   * Inspect `applyPosting()`. Notice that `balance` is treated as a cached projection, and the `disallowNegativeBalance` guard runs within the active transaction.
4. **[`JournalEntry.java`](src/main/java/com/ledgerline/domain/model/JournalEntry.java)**:
   * Review `JournalEntry.create()`. This is the **Tier 1 Invariant Gate**. It enforces at least 2 legs, matching currency, and $\sum \text{Debits} == \sum \text{Credits}$. Any imbalance throws `ImbalancedJournalEntryException` before hitting the database.
5. **[`TransferService.java`](src/main/java/com/ledgerline/application/service/TransferService.java)**:
   * **The Core Orchestrator**: Observe lines 44–58. Notice how `TransferService` extracts distinct account IDs and sorts them in ascending order before taking `PESSIMISTIC_WRITE` locks (`SELECT ... FOR UPDATE`). This mathematically breaks Coffman's circular wait condition.
6. **[`V1__init_ledger_schema.sql`](src/main/resources/db/migration/V1__init_ledger_schema.sql)**:
   * **The Tier 2 Invariant Gate**: Review the `check_journal_entry_zero_sum()` function and the `CONSTRAINT TRIGGER ... DEFERRABLE INITIALLY DEFERRED`. Notice how PostgreSQL evaluates the zero-sum balance across all legs when the transaction calls `COMMIT`.
7. **[`LayerArchitectureTest.java`](src/test/java/com/ledgerline/architecture/LayerArchitectureTest.java)**:
   * Automated ArchUnit test ensuring the Domain layer remains completely isolated from Spring Data or web controllers.

---

## 2. End-to-End Transfer Request Lifecycle

This diagram illustrates how an incoming transfer request moves across the architectural layers:

```
[ Client / Caller ]
        │
        │ 1. POST /api/v1/transfers { description, currency, legs }
        ▼
[ TransferController ]
        │
        │ 2. Validates JSON payload (@Valid TransferRequest)
        │ 3. Delegates to Application Layer
        ▼
[ TransferService ]
        │
        │ 4. Extracts account IDs & sorts them in ASCENDING order: [AccountA, AccountB]
        │ 5. Queries AccountRepository.findByIdInOrderByIdAscForUpdate()
        ▼
[ PostgreSQL Database ]
        │
        │ 6. Takes row-level exclusive locks: SELECT ... FOR UPDATE (in sorted order)
        ▼
[ TransferService ]
        │
        │ 7. Iterates through legs; calls account.applyPosting()
        │    - Checks currency matching
        │    - Computes signed delta
        │    - Checks disallow_negative_balance
        │
        │ 8. Invokes JournalEntry.create(instructions)
        ▼
[ JournalEntry (Domain Entity) ]
        │
        │ 9. Tier 1 Check: Asserts Sum(Debits) == Sum(Credits)
        │    - If imbalanced: throws ImbalancedJournalEntryException (Rollback)
        │    - If balanced: builds immutable JournalEntry + Postings
        ▼
[ TransferService ]
        │
        │ 10. Persists Account balance projections and JournalEntry with Postings
        ▼
[ PostgreSQL Database ]
        │
        │ 11. Inserts into journal_entries
        │ 12. Inserts rows into postings (row-by-row)
        │ 13. Updates accounts balance
        │
        │ 14. Transaction COMMIT initiated
        │ 15. Tier 2 Check: Deferred Constraint Trigger (trg_postings_zero_sum) executes
        │     - Validates Net Sum == 0 across all legs
        │     - Commits or Aborts transaction
        ▼
[ TransferController ]
        │
        │ 16. Returns HTTP 201 Created with TransferResponse DTO
        ▼
[ Client / Caller ]
```

---

## 3. Concurrency & Deadlock Elimination Flow

When high-throughput transactions occur between shared accounts, naive locking results in deadlocks:

### The Deadlock Scenario (Without Canonical Ordering)
```
  Transaction 1 (Alice → Bob)             Transaction 2 (Bob → Alice)
─────────────────────────────────       ─────────────────────────────────
1. Locks Alice row successfully         1. Locks Bob row successfully
2. Requests lock on Bob row...          2. Requests lock on Alice row...
   [BLOCKED by Transaction 2]              [BLOCKED by Transaction 1]
                 \                              /
                  \─── CIRCULAR WAIT DEADLOCK ─/
                      (PostgreSQL error 40P01)
```

### The Solution: Canonical Ascending UUID Ordering
In `ledgerline`, both transactions sort account UUIDs before locking:
Assume `UUID(Alice) < UUID(Bob)`.

```
  Transaction 1 (Alice → Bob)             Transaction 2 (Bob → Alice)
─────────────────────────────────       ─────────────────────────────────
Sorted Order: [Alice, Bob]              Sorted Order: [Alice, Bob]

1. Acquires lock on Alice               1. Requests lock on Alice
   (Lock Granted)                          (BLOCKED - waits cleanly for Tx 1)

2. Acquires lock on Bob
   (Lock Granted)

3. Executes transfer & commits
4. Releases Alice & Bob locks
                                        2. Resumes: Acquires lock on Alice
                                        3. Acquires lock on Bob
                                        4. Executes transfer & commits
```
* **Result**: Zero deadlocks. No transaction is aborted due to `40P01`.

---

## 4. Defense-in-Depth Invariant Enforcement Flow

`ledgerline` implements a two-tier defense model ensuring that financial corruption is impossible, whether requests originate from application APIs or direct database scripts.

```
Incoming Request / SQL Mutation
             │
             ▼
    ┌─────────────────┐
    │  Tier 1: Domain │
    │  Validation     │
    └────────┬────────┘
             │
   Balanced? │
             ├───────── NO ────────► [ ImbalancedJournalEntryException ]
             │                       (Immediate rejection; 0 DB queries executed)
             ▼ YES
    ┌─────────────────┐
    │ Persistence &   │
    │ Row Insertions  │
    └────────┬────────┘
             │
             │ Postings inserted row-by-row
             ▼
    ┌─────────────────┐
    │ Transaction     │
    │ COMMIT Phase    │
    └────────┬────────┘
             │
             ▼
    ┌─────────────────┐
    │ Tier 2: DB      │
    │ Deferred Trigger│ (trg_postings_zero_sum)
    └────────┬────────┘
             │
   Net Sum   │
   == 0 ?    │
             ├───────── NO ────────► [ PostgreSQL Exception / Abort ]
             │                       (Database forcibly rolls back transaction; 0 rows stored)
             ▼ YES
    [ Transaction Committed ]
```

---

## 5. Hexagonal Layering & Dependency Movement

`ledgerline` enforces strict Dependency Inversion:

```
[ Infrastructure: Web ]          [ Infrastructure: Persistence ]
(TransferController)              (JpaAccountRepository, Flyway)
         │                                       │
         │ Calls                                 │ Implements
         ▼                                       ▼
[ Application Layer ]            [ Domain Layer: Ports ]
(TransferService, AccountService)──►(AccountRepository Interface)
         │                                       ▲
         │ Uses                                  │ Uses
         ▼                                       │
[ Domain Layer: Core Business Logic ] ───────────┘
(Account, JournalEntry, Posting, Money, Invariants)
```

### Rules Enforced by ArchUnit (`LayerArchitectureTest`):
1. **Domain Layer** has zero dependencies on any other application layer or external database/web framework.
2. **Application Layer** depends only on the Domain Layer. It has zero knowledge of JPA, Hibernate, or HTTP.
3. **Infrastructure Layer** adapts external protocols (HTTP REST requests, SQL persistence) into domain models and implements domain repository interfaces.
