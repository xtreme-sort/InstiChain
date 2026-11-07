# Backup, Recovery and End-to-End Testing

## What can be recovered from where

Fabric holds the institutional events. Everything private or operational lives
only in PostgreSQL and must be backed up separately. Replaying the ledger cannot
recreate it.

| Data | Source of truth | Restored by |
| --- | --- | --- |
| Fabric receipts (`fabric_transactions`) | Fabric | `rebuild` |
| Clubs | Fabric (`CLUB_CREATED`) | `rebuild` |
| Advisor and club-role appointments, role revocations | Fabric | `rebuild`, except each advisor's private verification reference |
| Users, email links, sessions, account signing keys | PostgreSQL only | Backup |
| Administrator and authority keys | PostgreSQL; public keys are also on Fabric | Backup |
| Competitions, result versions, recipients | PostgreSQL only | Backup |
| Credentials | Fabric events, but they reference competitions | Backup |
| Share links | PostgreSQL only | Backup |
| Drive connections and archived proof bytes | PostgreSQL and the proof archive directory | Backup of both |

The rebuild tool therefore restores only the first three rows. It never claims
to restore the rest.

## Back up PostgreSQL

With the Compose database:

```bash
docker compose exec -T db pg_dump -U instichain -d instichain --format=custom > backup-$(date +%F).dump
```

Also copy `INSTICHAIN_PROOF_ARCHIVE_DIRECTORY` and keep the proof encryption key
and administrator key in your secret store. The dump contains hashed tokens and
encrypted provider credentials, but it is still private: store it encrypted and
restrict access.

## Restore

1. Stop the backend.
2. Restore into an empty database:

   ```bash
   docker compose exec -T db dropdb -U instichain --if-exists instichain_restore
   docker compose exec -T db createdb -U instichain instichain_restore
   docker compose exec -T db pg_restore -U instichain -d instichain_restore < backup-2026-11-07.dump
   ```

   Point `INSTICHAIN_JDBC_URL` at the restored database. Flyway applies any
   migrations added since the backup when the backend starts.
3. Fill in Fabric events committed after the backup was taken. From `backend/`:

   ```bash
   cli rebuild           # dry run: lists what it would restore and what it cannot
   cli rebuild --apply   # inserts only missing rows, in one transaction
   cli audit             # confirms PostgreSQL now matches Fabric
   ```

   (`cli` is defined in [LEDGER_AUDIT.md](LEDGER_AUDIT.md).) `rebuild` never
   overwrites or deletes a row. Rows that differ from the ledger are left alone
   and show up in the audit for a person to resolve.
4. Credentials issued after the backup are reported as not restorable. Their
   events are still on Fabric, so they can be re-entered by hand from the
   ledger once their competitions exist again.

## End-to-end test

`scripts/e2e.mjs` drives the whole workflow through the HTTP API against a live
stack, signing with WebCrypto Ed25519 exactly as the browser does:

login by email link → no self-promotion → administrator bootstrap → club and
advisor → signed club-head grant → competition and result → club head cannot
issue → signed issuance (retried without duplicates) → student vault and
verification, private to the student → share link verified publicly → credential
revocation reported live on the same link → share revocation → revoked club head
loses access → ledger audit passes.

Prerequisites: PostgreSQL, Mailpit and the Fabric network running, the backend
started with `INSTICHAIN_ADMIN_SIGNING_KEY_FILE` configured, and Node 22.

```bash
make e2e
```

Each run creates its own users and club, so runs do not interfere with real data.
The single administrator is shared: set `E2E_ADMIN_EMAIL` to the bootstrapped
administrator on an existing database. Login links are limited to one per
minute per address, so wait a minute between runs.

The script exercises the API rather than the browser UI. Screens still need a
manual pass.
