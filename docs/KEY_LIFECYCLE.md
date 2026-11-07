# Signing-Key Lifecycle

Each account has one Ed25519 signing key, generated in the browser as a
non-exportable key and held in that browser's IndexedDB. The server stores only
public keys. Advisors use their key to sign role grants, credential issuance and
revocations, so losing or leaking it is an institutional event that is recorded
on Fabric.

## Principles

- **History is never rewritten.** A retired public key stays in `authority_keys`
  so every signature made before the change still verifies.
- **Authority belongs to the person, not the key.** Appointments are not edited
  when a key changes. Historical verification accepts any of the appointed
  advisor's verified keys that was valid at signing time.
- **Ledger first.** Every change is committed to Fabric before PostgreSQL is
  updated. If Fabric is unavailable, nothing changes.
- **Retired keys stay retired.** The chaincode rejects any new event signed by a
  revoked key, and the API refuses to register a retired public key again.

## Three situations

| Situation | Who acts | Fabric event | Old key revoked from |
| --- | --- | --- | --- |
| Routine rotation | The advisor | `KEY_ROTATED`, signed by the old key | The rotation |
| Lost or wiped device | The user requests, the administrator approves | `KEY_RECOVERED`, signed by the administrator | The approval |
| Key compromise | The administrator | `KEY_REVOKED`, signed by the administrator | The stated compromise time |

### Routine rotation

From **Account → Signing identity → Rotate signing key**. The browser generates a
new key, the server prepares a `KEY_ROTATED` document, and the browser signs those
bytes twice: once with the old key, which authorises the change, and once with the
new key, which proves the advisor holds it. Only an advisor with an active key on
this device can rotate.

### Lost device policy

1. Sign in on the new device and choose **Request key recovery**. The browser
   generates a new key and signs a proof of possession with it.
2. Contact the institute administrator through an institutional channel. The
   request expires after 72 hours; a new request replaces an earlier one.
3. The administrator confirms the person's identity out of band, then approves
   it under **Administration → Signing-key recovery** with a reference to that
   check, such as a ticket number. Record the reference, not identity documents.
4. All of the user's active advisor keys are retired and the new key takes over.
   Until approval the user cannot sign, which is deliberate: a recovery request
   alone grants nothing.

Clearing site data has the same effect as losing the device. The key cannot be
exported, so there is no backup to restore.

### Key compromise

The administrator revokes the key under **Administration → Revoke a compromised
advisor key**, optionally backdating to when the compromise began. Credentials the
key signed after that time then fail their historical authority check, and the
response lists them. Revocation does not silently invalidate them: the advisor or
administrator must review each one and revoke those that are not genuine, as the
design document requires. The advisor then requests a recovery to sign again.

## Limitations

- The administrator's own server key is not covered. Replacing it remains an
  operator task, because no other authority can vouch for it.
- A compromise backdated to before an event was committed cannot remove that
  event from Fabric. It is reported for review instead.
- An advisor can sign from only one browser profile at a time. Moving to another
  device requires a recovery, which retires the key on the previous device.
