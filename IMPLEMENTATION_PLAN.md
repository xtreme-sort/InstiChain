# InstiChain Implementation Plan

Source: [InstiChain Design Document](InstiChain_Design_Document.pdf).

Build in this order: accounts -> club authority -> competition submissions -> signed issuance -> proof storage -> public verification -> recovery and testing.

Proposed stack: React, FastAPI and PostgreSQL. Use one authoritative append-only ledger writer; mining, cryptocurrency and distributed consensus are not required.


## Week 1: Project Foundation and Accounts

| Order | Feature | Example Commit Message |
| --- | --- | --- |
| 1 | Project structure, frontend/backend startup, configuration template and setup instructions | `chore: scaffold frontend and backend` |
| 2 | Database migrations for users, clubs, appointments, competitions and credentials; initial ledger schema | `feat: add initial database schema` |
| 3 | Institute email validation and short-lived, single-use verification links or OTPs | `feat: add institute email verification` |
| 4 | Login, logout, sessions and a basic account page; email verification grants no elevated role | `feat: add authenticated account sessions` |
| 5 | Controlled admin bootstrap, club creation and advisor onboarding; check email delivery and Drive test-account access early | `feat: add club and advisor onboarding` |

Completion demo: verify an institute email, log in and show a club with its trusted advisor.

Identity checks must accept `iitm.ac.in` and its subdomains using an exact domain-boundary check, reject lookalikes and keep stable internal user IDs separate from email addresses.

No passwords exist anywhere in this design. Item 4 (login/sessions) reuses the same
single-use email-link mechanism as item 3: an already-verified user requesting a new
link receives a sign-in link instead of a verification link, and confirming it opens a
session. Sessions are server-side rows referenced by a random token in an httpOnly
cookie, so a session can be revoked immediately (logout, compromise) without waiting on
token expiry — consistent with rechecking authority on every protected request.

## Week 2: Club Authority and Result Submission

| Order | Feature | Example Commit Message |
| --- | --- | --- |
| 6 | Shared transactional ledger writer and appointment events; record authority changes through it from the start | `feat: record authority changes in append-only ledger` |
| 7 | Advisor grants and revokes dated club appointments; enforce club scope and active terms on every protected request | `feat: enforce club appointment permissions` |
| 8 | Club dashboard and competition creation/editing for active office bearers | `feat: add competition management` |
| 9 | Individual and team result drafts; resolve recipients to verified accounts | `feat: add competition result drafts` |
| 10 | Minimal private proof-storage adapter and SHA-256 hashing; freeze numbered submissions for advisor review | `feat: freeze result submissions with proof digests` |

Completion demo: appoint a head, create a competition and submit frozen results; demonstrate a denied cross-club action.

The small storage adapter lets you hash actual evidence before implementing signing. Week 4 connects it to real Drive storage. Recording appointment events early avoids reconstructing authority history later.

## Week 3: Signed Approval and Credential Issuance

| Order | Feature | Example Commit Message |
| --- | --- | --- |
| 11 | Advisor key registration and browser signing using passphrase-encrypted private keys, JSON Canonicalization Scheme and Ed25519 | `feat: add advisor credential signing` |
| 12 | Review/reject submissions and approve the exact frozen version; sign each recipient's credential | `feat: add advisor submission review` |
| 13 | Validate signatures and current authority; atomically issue credentials with ledger entries and retry protection | `feat: issue signed credentials atomically` |
| 14 | Ledger integrity checks, historical authority validation and rebuilding current state through replay | `feat: validate and replay credential ledger` |
| 15 | Student achievement list and detail page showing issued credentials | `feat: display student achievements` |

Completion demo: approve a submission and show signed credentials in student accounts; retry without duplicates.

Use maintained cryptography libraries. Serialize authority changes and issuance under the same database transaction and ledger lock. Team results produce one credential per verified member with a common team/result ID. Changes to signed submission fields require new approval.

## Week 4: Vault and Real Cloud Proofs

| Order | Feature | Example Commit Message |
| --- | --- | --- |
| 16 | Google Drive connection separate from institute login; file-specific access and encrypted token storage | `feat: connect Google Drive storage` |
| 17 | Private student vault uploads, file selection and ownership checks; personal uploads remain unverified | `feat: add private student document vault` |
| 18 | Retain exact issuance proof bytes in the club archive; provide student references and report missing or changed files | `feat: archive and validate issuance proofs` |

Completion demo: retrieve archived proof and demonstrate a missing file or hash mismatch.

Use the `drive.file` OAuth scope. Keep refresh tokens encrypted on the server and out of share links. Store proof digests and opaque IDs in the ledger, with private storage references outside it.

## Week 5: Sharing and Credential Lifecycle

| Order | Feature | Example Commit Message |
| --- | --- | --- |
| 19 | Expiring, revocable share links for selected achievements and permitted proofs; QR codes | `feat: add scoped achievement sharing` |
| 20 | Public verification page showing authenticity, current status and proof availability separately, with check time | `feat: add public credential verification` |
| 21 | Advisor revocation and corrections using replacement credentials and explicit supersession | `feat: add credential revocation and replacement` |
| 22 | Annual club handover, advisor replacement, key rotation and recorded compromise/recovery | `feat: support club succession and key recovery` |

Completion demo: open a share link, revoke a credential and show the updated status; replace a club head.

External verifiers need no institute account. Links must expose only selected records and permitted proofs. Retain historical public keys. Ending an appointment must not invalidate earlier achievements unless they are separately revoked.

## Week 6: Auditability and Final Validation

| Order | Feature | Example Commit Message |
| --- | --- | --- |
| 23 | Signed ledger checkpoints exported to a separately controlled archive; enforce ledger write restrictions | `feat: add independent ledger checkpoints` |
| 24 | Full browser workflow plus privacy, tampering, issuance/revocation race and duplicate-retry checks | `test: cover verification and authorization failures` |
| 25 | Backup restoration and replay validation; load test with 10,000 credentials and 50 concurrent verification requests | `test: validate recovery and verification performance` |
| 26 | Fix discovered issues; finish setup guide, seeded demo data and demonstration script | `docs: document setup and final demonstration` |

Completion demo: run the complete workflow, including tampering detection, recovery and recorded performance results.

Check modified, reordered and deleted ledger entries against retained checkpoints. Deny ordinary application UPDATE and DELETE privileges on ledger rows. Target 95% of verification requests completing within two seconds, excluding cloud-file downloads; record the environment and actual results.

## Scope and Priorities

- Final acceptance: a complete competition-to-resume-verification demo, including tampering and team handover cases.
- Retain faculty approval, revocation, proof hashing and access control if time is constrained.
- Defer bulk import, notifications and extra visual polish until the complete workflow works.
- Keep grades, course enrolment and the institute directory out of this version.
- Recorded authorization cannot establish that a competition result was truthful; institutional onboarding, advisor judgment and the operating server remain trust assumptions.

