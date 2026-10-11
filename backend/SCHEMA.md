# Database Schema

Flyway applies migrations from `src/main/resources/db/migration`. Migration V1
defines the original Java PostgreSQL schema and V2 adds browser-generated account
signing keys. `baseline-on-migrate` is enabled at version 1 so an existing database
created by the former Alembic backend is adopted without recreating its tables; a
new empty database receives the complete schema.

| Table | Purpose |
| --- | --- |
| `users` | Stable identities and verified institute email addresses |
| `email_verifications` | Hashed, expiring, single-use email tokens |
| `sessions` | Hashed, expiring, revocable browser sessions |
| `user_signing_keys` | Browser-generated account public keys; no private keys |
| `administrator` | The single operator-bootstrapped administrator |
| `authority_keys` | Verified Ed25519 public keys; no private keys |
| `clubs` | Club query projection |
| `appointments` | Advisor, head, and office-bearer role projection |
| `competitions` | Club-scoped competitions |
| `result_submissions` | Immutable result versions |
| `result_recipients` | Students attached to a result version |
| `credentials` | Issued achievement projection and revocation fields |
| `fabric_transactions` | Fabric commit receipts and signed event projection |
| `ledger_entries` | Read-only legacy hash-chain history |

Passwords, raw email tokens, session tokens, private signing keys, and OAuth
tokens are never stored in these tables. Clubs, appointments, and credentials are
updated only after the corresponding Fabric transaction commits. Revocation is
append-only on Fabric; PostgreSQL stores the current status for fast reads.

For later schema changes, add the next sequential migration, beginning with
`V3__description.sql`. Never edit an applied migration in a shared environment.
