# Demonstration, Troubleshooting and Deployment Notes

## Demo runbook

Follow the README's first-time setup once, then before the demo:

```bash
make docker            # start Docker (or open Docker Desktop)
make fabric-up         # Fabric network and chaincode
make database mailpit  # PostgreSQL and the Mailpit inbox
make backend           # terminal 1
make frontend          # terminal 2
```

After a chaincode change, upgrade the deployed definition before demonstrating it:
`make fabric-refresh FABRIC_SEQUENCE=N`, with `N` one higher than the current
sequence (see [fabric/README.md](../fabric/README.md)).

Open the app at <http://127.0.0.1:5173> and the inbox at <http://127.0.0.1:8025>.
Use a private window for each extra person so their sessions and signing keys
stay separate.

| Step | Who | Shows |
| --- | --- | --- |
| 1. Sign up by email link | Everyone | Passwordless login; a signup grants no authority |
| 2. Create a club, appoint the advisor | Administrator | Admin-signed `CLUB_CREATED` and `ADVISOR_APPOINTED` |
| 3. Grant the club-head role | Advisor | Browser-signed `ROLE_GRANTED` |
| 4. Create a competition, submit a result | Club head | Immutable, versioned results; a club head cannot issue |
| 5. Issue the credential | Advisor | Browser-signed `ACHIEVEMENT_ISSUED` on Fabric |
| 6. Open the vault, create a share link | Student | Verification detail; QR code shown once |
| 7. Open the link in another browser | Verifier | Live verification, no account needed |
| 8. Revoke the credential, refresh the verifier | Advisor, then verifier | Revocation appears immediately |
| 9. Revoke the club head, try to act as them | Advisor, then club head | Access ends on the next request |
| 10. Show the ledger | Anyone | `/api/ledger/events` and `/api/ledger/revocations` |

For an operator view, run `cli audit` and `cli checkpoint` from
[LEDGER_AUDIT.md](LEDGER_AUDIT.md). `make e2e` runs steps 1 to 9 automatically.

## Troubleshooting

| Symptom | Cause | Fix |
| --- | --- | --- |
| Backend exits with `invalid value for parameter "TimeZone": "Asia/Calcutta"` | Newer JDKs report a legacy zone alias that PostgreSQL rejects | Fixed in code: the application starts in UTC. Pull the latest version |
| `./mvnw`: `JAVA_HOME environment variable is not defined correctly` | `JAVA_HOME` points to a folder that no longer exists | Point `JAVA_HOME` at the installed JDK folder, for example `C:\Program Files\Java\jdk-21` |
| Chaincode tests fail with `Mockito cannot mock this class` | JDK newer than 24; Byte Buddy does not yet support it | Use JDK 21, as the README requires. As a stopgap, set `JAVA_TOOL_OPTIONS=-Dnet.bytebuddy.experimental=true` |
| Gradle: `Unsupported class file major version 70` | JDK 26 is too new for the Gradle build scripts | Use JDK 21 |
| `make backend` prints `Install iproute2 (ss)` | `scripts/dev.sh` is written for Linux and WSL | Use WSL, or run `./mvnw spring-boot:run` in `backend/` |
| No email arrives in Mailpit | A link was already sent to that address within a minute | Wait a minute, or use another address |
| `503 The Fabric ledger is unavailable` | Fabric network stopped or not configured | `make fabric-up`; check `/api/ledger/health` |
| Revoking a credential fails on an older network | Chaincode predates credential revocation | `make fabric-refresh FABRIC_SEQUENCE=N` |
| "Your signing private key is unavailable on this device" | New browser, private window, or cleared site data | Account → Request key recovery; the administrator approves it |
| Verifier shows "Could not confirm" | Fabric could not be reached during the check | Bring Fabric up and select **Check again** |

## Production assumptions

This repository runs everything on one machine for development. A real
deployment needs, at minimum:

- **Independent organisations.** The institute and auditor peers, orderer and
  certificate authorities must run on separately operated infrastructure with
  their own keys. Locally they share one machine, which demonstrates the
  endorsement policy but not independence.
- **HTTPS** for the public URL, so session cookies are `Secure`.
- **Real SMTP** with an authorised sender, configured through `INSTICHAIN_SMTP_*`.
- **A reverse proxy** that terminates TLS, serves `index.html` for `/verify` and
  `/verify-email`, and rate-limits public verification per client.
- **Separate database roles**: a migration owner and a less privileged runtime role.
- **Secrets in a secret manager**: the administrator key, the proof encryption
  key and Google credentials.
- **Backups and checkpoints on a schedule**, stored away from the server; see
  [RECOVERY.md](RECOVERY.md) and [LEDGER_AUDIT.md](LEDGER_AUDIT.md).
- **One application instance**, or a shared rate limiter, because public
  verification limits are held in process memory.

## Test status

| Suite | Command | Result |
| --- | --- | --- |
| Backend unit and controller tests | `make backend-test` | 84 passed, 1 skipped (the migration test needs Docker) |
| Chaincode tests | `make fabric-test` | 8 passed (on JDK 26 only with the Byte Buddy flag above) |
| Frontend type check and build | `make frontend-test` | Passed |
| End-to-end workflow | `make e2e` | Not yet run: needs the Fabric network |
| Load test | `scripts/load-verify.mjs` | Not yet run: needs the Fabric network and share links |

Record actual end-to-end and load-test results here after running them; do not
quote the design targets as results.
