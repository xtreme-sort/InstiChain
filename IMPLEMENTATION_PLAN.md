# InstiChain Remaining Implementation Prompts

This playbook divides the five remaining workstreams into four commits each. Run the prompts in order because later commits depend on APIs and data models introduced earlier.

## Architecture To Preserve

- FastAPI remains the thin application API.
- Hyperledger Fabric is authoritative for institutional events and credential lifecycle records.
- PostgreSQL stores authentication data, private data, provider tokens, and query projections.
- Email authentication and cloud-file bytes stay off-chain.
- Never persist private signing keys, OAuth refresh tokens, raw share tokens, or public file URLs on Fabric.
- Preserve unrelated working-tree changes and do not create a Git commit automatically.
- Every implementation prompt must end with tests and exact, narrowly scoped `git add`, `git commit`, and `git push` commands.

## Task 1: Credential Lifecycle

### Commit 1.1 - Student credential queries

```text
Implement the next InstiChain commit: student credential query and status APIs.

First inspect the current repository, migrations, models, authentication dependencies, Fabric gateway, and existing achievement issuance flow. Preserve all unrelated working-tree changes.

Add authenticated endpoints that let a verified student list only credentials issued to that student and retrieve one credential by ID. Return issuer, club, competition/result context, issue time, ledger transaction reference, and a clearly typed lifecycle status. Introduce a reusable credential status service that can represent active, revoked, and superseded records without treating missing proof files as credential revocation. Ensure another student cannot enumerate or read these records. Follow existing FastAPI, SQLAlchemy, Pydantic, and test conventions, and add a migration only if the existing projection cannot support efficient ownership and status queries.

Add focused integration tests for empty results, successful listing/detail, ownership isolation, authentication, and legacy credentials. Run the relevant backend test suite. Do not commit automatically. At the end, summarize changed files and provide exact targeted git add commands, a complete git commit command whose message is inside quotes, and git push.

Use this commit title: feat(credentials): add student credential status APIs
```

### Commit 1.2 - Advisor-signed revocation

```text
Implement the next InstiChain commit: advisor-signed credential revocation.

Inspect the existing two-step Ed25519 signing workflow used for role grants and achievement issuance. Preserve unrelated changes and reuse the canonical-document and Fabric submission patterns already in the repository.

Add prepare and submit endpoints that allow the advisor who issued an achievement credential, or another currently authorized advisor for the same club according to the project policy, to revoke it with a required bounded reason. The prepare endpoint must return deterministic canonical bytes for local signing. The submit endpoint must verify the signature, actor key, advisor authority, club scope, credential state, request expiry, and idempotency key before recording an ACHIEVEMENT_REVOKED event on Fabric. Update the PostgreSQL projection only after Fabric confirms the transaction. Store the reason and ledger transaction reference, retain the original issued record, and make retries return the original successful result. Do not put private keys or sensitive provider data on-chain.

Add tests for success, unauthorized advisor, wrong club, invalid or expired signature request, duplicate submission, already-revoked credential, Fabric failure rollback, and historical record retention. Run the relevant tests. Do not commit automatically. Return a concise change summary plus exact targeted git add, a full quoted git commit command, and git push.

Use this commit title: feat(credentials): add advisor-signed revocation
```

### Commit 1.3 - Historical lifecycle verification

```text
Implement the next InstiChain commit: historical credential lifecycle verification.

Read the current credential issuance, revocation, appointment, authority-key, and Fabric receipt code before editing. Preserve unrelated working-tree changes.

Build one shared verification service that evaluates a credential from its issuance event through its current state. It must verify the canonical payload signature, signing key identity, ledger transaction receipt, advisor authority at the exact issuance time, later revocation events, and supersession links. A later appointment end or key rotation must not make a valid historical issuance appear forged. Return separate fields for cryptographic authenticity, historical authorization, current lifecycle status, and proof availability so these concepts cannot be conflated. Expose the result through a properly authorized authenticated API suitable for the student vault and advisor audit views.

Handle malformed or incomplete legacy data explicitly instead of silently marking it valid. Add unit and integration tests for active, revoked, superseded, historically valid, unauthorized-at-issuance, bad-signature, and missing-receipt cases. Run the relevant tests. Do not commit automatically. Provide exact targeted staging commands, the complete git commit command with the message in quotes, and git push.

Use this commit title: feat(credentials): verify historical credential lifecycle
```

### Commit 1.4 - Lifecycle history and race hardening

```text
Implement the next InstiChain commit: lifecycle history and concurrency hardening.

Inspect the credential lifecycle services and existing database transaction/idempotency conventions. Preserve unrelated changes.

Add an authorized credential history endpoint that returns the ordered issuance, supersession, and revocation timeline with Fabric transaction references. Students may read their own history; advisors may read credentials within their active club scope; administrators follow the existing audit policy. Harden mutations against concurrent revoke, correction, and duplicate-submit races using database constraints, locks, compare-and-set state transitions, or the repository's established equivalent. The resulting state must be deterministic even when two requests arrive together, and a failed Fabric submission must not leave a successful local projection.

Add concurrency and idempotency tests, including simultaneous revocations, correction-versus-revocation, duplicate request IDs, and Fabric retry behavior. Add any necessary database constraint through a migration. Run the lifecycle and migration tests. Do not commit automatically. Report changed files and give exact targeted git add, one complete quoted git commit command, and git push.

Use this commit title: test(credentials): harden lifecycle history and concurrency
```

## Task 2: Vault And Cloud Proofs

### Commit 2.1 - Private storage foundation

```text
Implement the next InstiChain commit: private proof-storage foundation.

Inspect project configuration, migrations, encryption helpers, dependency injection, and result submission models. Preserve unrelated working-tree changes and keep all cloud authentication data off Fabric.

Add migrations and backend models for per-user storage connections and proof artifacts. A storage connection should track provider, owner, encrypted refresh credential, scopes, connection status, and timestamps without exposing secrets in representations or logs. A proof artifact should track owner, provider file identifier, safe display metadata, archived-copy location, SHA-256 digest, byte size, MIME type, and availability state. Introduce a provider-neutral storage adapter interface and configuration template entries for an application encryption key and Google OAuth settings. Fail startup clearly in production when required encryption configuration is absent, while keeping tests deterministic.

Do not implement live Google OAuth in this commit. Add migration, model, encryption round-trip, secret-redaction, ownership, and configuration tests. Run the relevant backend tests and migration upgrade/downgrade checks. Do not commit automatically. Provide exact targeted git add commands, a full quoted commit command, and git push.

Use this commit title: feat(vault): add encrypted storage connection models
```

### Commit 2.2 - Google Drive connection

```text
Implement the next InstiChain commit: Google Drive OAuth connection.

Start by reviewing the storage abstraction, authentication cookies, same-origin protection, configuration, and frontend API patterns. Preserve unrelated changes.

Implement authenticated Google Drive connect, OAuth callback, connection-status, and disconnect flows using the minimum drive.file scope. Use a short-lived, single-use, user-bound OAuth state value and validate redirect targets. Encrypt refresh tokens at rest, rotate stored tokens when Google returns a replacement, never return tokens to the browser, and redact tokens and authorization codes from logs and errors. Disconnect must revoke the provider grant when possible and always remove local secrets without deleting institutional credential records. Add a fake Drive adapter for tests so the test suite requires no network or real Google account.

Add tests for successful connection, rejected/mismatched/replayed state, provider error, token encryption, token refresh, disconnect, CSRF/same-origin behavior, and sanitized failures. Add concise setup documentation for Google test-account access and required redirect URIs. Run tests. Do not commit automatically. Return exact targeted git add, the complete git commit command in quotes, and git push.

Use this commit title: feat(vault): connect private Google Drive accounts
```

### Commit 2.3 - Proof import and immutable binding

```text
Implement the next InstiChain commit: proof import, archival, and result binding.

Inspect the Drive adapter, immutable result submission workflow, database schema, and Fabric payload format. Preserve unrelated changes.

Allow an authenticated user to select a file that is accessible through the app-created Drive scope, stream its exact bytes through the backend, compute SHA-256 without loading unbounded files into memory, and retain an application-controlled archived copy according to the existing storage abstraction. Enforce configurable size and MIME limits. Bind the resulting proof artifact to a result submission so new submissions derive the on-chain proof digest from server-verified bytes rather than accepting an arbitrary client digest. Persist only the digest and non-sensitive reference needed by the institutional event on Fabric; keep provider IDs, OAuth data, filenames where sensitive, and archived bytes off-chain. Do not mutate already-submitted result records; preserve backward compatibility for legacy rows explicitly.

Add migrations if needed plus tests for exact-byte hashing, large streamed files, disallowed types, interrupted downloads, duplicate imports, ownership, result binding, Fabric failure rollback, and legacy submissions. Run all relevant tests. Do not commit automatically. Give a changed-file summary, exact targeted git add commands, a complete quoted commit command, and git push.

Use this commit title: feat(vault): archive and hash result proofs
```

### Commit 2.4 - Proof integrity monitoring

```text
Implement the next InstiChain commit: proof availability and integrity checks.

Read the proof artifact, Drive adapter, credential verification, and lifecycle status code first. Preserve unrelated changes.

Add a proof-check service and authorized API that can compare currently retrievable provider bytes and/or the retained archived copy against the immutable on-chain SHA-256 digest. Model available-and-matching, provider-missing, provider-content-changed, archive-missing, disconnected, and provider-temporarily-unavailable states separately. Provider outages and missing files must never make a valid credential appear cryptographically invalid or revoked. Add conservative timeouts, bounded retries, cached last-check metadata, and log redaction. Ensure the response reveals only metadata appropriate to the requesting student, scoped advisor, or public share policy.

Add adapter and API tests for every state, timeout/retry behavior, authorization, digest mismatch, and the separation between proof health and credential authenticity. Run the tests. Do not commit automatically. Return exact targeted staging commands, the full git commit command with its message in quotes, and git push.

Use this commit title: feat(vault): verify proof availability and integrity
```

## Task 3: Selective Sharing And Public Verification

### Commit 3.1 - Share-link data model

```text
Implement the next InstiChain commit: selective share-link persistence.

Inspect authentication, credential ownership, migrations, token hashing utilities, and audit conventions. Preserve unrelated working-tree changes. Share links are private application data and must not be written to Fabric.

Add migrations and models for student-owned share links and the exact credentials selected for each link. Store only a cryptographic hash of a high-entropy bearer token, not the raw token. Include bounded expiry, created/revoked timestamps, optional student-visible label, and indexes for safe lookup. Enforce in the service layer and database where possible that every selected credential belongs to the creating student. Design deletion and revocation so issued credentials and ledger history are never removed. Add audit metadata without logging raw share tokens.

Add model/service tests for token uniqueness and hashing, ownership, expiry bounds, duplicate selections, revocation, cascade behavior, and secret redaction. Run migration checks and tests. Do not commit automatically. Provide exact targeted git add, a complete quoted git commit command, and git push.

Use this commit title: feat(sharing): add selective share-link records
```

### Commit 3.2 - Student share management APIs

```text
Implement the next InstiChain commit: student share-link management APIs.

Review the new share models, student credential endpoints, session authentication, CSRF/same-origin defenses, and rate-limiting conventions. Preserve unrelated changes.

Add authenticated endpoints for a student to create a share link from selected owned credentials, list link metadata without tokens, and revoke a link immediately. Return the raw unguessable token only once at creation. Reject expired, revoked, superseded, or non-owned credential selection according to a clearly documented policy. Apply expiry bounds, transactional creation, idempotency where appropriate, same-origin checks, rate limits, and audit logging that never includes bearer tokens. Do not expose cloud provider URLs or credentials.

Add integration tests for creation, one-time token return, list redaction, selection isolation, expiry validation, immediate revocation, repeated revocation, rate limiting, and CSRF. Run the relevant tests. Do not commit automatically. Provide exact targeted staging commands, the complete commit command with the message inside quotes, and git push.

Use this commit title: feat(sharing): add student share management APIs
```

### Commit 3.3 - Public live verification API

```text
Implement the next InstiChain commit: public live credential verification.

Inspect the share-token model, shared credential lifecycle verifier, Fabric receipts, proof-check service, and privacy requirements. Preserve unrelated changes.

Add an unauthenticated read-only verification endpoint addressed by the high-entropy share token. Hash the presented token before lookup and return the same generic not-found response for unknown, expired, and revoked links. For each selected credential, perform a live lifecycle verification covering signature validity, issuer key, historical advisor authority, Fabric transaction integrity, current revocation/supersession state, and proof availability. Return only the approved public fields: student identity, competition, award/result, issuing advisor and club, issue time, current credential state, and appropriately minimized proof status. Do not expose emails, provider IDs, internal database IDs where avoidable, OAuth data, archived paths, or stack traces. Add cache controls that prevent shared responses from being stored publicly and add abuse-resistant rate limiting.

Add tests for valid, expired, revoked, and unknown tokens; selected-record isolation; live revocation after link creation; supersession; tampered receipt; proof-provider outage; privacy; rate limiting; and generic error behavior. Run tests. Do not commit automatically. Give exact targeted git add, a full quoted git commit command, and git push.

Use this commit title: feat(verification): add public live verification API
```

### Commit 3.4 - Share and verifier interface

```text
Implement the next InstiChain commit: student sharing and public verifier UI.

Inspect the frontend framework, design system, routing, API client, authentication states, and current responsive layout. Preserve unrelated changes. Follow the established visual language and use the installed icon library rather than custom SVG icons.

Build a student sharing view that lists owned credentials, supports explicit multi-selection, creates bounded-expiry links, displays the returned link only at creation, generates a QR code with a maintained library, lists existing shares without secret tokens, and revokes links with confirmation. Add a public verification route that needs no session and clearly separates authentic/historically authorized/current lifecycle/proof availability results. Include useful loading, empty, expired, revoked, unavailable, and partial-proof states without exposing implementation secrets. Make layouts usable on mobile and desktop, accessible by keyboard, and stable without overlapping text or controls.

Add frontend unit/component tests and browser tests for create-copy-QR-revoke and public verification states. Use screenshots at mobile and desktop widths to check layout. Run frontend tests and build. Do not commit automatically. Return changed files plus exact targeted git add, one complete quoted git commit command, and git push.

Use this commit title: feat(frontend): add credential sharing and verification
```

## Task 4: Ledger Assurance And Recovery

### Commit 4.1 - Authority key lifecycle

```text
Implement the next InstiChain commit: authority key rotation and compromise handling.

Inspect advisor onboarding, Ed25519 public-key storage, appointments, signature verification, Fabric events, and credential historical verification. Preserve unrelated working-tree changes.

Add a controlled key-rotation workflow that verifies possession of the new key and records an immutable KEY_ROTATED event on Fabric before changing the PostgreSQL projection. Retain old public keys and their validity intervals so historical signatures remain verifiable. Add an administrator-controlled compromise/revocation path with a required reason and immutable KEY_REVOKED event. New signatures from an inactive key must fail; historical credentials signed before a normal rotation should remain valid. For compromised keys, report the affected review interval explicitly rather than rewriting history. Use idempotency and transaction rollback patterns already established in the project.

Add migrations as needed and tests for normal rotation, proof of new-key possession, invalid signature, duplicate request, old-key rejection for new work, historical verification, compromise flagging, unauthorized administration, and Fabric failure rollback. Run tests. Do not commit automatically. Provide exact targeted git add, a complete quoted commit command, and git push.

Use this commit title: feat(keys): add authority key rotation and revocation
```

### Commit 4.2 - Signed ledger checkpoints

```text
Implement the next InstiChain commit: signed ledger checkpoints and exports.

Review the Fabric gateway, receipt projections, authority keys, configuration, and storage abstraction. Preserve unrelated changes.

Implement an administrator-triggered checkpoint that captures the Fabric channel/chaincode identity, latest confirmed block or transaction position, deterministic digest of the covered institutional event sequence, creation time, and previous checkpoint reference. Sign the checkpoint with a dedicated configured institutional audit key, verify it immediately, persist its metadata in PostgreSQL, and write a portable JSON export through a configurable archival storage interface. Do not include OAuth secrets, session data, share tokens, or private keys. Add a CLI command to create and independently verify an exported checkpoint. Document key generation and secure deployment configuration without committing real keys.

Add deterministic serialization, signature, chain-link, tamper, missing-export, authorization, and redaction tests. Run tests and exercise the CLI against the test configuration. Do not commit automatically. Return exact targeted staging commands, a full quoted git commit command, and git push.

Use this commit title: feat(ledger): add signed audit checkpoints
```

### Commit 4.3 - Projection replay and rebuild

```text
Implement the next InstiChain commit: rebuild PostgreSQL projections from Fabric.

Inspect every institutional Fabric event type, its PostgreSQL projection, migrations, checkpoint format, and service invariants. Preserve unrelated changes.

Create a replay engine and CLI that reads Fabric events in deterministic ledger order, validates transaction receipts and event schemas, and rebuilds institutional query projections for clubs, appointments, competitions, results, credentials, revocations, supersessions, and key history. Authentication users, sessions, encrypted provider connections, proof bytes, and share links are private operational data and must not be erased or reconstructed from Fabric. Support dry-run validation with counts/differences and a controlled rebuild mode that uses staging tables or an equivalent atomic approach so readers never observe a half-built projection. Stop on unknown or invalid events with actionable diagnostics.

Add fixture-ledger tests for full replay, idempotent replay, unknown events, invalid ordering, interrupted rebuild, atomic replacement, projection comparison, and preservation of private tables. Add an operator runbook. Run the replay and migration tests. Do not commit automatically. Provide exact targeted git add commands, the complete quoted commit command, and git push.

Use this commit title: feat(ledger): rebuild projections from Fabric events
```

### Commit 4.4 - Audit and disaster-recovery validation

```text
Implement the next InstiChain commit: ledger audit and disaster-recovery validation.

Read the checkpoint, replay, Fabric receipt, backup, and configuration code. Preserve unrelated working-tree changes.

Add an audit CLI that compares Fabric event order and payload digests with PostgreSQL projections and signed checkpoints, detecting modification, deletion, insertion, reordering, invalid signatures, broken checkpoint chains, and missing receipts. Produce machine-readable JSON plus a concise human report without leaking secrets. Add scripted recovery validation that restores or initializes a clean PostgreSQL database, replays institutional projections, verifies the latest checkpoint, and confirms private operational data is restored only from its separate backup process. Keep destructive recovery commands explicitly guarded and restricted to configured test/recovery databases.

Add tamper-detection and recovery integration tests, including a deliberately corrupted projection, truncated receipt set, invalid checkpoint signature, interrupted replay, and successful clean recovery. Expand the operator runbook with backup boundaries and commands. Run all relevant tests. Do not commit automatically. Return exact targeted staging commands, one complete quoted git commit command, and git push.

Use this commit title: test(ledger): validate audit and disaster recovery
```

## Task 5: Complete Frontend, Security, And Demo

### Commit 5.1 - Role-aware application shell

```text
Implement the next InstiChain commit: role-aware application shell and local signing UI.

Inspect the current frontend, session API, admin screens, backend role rules, and canonical signing endpoints. Preserve unrelated changes and retain the existing design language.

Replace the minimal single-purpose flow with a compact role-aware application shell for student, club head, advisor, and administrator capabilities. Navigation and route guards must derive from the server session, while the backend remains authoritative. Add an advisor signing component that accepts an encrypted Ed25519 private key locally, unlocks it with a passphrase in browser memory, signs the exact canonical bytes returned by prepare endpoints, and submits only the signature. Use a maintained cryptography library compatible with the stored key format. Never upload, log, persist, or place the private key/passphrase in analytics or application state storage. Clear sensitive memory/state after signing or logout.

Add accessible loading/error/unauthorized states and frontend tests for role navigation, route guards, successful local signing, wrong passphrase, malformed key, logout cleanup, and assertions that network requests never contain key material. Run frontend tests and production build. Do not commit automatically. Provide exact targeted git add, a complete quoted commit command, and git push.

Use this commit title: feat(frontend): add role-aware shell and local signing
```

### Commit 5.2 - Operational dashboards

```text
Implement the next InstiChain commit: advisor, club-head, and student operational dashboards.

Inspect all existing backend APIs and the role-aware frontend shell before editing. Preserve unrelated changes. Build the actual working application, not a marketing or explanatory landing page.

Add an advisor dashboard for assigned clubs, pending/issued role actions, achievement issuance, correction, revocation, and history. Add a club-head workspace for competition creation, team/results entry, proof selection/import, and submission status. Add a student vault for credential status, lifecycle history, proof health, and selective sharing. Use dense, clear tables/forms and established icons; include confirmation for destructive lifecycle actions, validation near inputs, stable loading/empty/error states, and responsive layouts that do not overlap. Every action must call real existing APIs and enforce server permissions rather than relying on hidden controls.

Add component and integration tests for primary workflows and role isolation. Check mobile and desktop screenshots for each dashboard, run the frontend test suite and production build, and fix discovered layout issues. Do not commit automatically. Report changed files and provide exact targeted git add, a full quoted git commit command, and git push.

Use this commit title: feat(frontend): add institutional workflow dashboards
```

### Commit 5.3 - End-to-end workflow coverage

```text
Implement the next InstiChain commit: complete browser-level workflow coverage.

Inspect the current test stack, Docker Compose services, seed utilities, Mailpit setup, Fabric test network, and frontend routes. Preserve unrelated changes.

Create deterministic end-to-end tests for the real user journey: institute-email login through Mailpit, administrator bootstrap, club creation, advisor onboarding, club-head role grant, competition creation, Drive proof import through the fake test provider, immutable result submission, advisor-signed achievement issuance, student vault display, selective share creation, public verification, revocation, and live verifier status change. Add isolated test data setup/cleanup and stable selectors. Exercise failure cases for expired login link, unauthorized role, Fabric unavailable, provider unavailable, invalid signature, and revoked share. Do not weaken production security to make tests pass.

Add Makefile commands for bringing up the test dependencies and running the E2E suite if equivalent commands do not already exist. Run the tests or clearly report any environment-specific dependency that prevents execution. Do not commit automatically. Return exact targeted git add commands, the complete git commit command in quotes, and git push.

Use this commit title: test(e2e): cover the complete credential journey
```

### Commit 5.4 - Security, performance, and demo release

```text
Implement the final InstiChain commit: security hardening, performance validation, and demo readiness.

Audit the whole repository, current configuration templates, Makefile, Docker Compose stack, logging, API middleware, migrations, Fabric setup, backup/replay tooling, and documentation. Preserve unrelated user changes unless they directly conflict with correctness.

Add or complete production-minded security controls: strict allowed origins/hosts, secure cookie settings by environment, CSRF enforcement for cookie-authenticated mutations, endpoint-specific rate limits, request size limits, safe headers, dependency/config validation, and systematic secret/PII redaction. Add a deterministic seed command for at least 10,000 credentials and a load test for 50 concurrent public verification requests, reporting p50/p95/error rate while excluding cloud-download latency as specified by the design. Add one-command demo startup/status/stop targets that cover PostgreSQL, Mailpit, Fabric, backend, and frontend without assuming systemd. Finish setup, architecture, troubleshooting, backup/recovery, test-account, and professor-demo instructions. Do not claim a performance target passed unless it was actually measured.

Run backend tests, frontend tests/build, E2E tests where the environment permits, the audit/checkpoint verification, and the load test. Clearly record executed results and remaining environmental limits. Do not commit automatically. Provide exact narrowly scoped git add commands, a detailed complete git commit command with its entire message inside quotes, and git push.

Use this commit title: chore(release): harden and document the demo system
```

## Recommended Execution Order

1. Complete all four Credential Lifecycle commits.
2. Complete all four Vault and Cloud Proofs commits.
3. Complete all four Selective Sharing and Public Verification commits.
4. Complete all four Ledger Assurance and Recovery commits.
5. Complete all four Frontend, Security, and Demo commits.

After every commit, run `git status --short`, inspect `git diff --cached`, commit only the intended files, and push before starting the next prompt.
