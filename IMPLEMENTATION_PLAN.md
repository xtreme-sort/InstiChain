# InstiChain Implementation Plan

This is the implementation and verification record for the current **Java/Spring Boot + Hyperledger Fabric** repository. It replaces the older FastAPI-based plan. The commit prompts below are retained as the intended scope of each milestone; their code landing does not by itself prove every acceptance criterion. Do not commit automatically unless asked.

## Progress

**Status checked 7 November 2026:** all 20 planned feature/release commits, **1.1 through 5.4, have landed in code**, through `3310fd7`. This does **not** mean the project has passed final acceptance. The 5.3 HTTP-API end-to-end script and 5.4 load-test script exist but have not run against a live Fabric network; the requested browser end-to-end test does not exist. Frontend component tests and mobile/desktop screenshot checks requested in several UI commits are also absent. See the verification gaps below.

| Milestones | Code status | Acceptance status |
| --- | --- | --- |
| 1.1-2.4: application UI and credential lifecycle | Implemented | Backend checks pass; no automated frontend component/browser suite |
| 3.1-3.4: private proof archive and Drive | Implemented | Mocked tests pass; live Google OAuth/import and Docker-backed migration check pending |
| 4.1-4.3: sharing and public verifier APIs | Implemented | Unit/controller tests pass; live Fabric-backed verification pending |
| 4.4: sharing and verifier UI | Implemented | TypeScript/build passes; create/copy/revoke browser flows and responsive screenshots pending |
| 5.1-5.2: key lifecycle and ledger audit | Implemented | Unit tests pass; live rotation, checkpoint, and projection audit pending |
| 5.3: recovery and end-to-end | Rebuild tool and HTTP-API script implemented | Live `make e2e` not run; script is **not** a browser test |
| 5.4: release hardening and performance | Security review, demo notes, and load script implemented | 50-concurrent-user measurements and live release checks pending |

The chaincode changes in 2.2 and 5.1 require `make fabric-refresh FABRIC_SEQUENCE=N` on an already-deployed Java chaincode definition; use the **next** sequence only after checking the current deployment (see `fabric/README.md`).

## What Is Implemented Now

| Area | Current state | Main code |
| --- | --- | --- |
| Startup and configuration | Spring Boot API, React frontend, PostgreSQL/Mailpit Compose services, Fabric test-network scripts, Make targets | `backend/`, `frontend/`, `compose.yaml`, `Makefile`, `scripts/` |
| Email login | Institute-domain validation, single-use expiring email links, persistent cookie sessions, logout | `backend/src/main/java/edu/iitm/instichain/auth/` |
| Signing identity | Browser-generated Ed25519 key in IndexedDB; public key registered with the API; local signing for advisor actions | `frontend/src/signingKeyStore.ts`, `frontend/src/SigningIdentity.tsx`, `backend/src/main/java/edu/iitm/instichain/auth/SigningKeyController.java` |
| Administration | Controlled CLI bootstrap, club creation, advisor appointment, admin UI | `backend/src/main/java/edu/iitm/instichain/cli/`, `backend/src/main/java/edu/iitm/instichain/domain/AdminController.java`, `frontend/src/AdminPanel.tsx` |
| Advisor roles | Advisor club list, signed club-head/office-bearer grants and revocations, advisor UI | `backend/src/main/java/edu/iitm/instichain/domain/AdvisorController.java`, `frontend/src/AdvisorPanel.tsx` |
| Competitions and results | Backend APIs and officer UI for competition creation, result submission and immutable result versions | `CompetitionController.java`, `ResultController.java`, `frontend/src/CompetitionPanel.tsx`, `frontend/src/ResultPanel.tsx` |
| Achievement issuance | Backend prepare/submit API and advisor UI for locally signed credential issuance | `IssuanceController.java`, `frontend/src/IssuancePanel.tsx` |
| Credential lifecycle | Student list/detail/vault, signed revocation API, historical verification and history APIs | `StudentCredentialController.java`, `CredentialRevocationController.java`, `CredentialVerificationService.java`, `frontend/src/CredentialVault.tsx` |
| Fabric ledger | Java chaincode, direct Fabric Gateway client, confirmed-write receipts, event and revocation inspection endpoints | `fabric/chaincode/`, `backend/src/main/java/edu/iitm/instichain/fabric/` |
| Proof storage | Private proof archive, Google Drive OAuth/import, server-side SHA-256 binding, proof-health checks and UI | `backend/src/main/java/edu/iitm/instichain/storage/`, `frontend/src/ProofLibrary.tsx` |
| Selective sharing and public verification | Student-owned hashed share tokens, create/list/revoke APIs, live public verification API, share/QR and verifier UI | `backend/src/main/java/edu/iitm/instichain/sharing/`, `frontend/src/ShareManager.tsx`, `frontend/src/PublicVerifier.tsx` |
| Signing-key lifecycle | Possession-based rotation, administrator-approved recovery, historical public keys, browser key administration | `backend/src/main/java/edu/iitm/instichain/keys/`, `frontend/src/KeyAdministration.tsx`, `docs/KEY_LIFECYCLE.md` |
| Ledger assurance and recovery | Signed checkpoints, projection audit, guarded rebuild of replayable records, backup guide | `backend/src/main/java/edu/iitm/instichain/audit/`, `docs/LEDGER_AUDIT.md`, `docs/RECOVERY.md` |
| Release checks | API end-to-end and verification load scripts, security and demo notes | `scripts/e2e.mjs`, `scripts/load-verify.mjs`, `docs/SECURITY.md`, `docs/DEMO.md` |

The repository has **no Ethereum runtime**. The current end-to-end script exercises HTTP APIs with WebCrypto signatures; it does not automate the React screens. No full browser-to-Fabric pass has been demonstrated.

## Verification And Remaining Work

Local checks on 7 November 2026: backend `./mvnw -q test` ran **84 tests: 83 passed, 1 skipped** (the Flyway/Testcontainers migration test needs Docker); Java chaincode Gradle tests passed **8 tests**; `npm ci` followed by `npm run build` passed. Docker was inaccessible from this environment, so these checks do not establish a working integrated deployment.

1. Start Docker, PostgreSQL, Mailpit, and Fabric. Run the skipped migration test and `make e2e` against a configured backend. Record the actual results in `docs/DEMO.md`. The existing script tests the HTTP API, not the browser.
2. Exercise the full React workflow manually on desktop and mobile, then add automated component/browser tests for role navigation, result entry, issuance, key recovery, sharing, and public verification. Capture mobile/desktop screenshots and check for overlap and failure states.
3. Configure Google OAuth test credentials and a test account; verify connect, import, archive integrity, provider disconnection, and proof-health states against the live service.
4. Run `scripts/load-verify.mjs` against valid share tokens with `LOAD_CONCURRENCY=50`, adjusting the test-only verification rate limit as its instructions require. Record measured p50, p95, error rate, and environment; do not present the design target as a result.
5. Validate key rotation/recovery, checkpoint verification, projection audit, and guarded rebuild on a live Fabric deployment. Keep production deployment assumptions separate from what the one-machine test network proves.
6. Update the current `report-template.tex` draft before submission: it was written before commits 4.3-5.4 and still says public verification is pending. Confirm its stakeholder, contribution, Docker-image, and final-SHA fields rather than inventing them.

These are **verification and release tasks**, not six more feature commits. A separate single-image Docker submission artifact, if required by the course, is not provided by this repository and must be built and tested separately.

## Architecture Rules

- Java Spring Boot is the application API; Java chaincode runs on Hyperledger Fabric. React/TypeScript is the browser client.
- Fabric is authoritative for institutional events such as club creation, appointments, role changes, and credential issuance/revocation. PostgreSQL holds authentication, private data, drafts, and fast read projections.
- Competitions and result submissions are currently PostgreSQL records until credential issuance. Do not claim they are independently replayable from Fabric without first adding corresponding ledger events.
- Email messages, login tokens, OAuth secrets, private signing keys, proof bytes, and share-link bearer tokens must stay off-chain.
- Preserve unrelated working-tree changes. Add focused tests and migrations when changing persistence. Never fabricate a passing end-to-end or performance result.

## Priority 1: Finish Existing Role And Issuance Workflows In The UI

The advisor role dashboard already exists. These commits extend the UI to use the remaining backend APIs.

### Commit 1.1 - Role-aware navigation

```text
Inspect the current React App, session API, AdminPanel, AdvisorPanel, and signingIdentity.ts. Add a compact role-aware navigation shell that exposes only available administrator/advisor areas and leaves server authorization authoritative. Keep the existing email-login and signing-key behavior; do not introduce a second private-key format. Add loading, unauthorized, and error states plus focused frontend tests. Run the frontend test and build commands. Do not commit automatically; give exact targeted git add, a complete quoted git commit command, and git push. Title: feat(frontend): add role-aware application navigation
```

### Commit 1.2 - Competition workspace

```text
Inspect CompetitionController, AccessService, the current session model, and frontend patterns. Add a club-scoped competition list/create/detail workspace for authorized club heads and office bearers. Handle validation, loading, empty, and permission-denied states; never rely on hidden controls for security. Add API/controller and frontend tests for authorized and unauthorized use. Run relevant tests and build. Do not commit automatically; give targeted git add, a full quoted git commit command, and git push. Title: feat(frontend): add competition workspace
```

### Commit 1.3 - Result submission workspace

```text
Inspect ResultController and its immutable version rules. Add result entry, verified recipient selection, result listing/detail, and corrected-version submission to the competition workspace. For now, show the existing proof-digest field honestly as a manual digest; do not pretend a file was uploaded or validated. Add tests for club scope, versioning, input errors, and frontend states. Run tests and build. Do not commit automatically; provide targeted git add, a full quoted git commit command, and git push. Title: feat(frontend): add result submission and corrections
```

### Commit 1.4 - Advisor issuance screen

```text
Inspect IssuanceController's prepare/submit contract and the existing local Ed25519 signing helper. Build the advisor screen to review a frozen result version, sign the exact prepared bytes locally, submit issuance, and show confirmed credential IDs and ledger references. Do not send private keys to the server. Cover duplicate submission, missing local key, invalid signature, Fabric unavailable, and successful issuance in tests. Run frontend build and relevant backend tests. Do not commit automatically; give targeted git add, a full quoted git commit command, and git push. Title: feat(frontend): add advisor credential issuance
```

## Priority 2: Credential Lifecycle And Student Views

### Commit 2.1 - Student credential APIs

```text
Inspect the issued-credential schema, SessionService, and IssuanceController. Add authenticated APIs for a student to list and read only their own credentials with issuer, competition, result, ledger reference, and typed lifecycle status. Add focused Spring MVC/JDBC tests for ownership, empty results, legacy data, and unauthenticated requests. Run backend tests. Do not commit automatically; give targeted git add, a full quoted git commit command, and git push. Title: feat(credentials): add student credential APIs
```

### Commit 2.2 - Advisor-signed credential revocation

```text
Reuse the advisor prepare/sign/submit pattern to revoke an issued credential with a bounded reason. Verify current club authority, key identity, signature, document expiry, idempotency, and credential state; append ACHIEVEMENT_REVOKED to Fabric, then update PostgreSQL only after confirmation. Preserve the original issuance. Add migration and tests for success, scope, retries, concurrent attempts, and Fabric failure. Run backend and chaincode tests. Do not commit automatically; give targeted git add, a full quoted git commit command, and git push. Title: feat(credentials): add signed credential revocation
```

### Commit 2.3 - Historical verification and timeline

```text
Build a shared Java verification service and authorized timeline API using Fabric events/receipts and stored public keys. Report cryptographic authenticity, authorization at issuance time, current active/revoked/superseded status, and proof availability separately. A later advisor term ending must not invalidate a valid historical issuance. Treat missing or malformed legacy evidence as unknown, not valid. Test active, revoked, superseded, invalid signature, missing receipt, and historical authority cases. Do not commit automatically; give targeted git add, a full quoted git commit command, and git push. Title: feat(credentials): verify lifecycle history
```

### Commit 2.4 - Student credential vault

```text
Add a student-facing React vault using the credential list/detail and timeline APIs. Show issuer, award, status, Fabric reference, and separate proof-health information, with clear loading, empty, and error states. Do not show other students' records or imply proof bytes exist when only a digest exists. Add component tests, mobile/desktop layout checks, and a production build. Do not commit automatically; give targeted git add, a full quoted git commit command, and git push. Title: feat(frontend): add student credential vault
```

## Priority 3: Private Proof Storage And Google Drive

Google Drive connection, import, archival, and proof-health checks are implemented. Live provider testing still requires configured OAuth credentials and a test account.

### Commit 3.1 - Storage foundation

```text
Add Flyway schema and Java services for per-user provider connections and proof artifacts. Encrypt refresh credentials at rest, store provider IDs and archived bytes only off-chain, redact secrets from errors/logs, and define a provider-neutral storage adapter with a fake for tests. Add configuration templates and tests for ownership, encryption, redaction, and migrations. Do not commit automatically; give targeted git add, a full quoted git commit command, and git push. Title: feat(proofs): add private storage foundation
```

### Commit 3.2 - Drive OAuth connection

```text
Implement authenticated Google Drive connect/callback/status/disconnect in Spring Boot with least-privilege scope, short-lived single-use user-bound state, encrypted refresh tokens, redirect validation, and provider error handling. Never expose tokens to React. Document test-account and redirect-URI setup. Test success, replayed state, token refresh, disconnect, and sanitized failures with a fake provider. Do not commit automatically; give targeted git add, a full quoted git commit command, and git push. Title: feat(proofs): connect Google Drive accounts
```

### Commit 3.3 - Import and bind exact proof bytes

```text
Stream accessible Drive file bytes through the backend with size/MIME limits, compute SHA-256 server-side, and save an application-controlled archived copy. Bind an owned artifact to a new result version; derive the issued on-chain digest from verified bytes, not a client-supplied digest. Keep existing manual-digest rows explicitly marked as legacy; never silently relabel them as verified. Add tests for hashing, size/type limits, ownership, failures, and immutable result versions. Do not commit automatically; give targeted git add, a full quoted git commit command, and git push. Title: feat(proofs): archive and bind result evidence
```

### Commit 3.4 - Proof health and UI

```text
Add authorized proof checks that distinguish matching archive, provider missing/changed, disconnected, and temporary provider outage. Proof loss must not turn an authentic credential into a revoked one. Add Drive connection and proof-selection UI to the result/vault workflows, with honest status display and no leaked provider URLs. Test backend states and frontend flows, then build. Do not commit automatically; give targeted git add, a full quoted git commit command, and git push. Title: feat(proofs): expose evidence integrity status
```

## Priority 4: Selective Sharing And Public Verification

### Commit 4.1 - Private share-link model

```text
Add Flyway tables for student-owned share links and selected credentials. Store only hashes of high-entropy tokens, bounded expiry, revocation time, and safe metadata; never put share tokens on Fabric. Enforce ownership and uniqueness in Java service/database constraints. Test token hashing, ownership, expiry, and redaction. Do not commit automatically; give targeted git add, a full quoted git commit command, and git push. Title: feat(sharing): add selective share-link records
```

### Commit 4.2 - Student share APIs

```text
Add authenticated create/list/revoke share APIs. Return the raw token only on creation; list only metadata. Validate credential ownership and lifecycle status, apply same-origin protection and rate limits, and keep revocation immediate. Test isolation, duplicate selections, expiry bounds, replay, and redaction. Do not commit automatically; give targeted git add, a full quoted git commit command, and git push. Title: feat(sharing): add student share management
```

### Commit 4.3 - Public verifier API

```text
Add a read-only public endpoint for a share token that performs live credential lifecycle verification using the shared Java verifier. Unknown, expired, and revoked links should have the same generic response. Return only approved public identity, award, issuer, state, and minimal proof status; no emails, provider IDs, internal paths, or secrets. Add no-store headers and abuse-resistant rate limiting. Test live revocation, tampering, selected-record isolation, privacy, and outages. Do not commit automatically; give targeted git add, a full quoted git commit command, and git push. Title: feat(verification): add public live verification
```

### Commit 4.4 - Share and verifier UI

```text
Add student selection/share management and a public verification route in React. Show the token/QR only when created, support copy and immediate revocation, and separate authenticity, lifecycle, and proof states. Use a maintained QR library, keyboard-accessible controls, and responsive layouts. Test create/copy/revoke and valid/expired/unavailable verifier states; check mobile and desktop screenshots and build. Do not commit automatically; give targeted git add, a full quoted git commit command, and git push. Title: feat(frontend): add sharing and verifier screens
```

## Priority 5: Ledger Assurance, Recovery, And Release

### Commit 5.1 - Key rotation and recovery policy

```text
Inspect the browser-only non-exportable key model, advisor appointments, and Fabric signing rules. Design and implement controlled authority-key rotation, key compromise handling, and a documented lost-device policy. Prove possession of a new key, record changes on Fabric before updating PostgreSQL, preserve old public keys for historical verification, and reject old keys for new signatures. Add migration, contract, service, and browser tests where needed. Do not commit automatically; give targeted git add, a full quoted git commit command, and git push. Title: feat(keys): add controlled signing-key lifecycle
```

### Commit 5.2 - Checkpoints and projection audit

```text
Add deterministic signed Fabric event checkpoints and an operator CLI that compares Fabric events/receipts with PostgreSQL projections. Detect missing, changed, reordered, or invalid records without leaking private data. Document audit-key configuration and portable export verification. Test tampering and missing receipts with fixture data. Do not commit automatically; give targeted git add, a full quoted git commit command, and git push. Title: feat(ledger): add checkpoint and projection audit
```

### Commit 5.3 - Recovery boundaries and end-to-end tests

```text
Implement a guarded dry-run/rebuild tool for only those institutional projections whose complete source events exist on Fabric. Do not claim competitions, result drafts, users, sessions, OAuth data, or proof bytes can be replayed unless separately recorded/backed up. Add backup/restore instructions for PostgreSQL private data, then browser-to-Fabric tests for login, club/advisor/role actions, competition/result, issuance, student view, sharing, and revocation using isolated fixtures. Report tests that cannot run in the current Docker environment. Do not commit automatically; give targeted git add, a full quoted git commit command, and git push. Title: test(e2e): validate Fabric workflow and recovery
```

### Commit 5.4 - Security, performance, and demo release

```text
Audit Spring Boot cookies/origins/CSRF, rate limits, request sizes, secret logging, dependency configuration, Fabric availability behavior, and frontend error handling. Add realistic load-test fixtures and measure 50 concurrent public verifications, reporting p50/p95/error rate only from actual runs. Finish setup, troubleshooting, backup, production deployment assumptions, and professor-demo instructions; document that local Fabric peers are not independent production organizations. Run backend, chaincode, frontend, E2E, and load tests where available, recording exact results and limitations. Do not commit automatically; give targeted git add, a full quoted git commit command, and git push. Title: chore(release): harden and document InstiChain
```

## For Follow-up Changes

1. Review `git status --short` and keep unrelated changes intact.
2. Run the tests for touched backend, chaincode, and frontend modules; add integration tests for cross-layer behavior.
3. Inspect `git diff --cached` before committing. Stage only the intended files, then push only after the change is reviewed.