# InstiChain Architecture and Code Map

## Runtime boundary

```text
React browser client
        |
        v
Spring Boot API (Java 21)
        |
        +-- Hyperledger Fabric: authoritative institutional events
        +-- PostgreSQL: authentication, private data, and query projections
        +-- SMTP / Mailpit: email-link delivery
```

The API authenticates people, enforces authorization, prepares and verifies
signatures, submits institutional events to Fabric, and maintains PostgreSQL read
models only after Fabric confirms a write.

## Data ownership

| Data | Authoritative system |
| --- | --- |
| Users, email links, sessions | PostgreSQL |
| Clubs, appointments, credentials | Fabric events; PostgreSQL query projection |
| Competitions, submissions, recipients | PostgreSQL until credential issuance |
| Fabric transaction receipts | PostgreSQL projection |
| Email messages | SMTP provider or Mailpit |
| Private keys and service credentials | Local ignored files or a secret manager |

The legacy `ledger_entries` table is retained only for old migration history. New
institutional records are never written to it.

## Confirmed-write flow

1. React sends an authenticated request to Spring Boot.
2. The API validates the session, role, club scope, and request.
3. The appropriate authority signs a canonical event document.
4. The Java Fabric Gateway client sends it directly to the Org1 peer.
5. Org1 and Org2 endorse it; Java chaincode validates and stores the event.
6. After a valid commit, the API records the receipt and updates its PostgreSQL
   projection in the same local transaction.

If Fabric does not confirm the event, the PostgreSQL domain change rolls back.

## Code map

```text
frontend/src/
  App.tsx                    Session-aware application shell
  EmailVerification.tsx      Email-link registration and login
  AdminPanel.tsx             Club and advisor administration

backend/
  pom.xml                    Spring Boot and Fabric Gateway dependencies
  mvnw                       Pinned Maven launcher
  src/main/java/edu/iitm/instichain/
    auth/                    Institute email, tokens, sessions, SMTP
    cli/                     Operator-only keygen and admin bootstrap
    common/                  Errors, canonical JSON, signatures, UUIDs
    config/                  Typed environment configuration and transactions
    domain/                  Clubs, advisors, roles, competitions, results, issuance
    fabric/                  Direct Gateway client, receipts, audit API
    web/                     Health endpoint
  src/main/resources/
    application.yml          Environment bindings
    db/migration/            Flyway database migrations
  src/test/                  Java tests

fabric/chaincode/
  build.gradle               Java chaincode build
  src/main/java/             Ledger contract
  src/test/java/             Contract tests
  gradlew                    Pinned Gradle wrapper

scripts/
  dev.sh                     Frontend/backend start, stop, and status
  fabric.sh                  Fabric install, deploy, upgrade, and teardown
```

The React frontend remains TypeScript because the Java requirement applies to
the server and blockchain application layers. There is no Python backend, Node
gateway bridge, or JavaScript chaincode in the tracked runtime architecture.
