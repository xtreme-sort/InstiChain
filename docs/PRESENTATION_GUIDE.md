# InstiChain Presentation Guide

## 1. Project Idea

InstiChain is an institute credential and club-management system. It allows:

- Institute users to sign in using a verified institute email.
- Administrators to create clubs and appoint faculty advisors.
- Advisors to grant and revoke club positions such as club head and office bearer.
- Club officers to record competitions and results.
- Advisors to approve and issue achievement credentials.
- External verifiers to inspect blockchain events and revocation status.

## 2. Architecture

```text
React frontend
      |
Spring Boot Java API
      |
      +-- PostgreSQL
      |     Users, login sessions, private data, drafts, and projections
      |
      +-- Hyperledger Fabric
      |     Authoritative institutional events
      |
      +-- SMTP / Mailpit
            Email verification and login links
```

The complete architecture map is in [ARCHITECTURE.md](ARCHITECTURE.md).

## 3. Authentication

Users enter an `iitm.ac.in` email address and receive a short-lived, single-use
link.

Implemented protections:

- Only IITM domains and proper subdomains are accepted.
- Tokens expire after 15 minutes.
- Only token hashes are stored.
- A token can be used only once.
- Resend rate limits are enforced.
- Returning users cannot change their saved name through another login.
- Login sessions persist using an HTTP-only cookie.
- Logout revokes the server-side session.
- The browser generates an Ed25519 signing identity at first registration.
- The non-exportable private key remains in browser IndexedDB.
- Only the public key and its fingerprint are registered with the backend.

Relevant code:

- [EmailAuthController.java](../backend/src/main/java/edu/iitm/instichain/auth/EmailAuthController.java)
- [InstituteEmail.java](../backend/src/main/java/edu/iitm/instichain/auth/InstituteEmail.java)
- [SessionService.java](../backend/src/main/java/edu/iitm/instichain/auth/SessionService.java)
- [MailService.java](../backend/src/main/java/edu/iitm/instichain/auth/MailService.java)
- [SigningKeyController.java](../backend/src/main/java/edu/iitm/instichain/auth/SigningKeyController.java)
- [SigningIdentity.tsx](../frontend/src/SigningIdentity.tsx)

Login and user data are deliberately not stored on the blockchain because they
are private, temporary, and operational.

## 4. Administrator And Club Management

An ordinary signup never becomes an administrator automatically. The first
administrator is established using a server-side CLI after institutional
verification. There is no HTTP endpoint that can promote a user.

The administrator can:

- Create clubs.
- View existing clubs.
- Use an advisor's account-registered Ed25519 public key.
- Appoint verified faculty advisors for fixed time periods.
- Prevent overlapping advisor appointments.

Relevant code:

- [OperatorCli.java](../backend/src/main/java/edu/iitm/instichain/cli/OperatorCli.java)
- [AdminBootstrapService.java](../backend/src/main/java/edu/iitm/instichain/cli/AdminBootstrapService.java)
- [AdminController.java](../backend/src/main/java/edu/iitm/instichain/domain/AdminController.java)

Blockchain events produced here:

- `ADMIN_BOOTSTRAPPED`
- `CLUB_CREATED`
- `ADVISOR_APPOINTED`

## 5. Advisor Role Management

An active faculty advisor can grant these club-scoped roles:

- `club_head`
- `office_bearer`

The workflow has two steps:

1. The backend prepares a canonical role document.
2. The advisor signs it using their Ed25519 private key and submits it.

The backend verifies the signature using the advisor's registered public key
before sending the event to Fabric.

Advisors can also revoke an active role. Revocation does not delete the original
appointment; it appends a `ROLE_REVOKED` event.

Relevant code:

- [AdvisorController.java](../backend/src/main/java/edu/iitm/instichain/domain/AdvisorController.java)
- [AccessService.java](../backend/src/main/java/edu/iitm/instichain/domain/AccessService.java)
- [CanonicalJson.java](../backend/src/main/java/edu/iitm/instichain/common/CanonicalJson.java)
- [Ed25519.java](../backend/src/main/java/edu/iitm/instichain/common/Ed25519.java)

## 6. Competitions And Results

Club heads and office bearers can:

- Create competitions.
- View competitions belonging to their club.
- Submit results with an award and proof digest.
- Attach verified students as recipients.
- Create immutable corrected result versions.

The proof digest is a SHA-256 hash representing supporting evidence. The actual
file is not placed on the blockchain.

Relevant code:

- [CompetitionController.java](../backend/src/main/java/edu/iitm/instichain/domain/CompetitionController.java)
- [ResultController.java](../backend/src/main/java/edu/iitm/instichain/domain/ResultController.java)

## 7. Credential Issuance

A faculty advisor can approve a frozen result version and issue credentials to
all its recipients.

The advisor signs one issuance document containing:

- Competition identity.
- Result and submission version.
- Award.
- Proof digest.
- Recipient IDs.
- Credential IDs.
- Superseded credentials, if applicable.

Fabric records an `ACHIEVEMENT_ISSUED` event. PostgreSQL creates the credential
projections only after Fabric confirms the transaction.

Relevant code:

- [IssuanceController.java](../backend/src/main/java/edu/iitm/instichain/domain/IssuanceController.java)

## 8. How The Blockchain Works

The Java backend connects directly to Hyperledger Fabric using the Fabric
Gateway client.

Relevant code:

- [FabricService.java](../backend/src/main/java/edu/iitm/instichain/fabric/FabricService.java)

For every institutional action:

1. The backend or advisor signs a canonical event.
2. The backend submits it to Fabric.
3. The chaincode validates its structure, identity, and request ID.
4. Org1 and Org2 endorse the transaction.
5. Fabric orders and commits it.
6. PostgreSQL updates its local projection only after confirmation.

If Fabric fails, the PostgreSQL transaction rolls back. Therefore, the
application cannot claim that a club, role, or credential was recorded when
Fabric did not confirm it.

## 9. Java Smart Contract

The chaincode is implemented in
[InstiChainContract.java](../fabric/chaincode/src/main/java/edu/iitm/instichain/chaincode/InstiChainContract.java).

It supports:

- `AppendEvent`
- `GetEvent`
- `GetEvents`
- `GetHead`
- `GetRevocation`
- `GetRevocations`

It provides:

- Ordered sequence numbers.
- Immutable event history.
- Idempotent request IDs.
- Duplicate revocation prevention.
- Revocation indexes.
- Fabric transaction IDs and commit timestamps.

Students are not Fabric nodes. The nodes are institute and auditor organization
peers. Students are identities referenced inside credentials.

## 10. Database

Flyway creates the PostgreSQL schema in
[V1__current_schema.sql](../backend/src/main/resources/db/migration/V1__current_schema.sql).

Important tables include:

- `users`
- `email_verifications`
- `sessions`
- `user_signing_keys`
- `administrator`
- `authority_keys`
- `clubs`
- `appointments`
- `competitions`
- `result_submissions`
- `result_recipients`
- `credentials`
- `fabric_transactions`

PostgreSQL is used for privacy and fast querying. Fabric remains authoritative
for institutional events.

## 11. Current Frontend

The frontend currently includes:

- Email-link signup and login.
- Persistent session handling.
- Logout.
- Administrator access detection.
- Club creation.
- Advisor onboarding and appointment display.

Relevant code:

- [App.tsx](../frontend/src/App.tsx)
- [EmailVerification.tsx](../frontend/src/EmailVerification.tsx)
- [AdminPanel.tsx](../frontend/src/AdminPanel.tsx)

The advisor, competition, result, credential, and public-verification APIs exist
in the backend, but their complete frontend screens are still pending.

## 12. Remaining Work

- Advisor dashboard for role grants and revocations.
- Competition and result-entry screens.
- Credential issuance interface.
- Student credential wallet or profile.
- Public credential verification page.
- Credential revocation API and UI. Chaincode support already exists.
- Full Google Drive OAuth, upload, and evidence retrieval. Only the access
  preflight exists.
- Key rotation and recovery.
- Production Fabric deployment with organizations on separate machines.
- Live end-to-end tests once the complete environment is running.

## Short Presentation Summary

InstiChain uses Spring Boot as a thin application layer, PostgreSQL for private
and operational data, and Hyperledger Fabric for authoritative institutional
records. Users authenticate using single-use institute email links.
Administrators create clubs and appoint verified faculty advisors. Advisors
cryptographically sign role grants, revocations, and achievement issuance.
These events require endorsement from both institute and auditor peers.
PostgreSQL is updated only after Fabric confirms the transaction, providing both
efficient application queries and independently verifiable institutional
history.
