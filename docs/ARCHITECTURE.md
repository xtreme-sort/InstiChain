# InstiChain Architecture and Code Map

## System boundary

```text
Browser (React)
        |
        v
Thin HTTP API (FastAPI)
        |
        +-- Hyperledger Fabric: authoritative institutional events
        +-- PostgreSQL: authentication, private metadata and query projections
        +-- SMTP / Google Drive: external delivery and file integrations
```

Fabric is the authoritative record for institutional actions. PostgreSQL is not
a competing blockchain: it holds login state, information that must remain
private, and projections that make screens fast to query. The API protects
identities and converts browser requests into endorsed Fabric transactions.

## Data ownership

| Data | Authoritative system | Reason |
| --- | --- | --- |
| Administrator bootstrap, club creation, advisor appointment, role grant and role revocation events | Hyperledger Fabric | Immutable, ordered and endorsed by institute and auditor peers |
| Credential issuance events, and future credential revocation | Hyperledger Fabric | Institutional records must be independently verifiable |
| Revocation list read model | PostgreSQL projection, indexed on Fabric | Verifiers need one fast status answer; Fabric remains authoritative |
| Competitions, result submissions and recipients | PostgreSQL | Drafts and versions are private until an advisor approves and issues |
| Users, email verification tokens and login sessions | PostgreSQL | Private, expiring and operational data must not be permanently replicated |
| Club and appointment read models | PostgreSQL projection | Efficient UI queries; each write is accepted only after Fabric confirms it |
| Fabric transaction receipts | PostgreSQL projection | Connects local records to transaction IDs and block numbers |
| Email messages | SMTP provider or Mailpit | Delivery infrastructure, not ledger state |
| Evidence files | Google Drive or another object store | Fabric should contain only durable references/digests, not file bytes |
| Private keys and service tokens | Ignored environment or secret manager | Secrets must never enter Git, PostgreSQL or Fabric |

The old `ledger_entries` PostgreSQL hash chain is migration history only. In code
it is deliberately named `LegacyLedgerEntry`, and no application service writes
to it.

## Request flow

For a club or advisor change:

1. React sends an authenticated request to FastAPI.
2. FastAPI validates the session, administrator role and request.
3. The authority service signs a canonical event.
4. The Node Gateway bridge submits it to the Fabric peer.
5. The chaincode validates it; Org1 and Org2 must both endorse the write.
6. After Fabric reports a valid commit, FastAPI stores the receipt and updates
   the PostgreSQL read model in one local transaction.
7. Read screens query the projection; audit screens query Fabric directly.

If Fabric is unavailable, the domain change is rejected and no local club or
appointment is committed.

## Directory map

```text
frontend/
  src/App.tsx                 Application shell and session-aware routing
  src/EmailVerification.tsx   Email-link registration and returning login
  src/AdminPanel.tsx          Club creation and advisor appointment UI
  src/styles.css              Shared presentation

backend/
  app/main.py                 FastAPI composition and process health route
  app/verification.py         Institute email validation and single-use links
  app/session.py              Login sessions, cookies and logout
  app/email_delivery.py       SMTP delivery
  app/admin.py                Protected club/advisor HTTP endpoints
  app/advisor.py              Advisor-signed club role grants and revocation
  app/permissions.py          Club-scoped role and authority checks
  app/competitions.py         Club-scoped competition workflow
  app/results.py              Immutable versioned result submissions
  app/issuance.py             Advisor-signed team credential issuance
  app/authority.py            Event signing, Fabric submission and receipts
  app/ledger.py               Read-only Fabric audit API and revocation list
  app/models.py               Auth/private tables and local projections
  app/bootstrap.py            Operator-only administrator/key commands
  app/preflight.py            SMTP and Drive connectivity checks
  migrations/                 Immutable PostgreSQL schema history
  tests/                      Backend and migration tests

fabric/
  chaincode/lib/instichain-contract.js  On-chain validation and event storage
  chaincode/test/                       Smart-contract unit tests
  gateway/src/server.js                 Official Fabric Gateway SDK bridge
  README.md                             Fabric network setup and operations
  runtime/                              Downloaded identities/runtime (ignored)

scripts/
  dev.sh                      Shared frontend/backend start-stop-status runner
  fabric.sh                   Fabric install, deploy, stop and Gateway runner

compose.yaml                  PostgreSQL and local Mailpit
Makefile                      Human-friendly development commands
```

Use `make mailpit`, `make status-mailpit` and `make stop-mailpit` to manage the
local SMTP test server. Its browser inbox is available at
<http://127.0.0.1:8025>.

Use `make database`, `make status-database` and `make stop-database` to manage
PostgreSQL without deleting its persistent Docker volume.

On Linux and WSL, `make docker` starts the Docker daemon using the active system
service manager, and `make status-docker` checks daemon access. Docker Desktop
must be opened through its desktop application.
