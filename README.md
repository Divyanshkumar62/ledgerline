# Ledger Line

> A reference double-entry financial ledger in Java 21 and Spring Boot 3 engineered for correctness under concurrency, retries, and partial failure.

---

## Architecture & Core Invariants

Ledger Line models a strict double-entry ledger where every transaction consists of balanced postings across accounts:

1. **Zero-Sum Balance Invariant**: Sum of postings in every journal entry must equal zero (debits equal credits).
2. **Immutability**: Postings and journal entries are append-only. No updates, no deletes.
3. **Idempotency**: Client requests carry unique idempotency keys to guarantee at-most-once execution across network retries.
4. **Concurrency Safety**: High-concurrency transfers against shared hot accounts maintain exact balances without race conditions or deadlocks.

---

## Tech Stack

* **Language**: Java 21
* **Framework**: Spring Boot 3.3.x
* **Database**: PostgreSQL 16
* **Database Migrations**: Flyway
* **Integration Testing**: Testcontainers (PostgreSQL 16)
* **Build System**: Gradle

---

## Project Structure

```
ledgerline/
├── src/main/java/com/ledgerline/
│   ├── domain/             # Core business models (Account, JournalEntry, Posting, Money)
│   ├── application/        # Transfer services and transaction orchestration
│   └── infrastructure/     # Spring Data JPA repositories, Flyway migrations
└── src/test/java/com/ledgerline/
    └── integration/        # Testcontainers-backed concurrency and invariant tests
```

---

## Getting Started

### Prerequisites

* JDK 21+
* Docker (for Testcontainers PostgreSQL tests)

### Build and Run Tests

```bash
./gradlew check
```

---

## Engineering Roadmap

Development progress is tracked phase-by-phase in [`docs/tracker.md`](docs/tracker.md).

* **Week 1**: Scaffold, Flyway schema, PostgreSQL 16 Testcontainers integration test, CI.
* **Week 2**: Domain entities, zero-sum invariant, ordered pessimistic locking transfer service.
* **Week 3**: Concurrency benchmark: pessimistic locks vs optimistic retry vs conditional atomic updates.
* **Week 4**: DB-first idempotency store with replay protection.
* **Week 5**: Transactional outbox relay worker to Kafka.
* **Week 6**: Daily reconciliation audit job and account drift freeze.
