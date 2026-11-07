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

## Public verification

A student creates a share link for selected credentials. A verifier needs no
account: `POST /api/public/verification` with `{"token": "..."}` re-runs the
shared credential verifier on every call, so a revocation recorded after the link
was created is reported immediately.

- The token travels in the request body and the verifier page reads it from the
  URL fragment, so it never reaches access logs or `Referer` headers. Only its
  SHA-256 hash is stored, and share tokens are never written to Fabric.
- Unknown, expired, and revoked links return the same `404` response.
- Responses carry the recipient's display name, award, competition, club, issuer,
  and Fabric reference, but no email addresses, internal paths, or storage
  provider details. Proof status is reduced to `verified`, `digest_only`,
  `unchecked`, or `unavailable`.
- Each credential reports authenticity, ledger status, historical authorization,
  and lifecycle separately, plus a `verdict` that is `valid` only when all of them
  pass. A Fabric outage yields `unknown`, never `valid`.
- Responses are `no-store`, and each client address is limited to 30 requests
  per minute. That limit is held in process memory, so a multi-instance
  deployment must also rate-limit at the reverse proxy.

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
