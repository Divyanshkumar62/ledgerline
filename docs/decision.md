# ledgerline — Architecture Decision Records (ADRs)

This document records the foundational architectural decisions made in `ledgerline`, outlining the problem context, chosen solution, alternatives considered, and engineering tradeoffs.

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

## ADR-002: Canonical Lock Ordering for Concurrency Control

* **Status**: Accepted
* **Date**: 2026-10-18
* **Deciders**: Principal Backend Engineer, Senior QA Lead

### Context
In financial transfers involving two or more accounts (e.g., Transfer 1: Account A $\rightarrow$ Account B, Transfer 2: Account B $\rightarrow$ Account A occurring simultaneously), acquiring pessimistic write locks (`SELECT ... FOR UPDATE`) in arbitrary order inevitably results in circular wait deadlocks (PostgreSQL error `40P01: deadlock detected`).

### Decision
Enforce **canonical lock ordering** by account UUID:
1. Before acquiring row locks in PostgreSQL, `TransferService` extracts all distinct account IDs involved in the transfer and sorts them in ascending order.
2. Locks are acquired sequentially in this strict canonical order:
   `SELECT a FROM Account a WHERE a.id IN :ids ORDER BY a.id ASC FOR UPDATE`
3. Eliminates Coffman Condition #4 (Circular Wait), guaranteeing zero database deadlocks under bidirectional transfer storms.

### Critical Technical Nuance: Java `UUID.compareTo`
Java's `java.util.UUID.compareTo` compares the internal `mostSignificantBits` and `leastSignificantBits` as **signed 64-bit longs**. Because they are signed, UUIDs with high bits starting with `0x8` to `0xF` evaluate to negative numbers and sort *before* UUIDs starting with `0x0` to `0x7`. The in-memory sorting logic and repository query must both adhere to consistent ascending order to guarantee deadlock freedom.

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
* `Domain`: Contains pure business logic, entities, value objects, exceptions, and repository interfaces (ports). Zero dependencies on external frameworks or infrastructure.
* `Application`: Orchestrates use cases and domain models (`TransferService`, `AccountService`). Depends strictly on `Domain`.
* `Infrastructure`: Implements persistence adapters (`JpaAccountRepository`) and HTTP controllers (`TransferController`).
* **ArchUnit Verification**: `LayerArchitectureTest` runs on every `./gradlew check` to fail builds if boundary rules are violated.
