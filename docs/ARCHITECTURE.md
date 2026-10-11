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
| Administrator bootstrap, club creation, advisor appointment and role revocation events | Hyperledger Fabric | Immutable, ordered and endorsed by institute and auditor peers |
| Future competition, result and credential events | Hyperledger Fabric | Institutional records must be independently verifiable |
| Revocation list read model | PostgreSQL projection, indexed on Fabric | Verifiers need one fast status answer; Fabric remains authoritative |
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
  app/admin.py                Protected club/advisor/revocation HTTP endpoints
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

On Linux and WSL, `make docker` starts the Docker daemon using the active system
service manager, and `make status-docker` checks daemon access. Docker Desktop
must be opened through its desktop application.

## Implemented today

- Institute-only email verification and passwordless returning login.
- Server-side sessions with revocation and secure-cookie behavior.
- Controlled initial administrator bootstrap.
- Club creation and faculty-advisor appointment.
- Fabric events for `ADMIN_BOOTSTRAPPED`, `CLUB_CREATED`, `ADVISOR_APPOINTED`
  and `ROLE_REVOKED`.
- Two-organization Fabric endorsement policy requiring Org1 and Org2.
- Direct audit endpoints at `/api/ledger/health`, `/api/ledger/events` and
  `/api/ledger/events/{request_id}`.
- Administrator-signed appointment revocation and the public revocation list at
  `/api/ledger/revocations`, `/api/ledger/revocations/{type}/{id}` and
  `/api/ledger/fabric-revocations`.

Competition creation, result submission and credential issuance are represented
in the planned projection schema but do not yet have workflows or Fabric event
handlers. They must follow the same commit-first flow above. Credential
revocation (`ACHIEVEMENT_REVOKED`) is validated and indexed by the chaincode and
listed by the revocation endpoints, but has no write endpoint until issuance
exists.

## Revocation and the revocation list

Revocation is an appended event, never an edit: `ROLE_REVOKED` records the end of
authority and the projection's `revoked_at`/`revocation_transaction_id` pair is
set only after Fabric confirms. The chaincode refuses a revocation whose subject
has no granting event or is already revoked, and maintains a `revocation:`
subject index so a verifier's "is this revoked?" question is a direct lookup
rather than a replay of the whole ledger.

The read endpoints are deliberately unauthenticated: an external placement
verifier holds a student's share link, not an institute account. They return
`checked_at` because, as the design document notes, an offline signature check
cannot establish that no later revocation exists. An absent entry means "not
revoked as of `checked_at`" — it is not a proof of validity, and a verifier that
cannot reach this endpoint must report status unknown rather than valid.
`/api/ledger/fabric-revocations` reads the on-chain index directly so the
PostgreSQL projection can be audited against Fabric.

## Where to add the next feature

1. Add deterministic validation and event handling to
   `fabric/chaincode/lib/instichain-contract.js`.
2. Add the thin authenticated HTTP endpoint under `backend/app/`.
3. Update PostgreSQL only when private fields or a query projection are needed.
4. Add the corresponding React workflow under `frontend/src/`.
5. Test chaincode validation, API authorization and projection behavior.

Do not put verification tokens, sessions, private keys, full evidence files or
other deletable personal data into Fabric.
