# Ledger Checkpoints and Projection Audit

Fabric is the authoritative record and PostgreSQL holds projections of it. Two
operator commands check that the two agree and that Fabric's history has not been
rewritten. Both read only public ledger data, and their output contains IDs and
categories but no payload contents, so a report can be shared with an auditor.

Run the commands from `backend/`:

```bash
cli() { ./mvnw -q exec:java -Dexec.mainClass=edu.iitm.instichain.cli.OperatorCli -Dexec.args="$*"; }
```

## Audit key

Checkpoints are signed with a dedicated Ed25519 audit key. Do not reuse the
administrator key: an attacker who could rewrite history with the administrator
key could then also re-sign the checkpoint. Create the key on a machine the
auditor controls and keep it off the application server:

```bash
cli keygen /secure/auditor/audit.pem
```

The command prints the audit public key. Give the auditor that value; it is the
trust anchor for every later verification.

## Create a checkpoint

```bash
cli checkpoint /secure/auditor/audit.pem checkpoints/2026-11-07.json
```

The checkpoint is a signed hash chain over every Fabric event in order:
`h₀ = 0…0` and `hᵢ = SHA-256(hᵢ₋₁ ":" canonical(eventᵢ))`. It records the sequence,
the head request ID, the final hash and the audit public key. Store each
checkpoint with a custodian outside this server, for example the institute
archive the design document describes. Take one at a fixed interval and after
significant events.

## Verify a checkpoint

```bash
cli verify-checkpoint checkpoints/2026-11-07.json <trusted-audit-public-key>
```

Pass the public key from the auditor's own records, never the copy inside the
file; otherwise a forger could sign a fake checkpoint with their own key. The
command exits with status 0 only when the outcome is `VALID`.

| Outcome | Meaning |
| --- | --- |
| `VALID` | Every event up to the checkpoint is unchanged. Later events are allowed. |
| `UNTRUSTED_KEY` | The file was signed by a key other than the trusted one. |
| `SIGNATURE_INVALID` | The checkpoint file was edited after signing. |
| `TRUNCATED` | The ledger now has fewer events than the checkpoint. |
| `HISTORY_CHANGED` | An event was modified, reordered or removed before the checkpoint. |

The format is plain JSON and the chain is reproducible from public data, so an
auditor can verify a checkpoint with their own tooling against an exported list
of events, without trusting this application.

## Audit the projections

```bash
cli audit
```

For every Fabric event the audit checks that:

- a PostgreSQL receipt exists with the same sequence, transaction ID, contents,
  signer and commit time
- the signature verifies against the stored public key of the signing key
- the projection it should have produced exists: the club, the appointment, the
  issued or revoked credential, or the retired key

It also reports PostgreSQL receipts that have no Fabric event. Finding kinds are
`MISSING_RECEIPT`, `CHANGED_RECEIPT`, `RECEIPT_WITHOUT_EVENT`, `INVALID_SIGNATURE`,
`UNKNOWN_SIGNING_KEY` and `MISSING_PROJECTION`. The command exits with status 1
if anything is found.

## Limits

- A checkpoint detects changes relative to itself. Events rewritten before the
  first checkpoint was taken cannot be detected this way.
- Signatures are checked against public keys stored in PostgreSQL. Replacing a
  stored key makes genuine events fail as `INVALID_SIGNATURE`, so the change is
  still reported, but the report cannot tell which side was altered. The advisor
  public key recorded on Fabric in each `ADVISOR_APPOINTED` event is the
  reference to compare against.
- Legacy rows imported from the earlier hash-chain table are excluded, because
  they never existed on Fabric.
