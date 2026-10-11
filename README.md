# InstiChain

InstiChain records faculty-authorized campus roles and competition achievements.
Its application and smart-contract backends are Java; its browser client is React
with TypeScript.

See [the architecture and code map](docs/ARCHITECTURE.md) for the boundary between
Fabric, PostgreSQL, and email infrastructure.

## Requirements

- Java 21
- Node.js 22.12 or newer and npm
- Docker with Compose
- `curl`, OpenSSL, and Git

Maven and Gradle do not need system installations. The repository contains pinned
launchers that download their distributions on first use.

## Project structure

```text
backend/                 Spring Boot API, Flyway migrations, and Java tests
fabric/chaincode/        Hyperledger Fabric Java smart contract
frontend/                React and TypeScript browser client
scripts/                 Development and Fabric lifecycle commands
docs/ARCHITECTURE.md     System design and source-code map
compose.yaml             PostgreSQL and Mailpit
Makefile                 Common local commands
```

## First-time setup

From the repository root:

```bash
cp backend/.env.example backend/.env
cd frontend && npm ci && cp .env.example .env && cd ..
make docker
make database
make mailpit
make fabric-install
make fabric-up
```

If `backend/.env` already exists, keep it and add the new JDBC and direct Fabric
settings from `.env.example`. The default database settings match `compose.yaml`.
Flyway applies the schema automatically when Spring Boot starts.

For a Fabric network already running the former JavaScript chaincode, run
`make fabric-up` to start or resume it, then run `make fabric-upgrade` once. This
preserves its ledger and installs the compatible Java contract at sequence 2.

## Start the application

Use separate terminals at the repository root:

```bash
make backend
make frontend
```

Open:

- Application: <http://127.0.0.1:5173>
- API health: <http://127.0.0.1:8000/api/health>
- Mailpit inbox: <http://127.0.0.1:8025>

Useful process commands:

```bash
make status
make status-docker
make status-database
make status-mailpit
make stop
make stop-database
make stop-mailpit
```

`make backend` supplies the local Fabric test-network identity path automatically.
The backend talks directly to the peer through the Java Fabric Gateway client;
there is no extra gateway process to start.

## Email-link login

1. Enter an `iitm.ac.in` email or a proper subdomain such as
   `student@smail.iitm.ac.in`.
2. Open Mailpit and follow the received link.
3. A new user enters a name once. A returning user confirms sign-in without a
   name field, and the stored identity and name remain unchanged.
4. The link expires after 15 minutes and can be used only once.

At first registration, the browser also generates an Ed25519 signing identity.
The non-exportable private key stays in IndexedDB on that device; only the public
key is registered with the backend. Existing accounts receive a key on their
next login. The account view shows the public key, SHA-256 fingerprint, and
whether the matching private key is available on the current device.

Only SHA-256 hashes of verification and session tokens are stored. Sessions use
an HTTP-only cookie and persist across browser reloads until logout or expiry.
Email ownership creates a basic user only; it grants no administrator or advisor
permissions.

## Administrator setup

After the intended administrator has signed in once, follow
[the controlled bootstrap guide](backend/ADMIN_SETUP.md). Bootstrap, club
creation, advisor appointments, advisor role grants/revocations, and credential
issuance all require confirmed Fabric transactions.

## Ledger inspection

```bash
curl http://127.0.0.1:8000/api/ledger/health
curl 'http://127.0.0.1:8000/api/ledger/events?start_sequence=1&limit=50'
curl http://127.0.0.1:8000/api/ledger/revocations
curl http://127.0.0.1:8000/api/ledger/fabric-revocations
```

The events response shows sequence, transaction ID, actor, object IDs, payload,
signature, submitting MSP, and commit time. PostgreSQL contains private data and
fast projections; Fabric is authoritative for issued institutional records.

## Tests

```bash
make test
```

Or run layers separately:

```bash
make backend-test
make fabric-test
make frontend-test
```

## Configuration

All backend settings live in the ignored `backend/.env`; the committed template
is `backend/.env.example`.

| Setting | Purpose |
| --- | --- |
| `INSTICHAIN_JDBC_URL` | PostgreSQL JDBC connection |
| `INSTICHAIN_DATABASE_USER`, `INSTICHAIN_DATABASE_PASSWORD` | Database login |
| `INSTICHAIN_PUBLIC_APP_URL` | Trusted frontend origin and email-link base |
| `INSTICHAIN_SMTP_*` | Mailpit or real SMTP provider |
| `INSTICHAIN_ADMIN_SIGNING_KEY_FILE` | Protected administrator Ed25519 key |
| `INSTICHAIN_FABRIC_*` | Fabric channel, chaincode, peer, identity, and TLS paths |

Local Compose credentials and Fabric test-network identities are development-only.
Production organizations must operate their Fabric nodes and keys independently.
