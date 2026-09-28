# Administrator and Advisor Setup

This milestone establishes one initial administrator, creates clubs and records
institutionally verified faculty appointments. Email ownership alone never grants
an administrator or advisor role. The operator and institute administrator must
perform the real institutional checks; the application records their decision.

## 1. Apply the Migration

From the repository root:

```bash
docker compose up -d --wait db mailpit
cd backend
source .venv/bin/activate
python -m pip install -r requirements.txt
python -m alembic upgrade head
```

Revision `0004` adds administrator and public-key records and advisor appointment
metadata. Existing users, clubs and ledger entries are preserved.

## 2. Bootstrap the Initial Administrator

First sign in through the existing email-link flow to create a verified institute
account. Independently establish that this person is the authorized institute
administrator. Then run these commands on the application server, from `backend/`:

```bash
python -m app.bootstrap keygen --output .keys/admin.pem
```

Add the **absolute path** to `backend/.env`:

```dotenv
INSTICHAIN_ADMIN_SIGNING_KEY_FILE=/absolute/path/to/project/backend/.keys/admin.pem
```

The generated key has owner-only permissions and is never overwritten. `.keys/`
is ignored by Git. Keep a protected backup: the bootstrap command cannot rotate
or replace the trusted key. The runtime server needs read access to this file.
It is an unencrypted server key protected by filesystem permissions, distinct
from advisors' passphrase-encrypted private keys.

Replace the example email and reference with the actual authorized account and
an institutional approval/ticket reference:

```bash
python -m app.bootstrap admin \
  --email institute-admin@iitm.ac.in \
  --verification-reference 'Institute authorization ticket ADMIN-001'
```

Bootstrap requires an existing verified account and writes a signed
`ADMIN_BOOTSTRAPPED` event. Repeating it with the same account and key is harmless.
Attempts to add a different administrator or resurrect a revoked bootstrap fail.
There is no HTTP bootstrap endpoint and no automatic "first user is admin" rule.

Restart the backend and refresh the signed-in frontend at
<http://127.0.0.1:5173>. The administrator sees **Institute administration**.
The backend checks the administrator record on every protected request.

## 3. Create a Club and Onboard an Advisor

1. Create a club with its name and unique lowercase slug.
2. Ask the faculty advisor to verify their institute email through the normal
   sign-in flow. An email domain does not establish faculty status.
3. Verify faculty identity and the public-key binding through an institutional
   channel. Record a reference to that check, not sensitive documents or secrets.
4. Submit the advisor's verified email, public Ed25519 key, verification reference
   and appointment term through **Appoint faculty advisor**.

For an early test key, an advisor can run the following on their **own workstation**
with backend dependencies installed:

```bash
python -m app.bootstrap advisor-keygen --output .keys/advisor.pem
```

The command prompts for a passphrase and writes an encrypted PKCS8 private key.
Only share the printed base64 public key with the administrator. Never upload the
private key or passphrase. This is a key preparation utility; browser key unlock
and credential signing are future milestones.

One advisor may cover multiple clubs, but advisor terms within a club may not
overlap. Terms use time-zone-aware timestamps and an exclusive end time. Past
requested start times become the actual grant time, so authority is not backdated.
An expired term or a future term is shown accordingly. A public key cannot be
rebound to another user. Old public keys and appointments are retained.

API endpoints (session cookie required):

| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/api/admin/access` | Check current administrator access |
| GET | `/api/admin/clubs` | List clubs and advisor appointment history |
| POST | `/api/admin/clubs` | Create a club and signed event |
| POST | `/api/admin/clubs/{club_id}/advisors` | Register/bind the advisor key and append an appointment |

Mutating requests must include an `Origin` matching `INSTICHAIN_PUBLIC_APP_URL`.
The browser supplies this automatically. Advisor appointment and key registration
are one transaction with the signed ledger event; failed writes leave no partial
club or appointment. Concurrent overlapping appointments produce one success and
one conflict. A duplicate club slug returns a conflict without another event.

The server signs these administrative events with the operator-provisioned key.
This does not implement advisor credential signing, annual handover, advisor
revocation UI, key recovery, full ledger replay or independent checkpoints.

## 4. Check Email Delivery Early

From `backend/`, with the configured SMTP server available:

```bash
python -m app.preflight smtp
python -m app.preflight smtp --recipient your-account@smail.iitm.ac.in
```

The first command checks connectivity and configured authentication without
sending mail. The second explicitly sends a test message. With local Mailpit,
confirm it appears at <http://127.0.0.1:8025>. Mailpit does not deliver to an
institute inbox. For actual delivery, configure your provider's host, authorized
sender, credentials and STARTTLS/SSL in `backend/.env`, then run the second command
and confirm receipt in the real inbox. SMTP acceptance alone is not proof of
delivery. These checks neither create accounts nor change permissions.

## 5. Check Drive Test-Account Access Early

This is a read-only integration probe, not the later Drive connection/vault UI.

1. Enable the Google Drive API in a test Google Cloud project. Configure its OAuth
   consent screen and client, adding your storage test account as a test user if
   the app is in testing mode. It may differ from your institute email.
2. Obtain a short-lived access token through an authorized OAuth flow for that
   client with `https://www.googleapis.com/auth/drive.file`. Do not request
   whole-Drive access just to make a test pass.
3. Put the token in the ignored `backend/.env` as
   `INSTICHAIN_DRIVE_TEST_ACCESS_TOKEN`. Do not pass it on the command line,
   commit it or paste it into reports.
4. Optionally set `INSTICHAIN_DRIVE_TEST_FILE_ID` to a file created by or explicitly
   selected/shared with the **same OAuth application**. A random file in the
   account may be inaccessible under `drive.file`.

```bash
python -m app.preflight drive
```

The check calls Drive's `about.get` and, when configured, `files.get`. It reports
account access and selected-file metadata/download capability separately. It does
not download file bytes, upload anything, retain refresh tokens or print provider
response bodies. Missing configuration and HTTP errors return a nonzero exit code;
they are never reported as successful access. An expired token needs renewal.
The probe uses the supplied token; it does not independently certify its scopes.

Record the check time, account/project used and observed result privately. Real
Google access cannot be established until valid test-account credentials exist.

References: [Drive scopes](https://developers.google.com/workspace/drive/api/guides/api-specific-auth),
[Drive account endpoint](https://developers.google.com/workspace/drive/api/reference/rest/v3/about/get),
[Ed25519](https://cryptography.io/en/latest/hazmat/primitives/asymmetric/ed25519/).
