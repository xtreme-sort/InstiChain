import { useEffect, useState } from 'react'
import { AlertTriangle, CircleCheck, CircleHelp, CircleX, RefreshCw } from 'lucide-react'
import { postJson } from './api'

type PublicCredential = {
  credential_id: string
  award: string
  competition: string
  club: string
  issuer: string
  issued_at: string
  fabric_transaction_id: string | null
  verdict: 'valid' | 'revoked' | 'superseded' | 'invalid' | 'unknown'
  authenticity: string
  ledger_status: string
  historical_authorization: string
  lifecycle_status: string
  proof_status: string
}
type Result = { checked_at: string; recipient: string; link_expires_at: string; credentials: PublicCredential[] }

const VERDICTS = {
  valid: { label: 'Valid', icon: CircleCheck, note: 'Authentic, issued by an authorised advisor, and currently active.' },
  revoked: { label: 'Revoked', icon: CircleX, note: 'The issuing club has revoked this credential.' },
  superseded: { label: 'Superseded', icon: AlertTriangle, note: 'A corrected version has replaced this credential.' },
  invalid: { label: 'Invalid', icon: CircleX, note: 'This record failed a signature or authority check.' },
  unknown: { label: 'Could not confirm', icon: CircleHelp, note: 'The ledger could not be checked. Do not treat this as valid; try again later.' },
} as const

const PROOFS: Record<string, string> = {
  verified: 'An archived copy matches the issued digest.',
  digest_only: 'Only the document digest was recorded; no archived copy is attached.',
  unchecked: 'The archived evidence has not been checked yet.',
  unavailable: 'The evidence copy is currently unavailable. This does not affect validity.',
}

function readable(value: string): string { return value.replaceAll('_', ' ') }

/**
 * Takes the token from the fragment once, then removes it from the address bar and history.
 * Cached at module level because StrictMode runs state initialisers twice.
 */
let takenToken: string | null = null
function takeToken(): string {
  if (takenToken === null) {
    takenToken = new URLSearchParams(window.location.hash.slice(1)).get('share') || ''
    if (window.location.hash) window.history.replaceState(null, '', window.location.pathname)
  }
  return takenToken
}

export default function PublicVerifier() {
  const [token] = useState(takeToken)
  const [result, setResult] = useState<Result | null>(null)
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(Boolean(token))

  async function check() {
    setLoading(true); setError('')
    try { setResult(await postJson<Result>('public/verification', { token })) }
    catch (cause) { setResult(null); setError(cause instanceof Error ? cause.message : 'Verification is unavailable.') }
    finally { setLoading(false) }
  }

  useEffect(() => { if (token) void check() }, [token])

  return <section className="verifier" aria-labelledby="verifier-title">
    <h1 id="verifier-title">Credential verification</h1>
    {!token && <p role="alert" className="unavailable">This page needs a verification link from a student.</p>}
    {loading && <p role="status"><RefreshCw size={15} aria-hidden="true" /> Checking the ledger...</p>}
    {error && <p role="alert" className="unavailable">{error}</p>}
    {result && <>
      <p className="verifier-meta">Shared by <strong>{result.recipient}</strong>. Checked live at{' '}
        {new Date(result.checked_at).toLocaleString()}. Link expires {new Date(result.link_expires_at).toLocaleString()}.</p>
      {result.credentials.length === 0 && <p>This link contains no credentials.</p>}
      {result.credentials.map((credential) => {
        const verdict = VERDICTS[credential.verdict] ?? VERDICTS.unknown
        const Icon = verdict.icon
        return <article className={`verifier-card ${credential.verdict}`} key={credential.credential_id}>
          <div className="verifier-verdict"><Icon size={22} aria-hidden="true" />
            <div><strong>{verdict.label}</strong><p>{verdict.note}</p></div></div>
          <h2>{credential.award}</h2>
          <p>{credential.competition} | {credential.club}</p>
          <dl>
            <dt>Issued by</dt><dd>{credential.issuer}</dd>
            <dt>Issued</dt><dd>{new Date(credential.issued_at).toLocaleString()}</dd>
            <dt>Authenticity</dt><dd>{readable(credential.authenticity)}</dd>
            <dt>Authority at issue</dt><dd>{readable(credential.historical_authorization)}</dd>
            <dt>Ledger receipt</dt><dd>{readable(credential.ledger_status)}</dd>
            <dt>Lifecycle</dt><dd>{readable(credential.lifecycle_status)}</dd>
            <dt>Evidence</dt><dd>{PROOFS[credential.proof_status] ?? readable(credential.proof_status)}</dd>
            <dt>Fabric transaction</dt><dd><code>{credential.fabric_transaction_id || 'Not available'}</code></dd>
          </dl>
        </article>
      })}
      <button type="button" onClick={() => void check()} disabled={loading}>
        <RefreshCw size={17} aria-hidden="true" /> Check again</button>
    </>}
  </section>
}
