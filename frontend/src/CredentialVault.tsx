import { useEffect, useState } from 'react'
import { BadgeCheck, RefreshCw } from 'lucide-react'
import { apiRequest } from './api'

type Credential = {
  id: string
  club_name: string
  competition_title: string
  result_id: string
  submission_version: number
  award: string
  proof_digest: string
  advisor_name: string
  issuance_transaction_id: string
  fabric_transaction_id: string | null
  issued_at: string
  lifecycle_status: 'active' | 'revoked' | 'superseded'
  proof_availability: string
}
type Verification = {
  authenticity: string
  ledger_status: string
  historical_authorization: string
  lifecycle_status: string
  proof_availability: string
  reason: string | null
}
type HistoryItem = {
  event_type: string
  request_id: string
  fabric_transaction_id: string | null
  committed_at: string
  reason: string | null
}

function readable(value: string): string {
  return value.replaceAll('_', ' ')
}

export default function CredentialVault() {
  const [credentials, setCredentials] = useState<Credential[]>([])
  const [selectedId, setSelectedId] = useState('')
  const [verification, setVerification] = useState<Verification | null>(null)
  const [history, setHistory] = useState<HistoryItem[]>([])
  const [loading, setLoading] = useState(true)
  const [checking, setChecking] = useState(false)
  const [error, setError] = useState('')
  const [detailError, setDetailError] = useState('')

  useEffect(() => {
    let active = true
    setLoading(true)
    void apiRequest<Credential[]>('credentials').then((rows) => {
      if (active) { setCredentials(rows); setSelectedId(rows[0]?.id || '') }
    }).catch((cause) => {
      if (active) setError(cause instanceof Error ? cause.message : 'Unable to load credentials.')
    }).finally(() => { if (active) setLoading(false) })
    return () => { active = false }
  }, [])

  useEffect(() => {
    if (!selectedId) return
    let active = true
    setChecking(true)
    setDetailError('')
    setVerification(null)
    setHistory([])
    void Promise.all([
      apiRequest<Verification>(`credentials/${selectedId}/verification`),
      apiRequest<HistoryItem[]>(`credentials/${selectedId}/history`),
    ]).then(([result, events]) => {
      if (active) { setVerification(result); setHistory(events) }
    }).catch((cause) => {
      if (active) setDetailError(cause instanceof Error ? cause.message : 'Unable to verify this credential.')
    }).finally(() => { if (active) setChecking(false) })
    return () => { active = false }
  }, [selectedId])

  const selected = credentials.find((credential) => credential.id === selectedId)
  return <section className="vault" aria-labelledby="vault-title">
    <div className="section-heading"><div><h1 id="vault-title">My credentials</h1>
      <p>Issued achievements and their current ledger status</p></div>
      <BadgeCheck size={26} aria-hidden="true" /></div>
    {error && <p role="alert" className="unavailable">{error}</p>}
    {loading ? <p role="status">Loading credentials...</p> : credentials.length === 0 ?
      <p>No achievement credentials have been issued to your account.</p> :
      <div className="vault-layout">
        <div className="vault-list" aria-label="Credentials">{credentials.map((credential) =>
          <button type="button" key={credential.id} className={selectedId === credential.id ? 'selected' : ''}
            onClick={() => setSelectedId(credential.id)}>
            <strong>{credential.award}</strong><span>{credential.competition_title}</span>
            <small>{readable(credential.lifecycle_status)}</small>
          </button>)}</div>
        {selected && <article className="vault-detail">
          <h2>{selected.award}</h2>
          <p>{selected.competition_title} | {selected.club_name}</p>
          <dl><dt>Issued by</dt><dd>{selected.advisor_name}</dd>
            <dt>Issued</dt><dd>{new Date(selected.issued_at).toLocaleString()}</dd>
            <dt>Result version</dt><dd>{selected.submission_version}</dd>
            <dt>Current status</dt><dd>{readable(selected.lifecycle_status)}</dd>
            <dt>Proof</dt><dd>Digest only; file availability has not been checked</dd>
            <dt>SHA-256</dt><dd><code>{selected.proof_digest}</code></dd>
            <dt>Fabric transaction</dt><dd><code>{selected.fabric_transaction_id || 'Not available'}</code></dd></dl>
          {checking && <p role="status"><RefreshCw size={15} aria-hidden="true" /> Checking ledger...</p>}
          {detailError && <p role="alert" className="unavailable">{detailError}</p>}
          {verification && <section className="vault-verification" aria-label="Verification">
            <h3>Verification</h3>
            <dl><dt>Authenticity</dt><dd>{readable(verification.authenticity)}</dd>
              <dt>Advisor authority at issue</dt><dd>{readable(verification.historical_authorization)}</dd>
              <dt>Ledger receipt</dt><dd>{readable(verification.ledger_status)}</dd>
              <dt>Lifecycle</dt><dd>{readable(verification.lifecycle_status)}</dd>
              <dt>Proof availability</dt><dd>{readable(verification.proof_availability)}</dd></dl>
            {verification.reason && <p>{verification.reason}</p>}
          </section>}
          <section className="vault-history" aria-label="Credential history"><h3>Ledger history</h3>
            {history.length === 0 ? <p>No verified history is available.</p> : <ol>{history.map((event) =>
              <li key={event.request_id}><strong>{readable(event.event_type)}</strong>
                <span>{new Date(event.committed_at).toLocaleString()}</span>
                {event.reason && <p>{event.reason}</p>}
                <code>{event.fabric_transaction_id || event.request_id}</code></li>)}</ol>}
          </section>
        </article>}
      </div>}
  </section>
}
