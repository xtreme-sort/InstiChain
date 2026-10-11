# Administrator and Advisor Setup

Email verification never grants an elevated role. The initial administrator is
created only by an operator command after the institute verifies that person's
authority.

## Bootstrap the administrator

1. Start PostgreSQL, Mailpit, Fabric, backend, and frontend.
2. Sign in once with the intended administrator's institute email.
3. From `backend/`, generate a protected server key:

```bash
./mvnw -q exec:java \
  -Dexec.mainClass=edu.iitm.instichain.cli.OperatorCli \
  -Dexec.args='keygen .keys/admin.pem'
```

4. Put its absolute path in the ignored `backend/.env`:

```dotenv
INSTICHAIN_ADMIN_SIGNING_KEY_FILE=/absolute/path/to/project/backend/.keys/admin.pem
```

5. Bootstrap the already-verified account:

```bash
./mvnw -q exec:java \
  -Dexec.mainClass=edu.iitm.instichain.cli.OperatorCli \
  -Dexec.args='admin institute-admin@iitm.ac.in Institute-approval-ADMIN-001'
```

The command appends `ADMIN_BOOTSTRAPPED` to Fabric before creating the database
projection. It is idempotent for the same active account and key and refuses to
replace the administrator. There is no HTTP promotion endpoint.

## Prepare an advisor signing identity

The advisor signs in to InstiChain once using the browser and device they will
use for advisor actions. The browser generates an Ed25519 key pair automatically:

- The non-exportable private key remains in that browser's IndexedDB storage.
- Only the public key is registered with the Java backend.
- The account page displays the public key, fingerprint, and local private-key
  availability.

The administrator verifies the faculty identity through an institutional
channel, then uses the UI to create the club and appointment. The appointment
automatically uses the public key registered to the advisor's verified account.
If the private key is reported as unavailable, key recovery or rotation is
required before that device can sign advisor actions.

## Local email check

Run `make mailpit`, request a sign-in link, and open
<http://127.0.0.1:8025>. Mailpit captures mail locally and does not deliver to a
real institute inbox. Configure the SMTP values in `.env` for real delivery.

From `backend/`, check connectivity without sending mail, or send an explicit
test message:

```bash
./mvnw -q exec:java -Dexec.mainClass=edu.iitm.instichain.cli.OperatorCli \
  -Dexec.args='smtp'
./mvnw -q exec:java -Dexec.mainClass=edu.iitm.instichain.cli.OperatorCli \
  -Dexec.args='smtp your-account@smail.iitm.ac.in'
```

## Drive test-account check

Set `INSTICHAIN_DRIVE_TEST_ACCESS_TOKEN` in the ignored `.env` to a short-lived
OAuth token with `drive.file` authorization. Optionally set
`INSTICHAIN_DRIVE_TEST_FILE_ID` to a file created by or shared with that OAuth
application. Then run:

```bash
./mvnw -q exec:java -Dexec.mainClass=edu.iitm.instichain.cli.OperatorCli \
  -Dexec.args='drive'
```

The command reads account and optional file metadata only. It does not download
file bytes, modify Drive, or print the access token.
