#!/usr/bin/env node
// End-to-end check of the full workflow against a running stack: backend, PostgreSQL,
// Mailpit and the Fabric network. It drives the same HTTP API as the browser and signs
// with WebCrypto Ed25519 exactly as the browser does. Each run creates its own users
// and club, so runs do not interfere; only the single administrator is shared.
//
//   node scripts/e2e.mjs
//
// Environment: E2E_API (default http://127.0.0.1:8000), E2E_ORIGIN (default
// http://127.0.0.1:5173), E2E_MAILPIT (default http://127.0.0.1:8025) and
// E2E_ADMIN_EMAIL (default e2e-admin@iitm.ac.in). The backend's
// INSTICHAIN_ADMIN_SIGNING_KEY_FILE must be configured so the admin can be bootstrapped.

import { execFileSync } from 'node:child_process'
import { webcrypto as crypto } from 'node:crypto'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const API = process.env.E2E_API || 'http://127.0.0.1:8000'
const ORIGIN = process.env.E2E_ORIGIN || 'http://127.0.0.1:5173'
const MAILPIT = process.env.E2E_MAILPIT || 'http://127.0.0.1:8025'
const ADMIN_EMAIL = process.env.E2E_ADMIN_EMAIL || 'e2e-admin@iitm.ac.in'
const RUN = Date.now().toString(36)
const BACKEND = join(dirname(fileURLToPath(import.meta.url)), '..', 'backend')

let passed = 0
const failures = []
async function step(name, work) {
  try {
    await work()
    passed += 1
    console.log(`  ok    ${name}`)
  } catch (error) {
    failures.push(name)
    console.log(`  FAIL  ${name}\n        ${error.message}`)
    throw error
  }
}
function expect(condition, message) { if (!condition) throw new Error(message) }

const b64 = (bytes) => Buffer.from(bytes).toString('base64')
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms))

async function call(method, path, { cookie, body } = {}) {
  const headers = { Origin: ORIGIN }
  if (cookie) headers.Cookie = cookie
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  const response = await fetch(`${API}/api/${path}`, {
    method, headers, body: body === undefined ? undefined : JSON.stringify(body),
  })
  const text = await response.text()
  let data = null
  try { data = text ? JSON.parse(text) : null } catch { data = text }
  return { status: response.status, data, headers: response.headers }
}
async function ok(method, path, options, expected = [200, 201, 204]) {
  const result = await call(method, path, options)
  expect(expected.includes(result.status),
    `${method} ${path} returned ${result.status}: ${JSON.stringify(result.data)}`)
  return result.data
}

async function latestToken(email, after) {
  for (let attempt = 0; attempt < 30; attempt += 1) {
    const search = await fetch(`${MAILPIT}/api/v1/search?query=${encodeURIComponent(`to:${email}`)}`)
    const found = (await search.json()).messages || []
    const fresh = found.find((message) => new Date(message.Created).getTime() >= after)
    if (fresh) {
      const message = await (await fetch(`${MAILPIT}/api/v1/message/${fresh.ID}`)).json()
      const match = /#token=([A-Za-z0-9_-]{43})/.exec(message.Text)
      if (match) return match[1]
    }
    await sleep(500)
  }
  throw new Error(`No login email reached Mailpit for ${email}. A link sent within the last minute is rate limited.`)
}

/** Registers or signs in through the emailed link, as the browser does. */
async function signIn(email, name) {
  const keys = await crypto.subtle.generateKey({ name: 'Ed25519' }, false, ['sign', 'verify'])
  const publicKey = b64(await crypto.subtle.exportKey('raw', keys.publicKey))
  const before = Date.now() - 2000
  await ok('POST', 'auth/email/request', { body: { email } }, [202])
  const token = await latestToken(email, before)
  const inspect = await ok('POST', 'auth/email/inspect', { body: { token } })
  const body = inspect.requires_name ? { token, display_name: name, public_key: publicKey } : { token }
  const response = await call('POST', 'auth/email/confirm', { body })
  expect(response.status === 200, `confirm returned ${response.status}: ${JSON.stringify(response.data)}`)
  const cookie = /instichain_session=[^;]+/.exec(response.headers.get('set-cookie') || '')?.[0]
  expect(cookie, 'confirm did not set a session cookie')
  return { email, cookie, id: response.data.account.id, privateKey: keys.privateKey, fresh: inspect.requires_name }
}

async function signedSubmit(user, preparePath, submitPath, draft = {}) {
  const prepared = await ok('POST', preparePath, { cookie: user.cookie, body: draft })
  const signature = await crypto.subtle.sign({ name: 'Ed25519' }, user.privateKey,
    Buffer.from(prepared.signing_payload, 'base64'))
  return ok('POST', submitPath, { cookie: user.cookie, body: { document: prepared.document, signature: b64(signature) } })
}

function operator(...args) {
  const mvnw = process.platform === 'win32' ? 'bash' : './mvnw'
  const prefix = process.platform === 'win32' ? ['./mvnw'] : []
  return execFileSync(mvnw, [...prefix, '-q', 'exec:java', '-Dexec.mainClass=edu.iitm.instichain.cli.OperatorCli',
    `-Dexec.args=${args.join(' ')}`], { cwd: BACKEND, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] })
}

async function main() {
  console.log(`InstiChain end-to-end run ${RUN}\n  API ${API} | Mailpit ${MAILPIT}`)
  const later = (days) => new Date(Date.now() + days * 86_400_000).toISOString()
  const ctx = {}

  await step('backend and Fabric are reachable', async () => {
    await ok('GET', 'health')
    const ledger = await ok('GET', 'ledger/health')
    expect(ledger.status === 'ok', 'Fabric ledger health is not ok')
  })

  await step('users register through emailed links', async () => {
    ctx.advisor = await signIn(`e2e-advisor-${RUN}@iitm.ac.in`, 'E2E Advisor')
    ctx.head = await signIn(`e2e-head-${RUN}@smail.iitm.ac.in`, 'E2E Club Head')
    ctx.student = await signIn(`e2e-student-${RUN}@smail.iitm.ac.in`, 'E2E Student')
    ctx.admin = await signIn(ADMIN_EMAIL, 'E2E Administrator')
    expect(ctx.advisor.fresh && ctx.student.fresh, 'new users should be asked for a name')
  })

  await step('a signup grants no authority', async () => {
    const result = await call('GET', 'admin/access', { cookie: ctx.student.cookie })
    expect(result.status === 403, `student reached admin access (${result.status})`)
  })

  await step('administrator is bootstrapped by the operator CLI only', async () => {
    if ((await call('GET', 'admin/access', { cookie: ctx.admin.cookie })).status !== 200) {
      operator('admin', ADMIN_EMAIL, `E2E-bootstrap-${RUN}`)
    }
    await ok('GET', 'admin/access', { cookie: ctx.admin.cookie })
  })

  await step('administrator creates a club and appoints an advisor', async () => {
    const club = await ok('POST', 'admin/clubs', { cookie: ctx.admin.cookie,
      body: { slug: `e2e-${RUN}`, name: `E2E Club ${RUN}`, description: 'End-to-end fixture' } })
    ctx.clubId = club.id
    await ok('POST', `admin/clubs/${ctx.clubId}/advisors`, { cookie: ctx.admin.cookie, body: {
      email: ctx.advisor.email, verification_reference: `E2E-faculty-${RUN}`,
      starts_at: new Date().toISOString(), ends_at: later(365) } })
  })

  await step('advisor signs a club-head grant', async () => {
    const grant = await signedSubmit(ctx.advisor, `advisor/clubs/${ctx.clubId}/role-grants/prepare`,
      `advisor/clubs/${ctx.clubId}/role-grants`,
      { email: ctx.head.email, position: 'club_head', starts_at: new Date().toISOString(), ends_at: later(180) })
    ctx.headRoleId = grant.id
  })

  await step('club head records a competition and a result', async () => {
    const competition = await ok('POST', `clubs/${ctx.clubId}/competitions`, { cookie: ctx.head.cookie,
      body: { title: `E2E Hackathon ${RUN}`, description: 'Fixture', starts_at: new Date().toISOString(), ends_at: later(1) } })
    ctx.competitionId = competition.id
    const result = await ok('POST', `clubs/${ctx.clubId}/competitions/${ctx.competitionId}/results`, {
      cookie: ctx.head.cookie, body: { award: 'First place', proof_digest: 'a'.repeat(64),
        recipient_emails: [ctx.student.email] } })
    ctx.resultId = result.result_id
    ctx.version = result.version
  })

  await step('club head cannot issue a credential', async () => {
    const attempt = await call('POST',
      `clubs/${ctx.clubId}/competitions/${ctx.competitionId}/results/${ctx.resultId}/versions/${ctx.version}/issuance/prepare`,
      { cookie: ctx.head.cookie })
    expect(attempt.status === 403, `club head reached issuance (${attempt.status})`)
  })

  await step('advisor issues the credential on Fabric', async () => {
    const base = `clubs/${ctx.clubId}/competitions/${ctx.competitionId}/results/${ctx.resultId}/versions/${ctx.version}`
    const issued = await signedSubmit(ctx.advisor, `${base}/issuance/prepare`, `${base}/issuance`)
    ctx.credentialId = issued.credentials[0].id
    // Retrying the same approval must not create a second credential.
    const again = await signedSubmit(ctx.advisor, `${base}/issuance/prepare`, `${base}/issuance`)
    expect(again.credentials.length === 1 && again.credentials[0].id === ctx.credentialId, 'retry duplicated the credential')
  })

  await step('student sees and verifies their credential', async () => {
    const list = await ok('GET', 'credentials', { cookie: ctx.student.cookie })
    expect(list.some((credential) => credential.id === ctx.credentialId), 'credential missing from the vault')
    const check = await ok('GET', `credentials/${ctx.credentialId}/verification`, { cookie: ctx.student.cookie })
    expect(check.authenticity === 'valid' && check.historical_authorization === 'valid',
      `verification was ${JSON.stringify(check)}`)
    const other = await call('GET', `credentials/${ctx.credentialId}/verification`, { cookie: ctx.head.cookie })
    expect(other.status === 404, 'another user could read the credential')
  })

  await step('student shares it and a verifier confirms it live', async () => {
    const share = await ok('POST', 'shares', { cookie: ctx.student.cookie, body: {
      request_id: crypto.randomUUID(), label: 'E2E recruiter', expires_at: later(2), credential_ids: [ctx.credentialId] } })
    ctx.shareId = share.id
    ctx.shareToken = share.token
    const result = await ok('POST', 'public/verification', { body: { token: ctx.shareToken } })
    expect(result.credentials[0].verdict === 'valid', `verdict was ${result.credentials[0].verdict}`)
    expect(!JSON.stringify(result).includes('@'), 'public response leaked an email address')
  })

  await step('a revocation shows up on the existing share immediately', async () => {
    await signedSubmit(ctx.advisor, `clubs/${ctx.clubId}/credentials/${ctx.credentialId}/revocations/prepare`,
      `clubs/${ctx.clubId}/credentials/${ctx.credentialId}/revocations`, { reason: 'E2E revocation check' })
    const result = await ok('POST', 'public/verification', { body: { token: ctx.shareToken } })
    expect(result.credentials[0].verdict === 'revoked', `verdict was ${result.credentials[0].verdict}`)
    const listed = await ok('GET', `ledger/revocations/credential/${ctx.credentialId}`)
    expect(listed.revoked === true, 'credential missing from the revocation list')
  })

  await step('revoking a share stops it working', async () => {
    await ok('POST', `shares/${ctx.shareId}/revoke`, { cookie: ctx.student.cookie })
    const result = await call('POST', 'public/verification', { body: { token: ctx.shareToken } })
    expect(result.status === 404, `revoked share returned ${result.status}`)
  })

  await step('a revoked club head loses access at once', async () => {
    const base = `advisor/clubs/${ctx.clubId}/roles/${ctx.headRoleId}/revocations`
    await signedSubmit(ctx.advisor, `${base}/prepare`, base, { reason: 'E2E handover' })
    const attempt = await call('POST', `clubs/${ctx.clubId}/competitions`, { cookie: ctx.head.cookie,
      body: { title: 'Should fail', starts_at: new Date().toISOString() } })
    expect(attempt.status === 403, `revoked head still created a competition (${attempt.status})`)
  })

  await step('PostgreSQL projections match the Fabric ledger', async () => {
    const output = operator('audit')
    expect(output.includes('Audit passed'), output.trim())
  })

  console.log(`\n${passed} step(s) passed. Fixture club slug: e2e-${RUN}`)
}

main().catch(() => {
  console.log(`\n${passed} passed, ${failures.length} failed. Later steps were skipped because they depend on it.`)
  process.exitCode = 1
})
