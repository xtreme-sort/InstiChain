# Security Review

A review of the running application's defences, with what was checked and what
remains a known limitation. Last reviewed for the 5.4 release commit.

## Sessions, origins and CSRF

- The session cookie is `HttpOnly`, `SameSite=Lax` and `Secure` whenever the
  public URL is HTTPS. Only a SHA-256 hash of the session token is stored.
- Every state-changing authenticated endpoint requires an `Origin` header equal
  to `INSTICHAIN_PUBLIC_APP_URL`. It is enforced through
  `AccessService.mutationGuard`, `SessionService.requireSameOrigin`, or the admin
  and club checks that call it, and the same guard takes the ledger lock.
- Three kinds of POST endpoint do not check the origin, by design:
  - Email-link request, inspect and confirm authenticate by a single-use token,
    and accept only `application/json`. A cross-site page cannot send a JSON body
    without a CORS preflight, and the API grants no CORS access.
  - Logout relies on `SameSite=Lax`: a cross-site POST arrives without the
    session cookie, so it cannot end the session.
  - Public verification is meant to be public and reads only.
- There is no CORS configuration, so other origins cannot read API responses.

## Rate limits

| Endpoint | Limit |
| --- | --- |
| Email login and verification links | 1 per minute and 20 per day per address |
| Share-link creation | 20 per day per student |
| Drive connection attempts | 10 per hour per user |
| Key recovery requests | 5 per day per user |
| Public verification | 30 per minute per client address, set by `INSTICHAIN_PUBLIC_VERIFICATION_PER_MINUTE` |

The public verification limit is held in process memory and keyed by the
connecting address. Behind a reverse proxy every request shares the proxy's
address, so a deployment must rate-limit at the proxy as well.

## Request size

`RequestSizeFilter` rejects API bodies over 256 KiB with `413`, and caps chunked
bodies as they are read. The largest legitimate body, a signed issuance for 100
recipients, is about 20 KB.

## Secrets and logging

- No passwords exist. Email tokens, session tokens and share tokens are stored
  only as SHA-256 hashes, and raw tokens are returned exactly once.
- Private signing keys stay in the browser as non-exportable keys, or for the
  administrator in an owner-only file on the server. Neither reaches PostgreSQL
  or Fabric.
- Google refresh tokens are encrypted at rest and never sent to the browser.
- The only application log lines are Fabric failures, which contain IDs and
  chaincode error messages but no tokens or documents. `server.error.include-message`
  is `never`, so stack details do not reach clients.
- Share and email-link tokens travel in URL fragments and request bodies, never
  query strings, so they stay out of access logs and `Referer` headers.

## Fabric availability

Every institutional write commits to Fabric first. If Fabric is unavailable the
request returns `503` and PostgreSQL is left unchanged. Verification reports an
unreachable ledger as `unknown`, never `valid`.

## Dependencies

`npm audit` reports no known vulnerabilities after updating Vite to 7.3.7. The
Java builds pin their versions through the Maven and Gradle wrappers.

## Known limitations

- The local Fabric network runs both organisations' peers on one machine. That
  shows the endorsement policy but not independent operation: in production,
  the institute and the auditor must run their own nodes and hold their own keys.
- One administrator, bootstrapped by the operator, is a single trust anchor.
  Replacing the administrator key is an operator task; see
  [KEY_LIFECYCLE.md](KEY_LIFECYCLE.md).
- Advisors sign from one browser profile. Clearing site data loses the key and
  needs a recovery.
- The system proves who authorised a record and that it is unchanged. It cannot
  prove a competition result was truthful.
