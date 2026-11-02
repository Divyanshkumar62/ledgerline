# ledgerline — Architecture

> Diagrams render on GitHub (Mermaid). Detailed step-by-step flows: [flow.md](flow.md). Why each piece exists: [decision.md](decision.md).

## 1. System context (what runs, what talks to what)

```mermaid
flowchart LR
    client(["API client (API key)<br/>retries with the same Idempotency-Key"])
    operator(["Operator (admin key)<br/>issues keys · repairs accounts"])
    prom(["Prometheus / ops"])
    consumers(["Downstream consumers<br/>dedupe on event_id"])

    subgraph app ["Ledger Line — Spring Boot 4, Java 21"]
        sec["Security<br/>API key → client · roles · owner-only"]
        api["REST API<br/>accounts · statements · transfers · fx-transfers · admin"]
        svc["TransferService<br/>tx boundary · idempotency · retry · outbox"]
        audit["Audit job<br/>every 60 s"]
        relay["Outbox relay<br/>every 200 ms"]
        ret["Retention job<br/>hourly"]
        act["Actuator<br/>/health · /prometheus"]
    end

    subgraph pg ["PostgreSQL 16 — source of truth"]
        ledger[("accounts · journal_entries · postings<br/>zero-sum + immutability triggers")]
        idem[(idempotency_records)]
        outbox[(outbox_events)]
        cp[(reconciliation_checkpoints)]
        clients[("api_clients (key hashes)<br/>account_admin_actions")]
    end

    kafka[["Kafka<br/>ledger.transfers.v1 · ledger.accounts.v1"]]

    client -->|HTTP| sec --> api --> svc
    operator -->|HTTP| sec
    sec -->|lookup| clients
    svc -->|one transaction| ledger & idem & outbox
    audit -->|REPEATABLE READ snapshot| ledger
    audit -->|freeze + LedgerDriftDetected| outbox
    audit --> cp
    relay -->|FOR UPDATE SKIP LOCKED| outbox
    relay -->|at-least-once| kafka --> consumers
    ret -->|purge old| idem & outbox
    prom -->|scrape| act
```

**The one rule that makes it correct:** everything that must agree is written in **one PostgreSQL transaction**: postings, balances, the idempotency record and the outbox event. Kafka is never on the transfer path.

## 2. Layers (enforced by ArchUnit on every build)

```mermaid
flowchart TB
    subgraph infra [infrastructure]
        web["web + security: Account/Transfer/Admin controllers · API-key filter · CorrelationFilter"]
        persist["persistence: JPA + JDBC adapters"]
        workers["messaging / audit / retention: OutboxRelay · AuditScheduler · RetentionJob"]
    end
    subgraph application [application]
        services["TransferService · AccountService · AccountAdminService · LedgerAuditService · ApiClientService"]
        strategies["ConcurrencyStrategy: A ordered pessimistic (default) · B optimistic · C atomic conditional"]
    end
    subgraph domain [domain]
        model["Account · JournalEntry · Posting · Money"]
        events["events: TransferCompleted · LedgerDriftDetected"]
        ports["ports: AccountRepository · IdempotencyRepository · OutboxRepository · ReconciliationRepository …"]
    end

    web --> services
    workers --> services
    services --> strategies
    services --> ports
    strategies --> model
    persist -. implements .-> ports
```

| Rule | Checked by |
|---|---|
| Infrastructure → Application → Domain, never backwards | `LayerArchitectureTest.layeringRule` |
| Domain never imports Spring (JPA mapping annotations allowed) | `domainIsFreeOfSpring` |
| Application never imports JPA, Hibernate, JDBC, Spring Data or HTTP | `applicationIsFreeOfPersistenceAndWeb` |

## 3. A transfer, end to end

```mermaid
sequenceDiagram
    autonumber
    participant C as Client
    participant API as Controller (+MDC)
    participant TS as TransferService
    participant DB as PostgreSQL
    participant R as Outbox relay
    participant K as Kafka

    C->>API: POST /transfers (Authorization: Bearer key, Idempotency-Key)
    API->>TS: executeIdempotent
    TS->>DB: BEGIN
    TS->>DB: caller owns every named account? (else 404)
    TS->>DB: INSERT idempotency_records ON CONFLICT DO NOTHING
    alt key already committed
        DB-->>TS: 0 rows
        TS->>DB: read record, compare request hash
        TS-->>C: 201 original body + X-Cache-Lookup: HIT-IDEMPOTENT (or 422 if payload differs)
    else this request owns the key
        TS->>DB: SELECT accounts ... ORDER BY id FOR UPDATE
        TS->>TS: JournalEntry.create (zero-sum, currency, ≥2 legs) · applyPosting (funds, frozen)
        TS->>DB: INSERT journal entry + postings, UPDATE balances
        TS->>DB: link key → entry · INSERT outbox TransferCompleted
        TS->>DB: COMMIT (deferred zero-sum trigger runs here)
        TS-->>C: 201 Created
    end
    loop every 200 ms
        R->>DB: SELECT unpublished FOR UPDATE SKIP LOCKED
        R->>K: send batch, await acks in order
        R->>DB: mark acked prefix published
    end
```

## 4. Deployment (`docker compose up --build`)

```mermaid
flowchart LR
    subgraph compose ["docker compose"]
        app["ledgerline:0.1.0<br/>:8080 · healthcheck = readiness probe"]
        pg[("postgres:16-alpine<br/>:5432")]
        kf[["apache/kafka-native:3.8.0<br/>kafka:19092 internal · localhost:9092 host"]]
        demo["demo<br/>curl walkthrough, exits"]
    end
    app --> pg
    app -->|kafka:19092| kf
    demo -->|waits for healthy app| app
```

The demo service prints PASS/FAIL for each guarantee and exits non-zero if any fails, so the same command is also a smoke test (it runs in CI).
