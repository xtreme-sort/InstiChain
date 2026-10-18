# What Is Where

Click any link to jump to the definition. Line numbers reflect the code as of this file's creation.

## Backend: entry points and configuration

| What | Where |
| --- | --- |
| Spring Boot entry point | [InstiChainApplication.main](backend/src/main/java/edu/iitm/instichain/InstiChainApplication.java#L11) |
| Typed settings (`instichain.*`) | [InstiChainProperties](backend/src/main/java/edu/iitm/instichain/config/InstiChainProperties.java#L13) |
| SMTP settings | [InstiChainProperties.Smtp](backend/src/main/java/edu/iitm/instichain/config/InstiChainProperties.java#L25) |
| Fabric settings | [InstiChainProperties.Fabric](backend/src/main/java/edu/iitm/instichain/config/InstiChainProperties.java#L29) |
| Environment bindings | [application.yml](backend/src/main/resources/application.yml) |
| Config template | [backend/.env.example](backend/.env.example) |
| Jackson (JSON) setup | [JacksonConfiguration](backend/src/main/java/edu/iitm/instichain/config/JacksonConfiguration.java#L11) |
| Transaction manager | [TransactionConfig](backend/src/main/java/edu/iitm/instichain/config/TransactionConfig.java#L11) |
| Maven dependencies | [pom.xml](backend/pom.xml) |

## Backend: authentication (`auth/`)

| What | Where |
| --- | --- |
| Email-link controller | [EmailAuthController](backend/src/main/java/edu/iitm/instichain/auth/EmailAuthController.java#L35) |
| `POST /api/auth/email/request` (send link) | [request](backend/src/main/java/edu/iitm/instichain/auth/EmailAuthController.java#L62) |
| `POST /api/auth/email/inspect` (check link) | [inspect](backend/src/main/java/edu/iitm/instichain/auth/EmailAuthController.java#L104) |
| `POST /api/auth/email/confirm` (register / sign in) | [confirm](backend/src/main/java/edu/iitm/instichain/auth/EmailAuthController.java#L114) |
| IITM domain check | [InstituteEmail.normalize](backend/src/main/java/edu/iitm/instichain/auth/InstituteEmail.java#L14) |
| Sending email | [MailService.sendVerification](backend/src/main/java/edu/iitm/instichain/auth/MailService.java#L18) |
| Session lookup / creation | [SessionService](backend/src/main/java/edu/iitm/instichain/auth/SessionService.java#L21) · [requireUser](backend/src/main/java/edu/iitm/instichain/auth/SessionService.java#L33) · [create](backend/src/main/java/edu/iitm/instichain/auth/SessionService.java#L49) · [revoke](backend/src/main/java/edu/iitm/instichain/auth/SessionService.java#L60) |
| Same-origin check | [SessionService.requireSameOrigin](backend/src/main/java/edu/iitm/instichain/auth/SessionService.java#L82) |
| `GET /api/auth/session`, `POST /api/auth/logout` | [SessionController](backend/src/main/java/edu/iitm/instichain/auth/SessionController.java#L24) · [logout](backend/src/main/java/edu/iitm/instichain/auth/SessionController.java#L32) |
| Browser signing-key API | [SigningKeyController](backend/src/main/java/edu/iitm/instichain/auth/SigningKeyController.java#L21) |
| Signing-key storage | [SigningKeyService.register](backend/src/main/java/edu/iitm/instichain/auth/SigningKeyService.java#L40) · [findActive](backend/src/main/java/edu/iitm/instichain/auth/SigningKeyService.java#L27) |
| User record | [UserAccount](backend/src/main/java/edu/iitm/instichain/auth/UserAccount.java#L6) |

## Backend: domain (`domain/`)

| What | Where |
| --- | --- |
| Role and permission checks | [AccessService](backend/src/main/java/edu/iitm/instichain/domain/AccessService.java#L21) · [admin](backend/src/main/java/edu/iitm/instichain/domain/AccessService.java#L39) · [club](backend/src/main/java/edu/iitm/instichain/domain/AccessService.java#L70) · [activeAppointments](backend/src/main/java/edu/iitm/instichain/domain/AccessService.java#L54) |
| Origin check + ledger lock on writes | [AccessService.mutationGuard](backend/src/main/java/edu/iitm/instichain/domain/AccessService.java#L83) |
| Admin API | [AdminController](backend/src/main/java/edu/iitm/instichain/domain/AdminController.java#L34) |
| List / create clubs | [GET /clubs](backend/src/main/java/edu/iitm/instichain/domain/AdminController.java#L61) · [POST /clubs](backend/src/main/java/edu/iitm/instichain/domain/AdminController.java#L103) |
| Appoint advisor | [POST /clubs/{id}/advisors](backend/src/main/java/edu/iitm/instichain/domain/AdminController.java#L133) |
| Build a ledger event document | [AdminController.action](backend/src/main/java/edu/iitm/instichain/domain/AdminController.java#L228) |
| Advisor API | [AdvisorController](backend/src/main/java/edu/iitm/instichain/domain/AdvisorController.java#L38) |
| Club role register | [GET /clubs/{id}/roles](backend/src/main/java/edu/iitm/instichain/domain/AdvisorController.java#L88) |
| Role grant (prepare / submit) | [prepare](backend/src/main/java/edu/iitm/instichain/domain/AdvisorController.java#L117) · [submit](backend/src/main/java/edu/iitm/instichain/domain/AdvisorController.java#L147) |
| Role revocation (prepare / submit) | [prepare](backend/src/main/java/edu/iitm/instichain/domain/AdvisorController.java#L207) · [submit](backend/src/main/java/edu/iitm/instichain/domain/AdvisorController.java#L230) |
| Advisor key lookup / signature check | [advisorKey](backend/src/main/java/edu/iitm/instichain/domain/AdvisorController.java#L278) · [signature](backend/src/main/java/edu/iitm/instichain/domain/AdvisorController.java#L292) |
| Competitions | [CompetitionController](backend/src/main/java/edu/iitm/instichain/domain/CompetitionController.java#L26) · [list](backend/src/main/java/edu/iitm/instichain/domain/CompetitionController.java#L45) · [create](backend/src/main/java/edu/iitm/instichain/domain/CompetitionController.java#L62) |
| Results and versions | [ResultController](backend/src/main/java/edu/iitm/instichain/domain/ResultController.java#L32) · [create](backend/src/main/java/edu/iitm/instichain/domain/ResultController.java#L81) · [new version](backend/src/main/java/edu/iitm/instichain/domain/ResultController.java#L95) |
| Credential issuance | [IssuanceController](backend/src/main/java/edu/iitm/instichain/domain/IssuanceController.java#L34) · [prepare](backend/src/main/java/edu/iitm/instichain/domain/IssuanceController.java#L60) · [issue](backend/src/main/java/edu/iitm/instichain/domain/IssuanceController.java#L70) · [document builder](backend/src/main/java/edu/iitm/instichain/domain/IssuanceController.java#L115) |

## Backend: Fabric (`fabric/`)

| What | Where |
| --- | --- |
| Gateway client | [FabricService](backend/src/main/java/edu/iitm/instichain/fabric/FabricService.java#L37) · [submit](backend/src/main/java/edu/iitm/instichain/fabric/FabricService.java#L50) · [evaluate](backend/src/main/java/edu/iitm/instichain/fabric/FabricService.java#L71) · [connection setup](backend/src/main/java/edu/iitm/instichain/fabric/FabricService.java#L80) |
| Append event + save receipt | [LedgerEventService.append](backend/src/main/java/edu/iitm/instichain/fabric/LedgerEventService.java#L49) · [appendAdmin](backend/src/main/java/edu/iitm/instichain/fabric/LedgerEventService.java#L35) · [lock](backend/src/main/java/edu/iitm/instichain/fabric/LedgerEventService.java#L31) |
| Public ledger API (`/api/ledger`) | [LedgerController](backend/src/main/java/edu/iitm/instichain/fabric/LedgerController.java#L23) · [events](backend/src/main/java/edu/iitm/instichain/fabric/LedgerController.java#L41) · [revocations](backend/src/main/java/edu/iitm/instichain/fabric/LedgerController.java#L55) |
| Fabric failure exception | [FabricUnavailableException](backend/src/main/java/edu/iitm/instichain/fabric/FabricUnavailableException.java#L3) |

## Backend: common utilities (`common/`)

| What | Where |
| --- | --- |
| Canonical (sorted) JSON for signing | [CanonicalJson](backend/src/main/java/edu/iitm/instichain/common/CanonicalJson.java#L14) · [bytes](backend/src/main/java/edu/iitm/instichain/common/CanonicalJson.java#L25) |
| Ed25519 sign / verify / load key | [Ed25519](backend/src/main/java/edu/iitm/instichain/common/Ed25519.java#L20) · [readPrivateKey](backend/src/main/java/edu/iitm/instichain/common/Ed25519.java#L26) · [sign](backend/src/main/java/edu/iitm/instichain/common/Ed25519.java#L71) · [verify](backend/src/main/java/edu/iitm/instichain/common/Ed25519.java#L78) |
| SHA-256 helpers | [Hashing](backend/src/main/java/edu/iitm/instichain/common/Hashing.java#L7) |
| Deterministic UUIDs | [UuidV5.url](backend/src/main/java/edu/iitm/instichain/common/UuidV5.java#L14) |
| API errors | [ApiException](backend/src/main/java/edu/iitm/instichain/common/ApiException.java#L5) · [ApiErrorHandler](backend/src/main/java/edu/iitm/instichain/common/ApiErrorHandler.java#L12) |
| Health endpoint | [HealthController](backend/src/main/java/edu/iitm/instichain/web/HealthController.java#L9) |

## Backend: operator CLI (`cli/`)

| What | Where |
| --- | --- |
| CLI entry (keygen, admin, smtp, drive) | [OperatorCli.main](backend/src/main/java/edu/iitm/instichain/cli/OperatorCli.java#L27) |
| Admin bootstrap | [AdminBootstrapService.bootstrap](backend/src/main/java/edu/iitm/instichain/cli/AdminBootstrapService.java#L32) |
| SMTP / Drive preflight | [IntegrationPreflightService](backend/src/main/java/edu/iitm/instichain/cli/IntegrationPreflightService.java#L19) |
| Admin setup guide | [ADMIN_SETUP.md](backend/ADMIN_SETUP.md) |

## Database (Flyway)

| Table | Where |
| --- | --- |
| `users` | [V1](backend/src/main/resources/db/migration/V1__current_schema.sql#L1) |
| `clubs` | [V1](backend/src/main/resources/db/migration/V1__current_schema.sql#L10) |
| `ledger_entries` (legacy) | [V1](backend/src/main/resources/db/migration/V1__current_schema.sql#L21) |
| `email_verifications` | [V1](backend/src/main/resources/db/migration/V1__current_schema.sql#L49) |
| `sessions` | [V1](backend/src/main/resources/db/migration/V1__current_schema.sql#L64) |
| `authority_keys` | [V1](backend/src/main/resources/db/migration/V1__current_schema.sql#L77) |
| `fabric_transactions` | [V1](backend/src/main/resources/db/migration/V1__current_schema.sql#L92) |
| `administrator` | [V1](backend/src/main/resources/db/migration/V1__current_schema.sql#L120) |
| `appointments` | [V1](backend/src/main/resources/db/migration/V1__current_schema.sql#L130) |
| `competitions` | [V1](backend/src/main/resources/db/migration/V1__current_schema.sql#L155) |
| `competition_results` | [V1](backend/src/main/resources/db/migration/V1__current_schema.sql#L169) |
| `result_submissions` | [V1](backend/src/main/resources/db/migration/V1__current_schema.sql#L177) |
| `result_recipients` | [V1](backend/src/main/resources/db/migration/V1__current_schema.sql#L192) |
| `credentials` | [V1](backend/src/main/resources/db/migration/V1__current_schema.sql#L199) |
| Immutability triggers | [V1](backend/src/main/resources/db/migration/V1__current_schema.sql#L229) |
| `user_signing_keys` | [V2](backend/src/main/resources/db/migration/V2__user_signing_keys.sql#L1) |
| Schema overview | [SCHEMA.md](backend/SCHEMA.md) |

## Chaincode (Fabric smart contract)

| What | Where |
| --- | --- |
| Contract class | [InstiChainContract](fabric/chaincode/src/main/java/edu/iitm/instichain/chaincode/InstiChainContract.java#L26) |
| `AppendEvent` (the only write) | [AppendEvent](fabric/chaincode/src/main/java/edu/iitm/instichain/chaincode/InstiChainContract.java#L39) |
| Reads | [GetEvent](fabric/chaincode/src/main/java/edu/iitm/instichain/chaincode/InstiChainContract.java#L123) · [GetHead](fabric/chaincode/src/main/java/edu/iitm/instichain/chaincode/InstiChainContract.java#L133) · [GetRevocation](fabric/chaincode/src/main/java/edu/iitm/instichain/chaincode/InstiChainContract.java#L139) · [GetRevocations](fabric/chaincode/src/main/java/edu/iitm/instichain/chaincode/InstiChainContract.java#L154) · [GetEvents](fabric/chaincode/src/main/java/edu/iitm/instichain/chaincode/InstiChainContract.java#L169) |
| Build file | [build.gradle](fabric/chaincode/build.gradle) |
| Network guide | [fabric/README.md](fabric/README.md) |

## Frontend (`frontend/src/`)

| What | Where |
| --- | --- |
| App shell | [App](frontend/src/App.tsx#L10) |
| Login / registration UI | [EmailVerification](frontend/src/EmailVerification.tsx#L40) · [Account type](frontend/src/EmailVerification.tsx#L7) |
| Admin panel | [AdminPanel](frontend/src/AdminPanel.tsx#L33) |
| Advisor panel | [AdvisorPanel](frontend/src/AdvisorPanel.tsx#L64) · [signAndSubmit](frontend/src/AdvisorPanel.tsx#L108) |
| Signing identity display | [SigningIdentity](frontend/src/SigningIdentity.tsx#L8) |
| Browser key generation / signing | [generateSigningIdentity](frontend/src/signingIdentity.ts#L62) · [saveSigningIdentity](frontend/src/signingIdentity.ts#L75) · [loadOrProvisionSigningIdentity](frontend/src/signingIdentity.ts#L124) · [signPreparedPayload](frontend/src/signingIdentity.ts#L132) |
| Styles | [styles.css](frontend/src/styles.css) |
| Dev server and API proxy | [vite.config.ts](frontend/vite.config.ts) |

## Data types (records, nested classes, TS types)

Most "model" classes are Java `record`s nested inside the service or controller that uses them.

### Backend records

| Type | What it holds | Where |
| --- | --- | --- |
| `UserAccount` | id, email, display name, verified time | [auth/UserAccount.java](backend/src/main/java/edu/iitm/instichain/auth/UserAccount.java#L6) |
| `AccessService.Administrator` | the admin user + admin key id + public key | [AccessService.java](backend/src/main/java/edu/iitm/instichain/domain/AccessService.java#L107) |
| `AccessService.Appointment` | an appointment row joined with its club | [AccessService.java](backend/src/main/java/edu/iitm/instichain/domain/AccessService.java#L108) |
| `AccessService.ClubAuthority` | user + the appointment that authorizes them | [AccessService.java](backend/src/main/java/edu/iitm/instichain/domain/AccessService.java#L112) |
| `SigningKeyService.SigningKey` | a user's browser public key | [SigningKeyService.java](backend/src/main/java/edu/iitm/instichain/auth/SigningKeyService.java#L109) |
| `SigningKeyController.RegisterRequest` | `public_key` request body | [SigningKeyController.java](backend/src/main/java/edu/iitm/instichain/auth/SigningKeyController.java#L30) |
| `EmailAuthController.EmailRequest` | `/request` body | [EmailAuthController.java](backend/src/main/java/edu/iitm/instichain/auth/EmailAuthController.java#L56) |
| `EmailAuthController.TokenRequest` | `/inspect` body | [EmailAuthController.java](backend/src/main/java/edu/iitm/instichain/auth/EmailAuthController.java#L57) |
| `EmailAuthController.ConfirmRequest` | `/confirm` body | [EmailAuthController.java](backend/src/main/java/edu/iitm/instichain/auth/EmailAuthController.java#L58) |
| `EmailAuthController.Verification` | an email-verification row | [EmailAuthController.java](backend/src/main/java/edu/iitm/instichain/auth/EmailAuthController.java#L223) |
| `AdminController.ClubRequest` | create-club body | [AdminController.java](backend/src/main/java/edu/iitm/instichain/domain/AdminController.java#L45) |
| `AdminController.AdvisorRequest` | appoint-advisor body | [AdminController.java](backend/src/main/java/edu/iitm/instichain/domain/AdminController.java#L49) |
| `AdvisorController.RoleDraft` | role-grant prepare body | [AdvisorController.java](backend/src/main/java/edu/iitm/instichain/domain/AdvisorController.java#L53) |
| `AdvisorController.RevocationDraft` | revocation prepare body | [AdvisorController.java](backend/src/main/java/edu/iitm/instichain/domain/AdvisorController.java#L57) |
| `AdvisorController.SignedDocument` | signed document + signature | [AdvisorController.java](backend/src/main/java/edu/iitm/instichain/domain/AdvisorController.java#L58) |
| `AdvisorController.Key` | advisor authority key | [AdvisorController.java](backend/src/main/java/edu/iitm/instichain/domain/AdvisorController.java#L60) |
| `AdvisorController.AppointmentRow` | club role row | [AdvisorController.java](backend/src/main/java/edu/iitm/instichain/domain/AdvisorController.java#L384) |
| `CompetitionController.CompetitionRequest` | create-competition body | [CompetitionController.java](backend/src/main/java/edu/iitm/instichain/domain/CompetitionController.java#L37) |
| `CompetitionController.CompetitionResponse` | competition response | [CompetitionController.java](backend/src/main/java/edu/iitm/instichain/domain/CompetitionController.java#L41) |
| `ResultController.SubmissionRequest` | result submission body | [ResultController.java](backend/src/main/java/edu/iitm/instichain/domain/ResultController.java#L43) |
| `ResultController.Recipient` | result recipient | [ResultController.java](backend/src/main/java/edu/iitm/instichain/domain/ResultController.java#L48) |
| `ResultController.Submission` | result submission response | [ResultController.java](backend/src/main/java/edu/iitm/instichain/domain/ResultController.java#L49) |
| `IssuanceController.SignedDocument` | signed issuance body | [IssuanceController.java](backend/src/main/java/edu/iitm/instichain/domain/IssuanceController.java#L49) |
| `IssuanceController.AdvisorKey` | advisor authority key | [IssuanceController.java](backend/src/main/java/edu/iitm/instichain/domain/IssuanceController.java#L51) |
| `IssuanceController.Submission` | frozen submission being issued | [IssuanceController.java](backend/src/main/java/edu/iitm/instichain/domain/IssuanceController.java#L52) |
| `IssuanceController.Claim` | one credential claim | [IssuanceController.java](backend/src/main/java/edu/iitm/instichain/domain/IssuanceController.java#L55) |
| `IssuanceController.Credential` | issued credential row | [IssuanceController.java](backend/src/main/java/edu/iitm/instichain/domain/IssuanceController.java#L56) |
| `FabricService.Receipt` | Fabric commit result | [FabricService.java](backend/src/main/java/edu/iitm/instichain/fabric/FabricService.java#L133) |
| `LedgerEventService.Event` | committed ledger event | [LedgerEventService.java](backend/src/main/java/edu/iitm/instichain/fabric/LedgerEventService.java#L81) |
| `InstiChainProperties` (+ `Smtp`, `Fabric`) | configuration | [InstiChainProperties.java](backend/src/main/java/edu/iitm/instichain/config/InstiChainProperties.java#L13) |
| `OperatorCli.PreflightConfiguration` | Spring context for smtp/drive checks | [OperatorCli.java](backend/src/main/java/edu/iitm/instichain/cli/OperatorCli.java#L105) |

### Frontend types

| Type | Where |
| --- | --- |
| `Account` | [EmailVerification.tsx](frontend/src/EmailVerification.tsx#L7) |
| `ConfirmResult` | [EmailVerification.tsx](frontend/src/EmailVerification.tsx#L8) |
| `Connection` | [App.tsx](frontend/src/App.tsx#L8) |
| `Advisor`, `Club` (admin view) | [AdminPanel.tsx](frontend/src/AdminPanel.tsx#L5) |
| `Club` (advisor view) | [AdvisorPanel.tsx](frontend/src/AdvisorPanel.tsx#L6) |
| `ClubRole` | [AdvisorPanel.tsx](frontend/src/AdvisorPanel.tsx#L16) |
| `PreparedAction` | [AdvisorPanel.tsx](frontend/src/AdvisorPanel.tsx#L29) |
| `GeneratedIdentity` | [signingIdentity.ts](frontend/src/signingIdentity.ts#L1) |
| `SigningIdentityStatus` | [signingIdentity.ts](frontend/src/signingIdentity.ts#L6) |
| `StoredIdentity`, `ServerKey` | [signingIdentity.ts](frontend/src/signingIdentity.ts#L12) |

## Constants

| Constant | Where |
| --- | --- |
| Session cookie name `COOKIE_NAME` | [SessionService.java](backend/src/main/java/edu/iitm/instichain/auth/SessionService.java#L22) |
| Global ledger advisory lock `LEDGER_LOCK` | [LedgerEventService.java](backend/src/main/java/edu/iitm/instichain/fabric/LedgerEventService.java#L17) |
| Role sets `VIEWERS` / `OFFICERS` | [CompetitionController.java](backend/src/main/java/edu/iitm/instichain/domain/CompetitionController.java#L27) · [ResultController.java](backend/src/main/java/edu/iitm/instichain/domain/ResultController.java#L33) |
| Role set `ADVISOR` | [AdvisorController.java](backend/src/main/java/edu/iitm/instichain/domain/AdvisorController.java#L39) · [IssuanceController.java](backend/src/main/java/edu/iitm/instichain/domain/IssuanceController.java#L35) |
| Email regex `ADDRESS` | [InstituteEmail.java](backend/src/main/java/edu/iitm/instichain/auth/InstituteEmail.java#L7) |
| UUIDv5 namespace | [UuidV5.java](backend/src/main/java/edu/iitm/instichain/common/UuidV5.java#L10) |
| Chaincode key prefixes (`event:`, `ledger:head`, `revocation:`) | [InstiChainContract.java](fabric/chaincode/src/main/java/edu/iitm/instichain/chaincode/InstiChainContract.java#L30) |
| Revocable event types `REVOCATION_SUBJECTS` | [InstiChainContract.java](fabric/chaincode/src/main/java/edu/iitm/instichain/chaincode/InstiChainContract.java#L34) |
| IndexedDB names | [signingIdentity.ts](frontend/src/signingIdentity.ts#L19) |

## Helper methods

| Helper | Where |
| --- | --- |
| Email-token lookup / per-email lock / token generation | [findValid](backend/src/main/java/edu/iitm/instichain/auth/EmailAuthController.java#L178) · [lockEmail](backend/src/main/java/edu/iitm/instichain/auth/EmailAuthController.java#L197) · [randomToken](backend/src/main/java/edu/iitm/instichain/auth/EmailAuthController.java#L207) |
| SMTP sender setup | [MailService.sender](backend/src/main/java/edu/iitm/instichain/auth/MailService.java#L47) |
| Public-key decoding / fingerprint | [decodePublicKey](backend/src/main/java/edu/iitm/instichain/auth/SigningKeyService.java#L78) · [fingerprint](backend/src/main/java/edu/iitm/instichain/auth/SigningKeyService.java#L94) |
| Row → `UserAccount` / `Appointment` | [SessionService.mapUser](backend/src/main/java/edu/iitm/instichain/auth/SessionService.java#L90) · [AccessService.mapAppointment](backend/src/main/java/edu/iitm/instichain/domain/AccessService.java#L94) |
| Advisor signed-document helpers | [append](backend/src/main/java/edu/iitm/instichain/domain/AdvisorController.java#L302) · [prepared](backend/src/main/java/edu/iitm/instichain/domain/AdvisorController.java#L309) · [roleAppointment](backend/src/main/java/edu/iitm/instichain/domain/AdvisorController.java#L341) · [userByEmail](backend/src/main/java/edu/iitm/instichain/domain/AdvisorController.java#L323) · [uuid / time parsing](backend/src/main/java/edu/iitm/instichain/domain/AdvisorController.java#L366) |
| Issuance helpers | [submission](backend/src/main/java/edu/iitm/instichain/domain/IssuanceController.java#L168) · [advisorKey](backend/src/main/java/edu/iitm/instichain/domain/IssuanceController.java#L186) · [credentials](backend/src/main/java/edu/iitm/instichain/domain/IssuanceController.java#L199) · [decodeSignature](backend/src/main/java/edu/iitm/instichain/domain/IssuanceController.java#L226) |
| Result save / load | [save](backend/src/main/java/edu/iitm/instichain/domain/ResultController.java#L115) · [submission](backend/src/main/java/edu/iitm/instichain/domain/ResultController.java#L150) |
| Revocation SQL union | [LedgerController.union](backend/src/main/java/edu/iitm/instichain/fabric/LedgerController.java#L104) |
| Key sorting for canonical JSON | [CanonicalJson.sort](backend/src/main/java/edu/iitm/instichain/common/CanonicalJson.java#L45) |
| Ed25519 public key from private key | [Ed25519.rawPublic](backend/src/main/java/edu/iitm/instichain/common/Ed25519.java#L58) |
| Admin key generation | [OperatorCli.keygen](backend/src/main/java/edu/iitm/instichain/cli/OperatorCli.java#L61) |
| Chaincode helpers | [comparable](fabric/chaincode/src/main/java/edu/iitm/instichain/chaincode/InstiChainContract.java#L182) · [parseObject](fabric/chaincode/src/main/java/edu/iitm/instichain/chaincode/InstiChainContract.java#L195) · [sequenceKey](fabric/chaincode/src/main/java/edu/iitm/instichain/chaincode/InstiChainContract.java#L217) |
| Frontend fetch wrappers | [adminRequest](frontend/src/AdminPanel.tsx#L8) · [advisorRequest](frontend/src/AdvisorPanel.tsx#L36) · [submitVerification](frontend/src/EmailVerification.tsx#L10) · [requestServerKey](frontend/src/signingIdentity.ts#L84) |
| Frontend IndexedDB / key provisioning | [openDatabase](frontend/src/signingIdentity.ts#L23) · [withStore](frontend/src/signingIdentity.ts#L36) · [provision](frontend/src/signingIdentity.ts#L98) |

## Tests

| What | Where |
| --- | --- |
| Email validation | [InstituteEmailTest](backend/src/test/java/edu/iitm/instichain/auth/InstituteEmailTest.java#L8) |
| Signing-key requests | [SigningKeyRequestTest](backend/src/test/java/edu/iitm/instichain/auth/SigningKeyRequestTest.java#L12) |
| Canonical JSON | [CanonicalJsonTest](backend/src/test/java/edu/iitm/instichain/common/CanonicalJsonTest.java#L11) |
| Ed25519 | [Ed25519Test](backend/src/test/java/edu/iitm/instichain/common/Ed25519Test.java#L10) |
| Jackson config | [JacksonConfigurationTest](backend/src/test/java/edu/iitm/instichain/config/JacksonConfigurationTest.java#L10) |
| Flyway migrations (needs Docker) | [SchemaMigrationTest](backend/src/test/java/edu/iitm/instichain/config/SchemaMigrationTest.java#L16) |
| Health | [HealthControllerTest](backend/src/test/java/edu/iitm/instichain/web/HealthControllerTest.java#L7) |
| Chaincode | [InstiChainContractTest](fabric/chaincode/src/test/java/edu/iitm/instichain/chaincode/InstiChainContractTest.java#L23) |

## Scripts and tooling

| What | Where |
| --- | --- |
| All `make` targets | [Makefile](Makefile#L4) |
| Start / stop / status of frontend and backend | [dev.sh](scripts/dev.sh) · [process discovery](scripts/dev.sh#L14) |
| Fabric install / up / upgrade / down | [fabric.sh](scripts/fabric.sh#L26) |
| PostgreSQL + Mailpit containers | [compose.yaml](compose.yaml) |

## Documentation

| What | Where |
| --- | --- |
| Setup and usage | [README.md](README.md) |
| Architecture and code map | [ARCHITECTURE.md](docs/ARCHITECTURE.md) |
| Presentation guide | [PRESENTATION_GUIDE.md](docs/PRESENTATION_GUIDE.md) |
| Remaining-work plan | [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md) |
