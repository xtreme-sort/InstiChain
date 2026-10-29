import { useEffect, useState } from 'react'
import { BadgeCheck } from 'lucide-react'
import { apiRequest, postJson } from './api'
import { signPreparedPayload } from './signingIdentity'
import type { Competition } from './CompetitionPanel'
import type { Submission } from './ResultPanel'

type Prepared = { document: Record<string, unknown>; signing_payload: string }
type Issued = {
  transaction_id: string
  issued_at: string
  credentials: { id: string; recipient_id: string; award: string; submission_version: number }[]
}

export default function IssuancePanel({ clubId, userId }: { clubId: string; userId: string }) {
  const [competitions, setCompetitions] = useState<Competition[]>([])
  const [competitionId, setCompetitionId] = useState('')
  const [submissions, setSubmissions] = useState<Submission[]>([])
  const [loading, setLoading] = useState(false)
  const [pendingId, setPendingId] = useState('')
  const [error, setError] = useState('')
  const [issued, setIssued] = useState<Issued | null>(null)

  useEffect(() => {
    let active = true
    setCompetitions([])
    setCompetitionId('')
    setSubmissions([])
    setIssued(null)
    setError('')
    void apiRequest<Competition[]>(`clubs/${clubId}/competitions`).then((rows) => {
      if (active) { setCompetitions(rows); setCompetitionId(rows[0]?.id || '') }
    }).catch((cause) => { if (active) setError(cause instanceof Error ? cause.message : 'Unable to load competitions.') })
    return () => { active = false }
  }, [clubId])

  useEffect(() => {
    if (!competitionId) return
    let active = true
    setLoading(true)
    setIssued(null)
    setError('')
    void apiRequest<Submission[]>(`clubs/${clubId}/competitions/${competitionId}/results`)
      .then((rows) => { if (active) setSubmissions(rows) })
      .catch((cause) => { if (active) setError(cause instanceof Error ? cause.message : 'Unable to load results.') })
      .finally(() => { if (active) setLoading(false) })
    return () => { active = false }
  }, [clubId, competitionId])

  async function issue(row: Submission) {
    if (!window.confirm(`Issue credentials for ${row.recipients.length} recipient(s) from result version ${row.version}?`)) return
    const path = `clubs/${clubId}/competitions/${competitionId}/results/${row.result_id}/versions/${row.version}/issuance`
    setPendingId(row.id)
    setError('')
    setIssued(null)
    try {
      const prepared = await apiRequest<Prepared>(`${path}/prepare`, { method: 'POST' })
      const signature = await signPreparedPayload(userId, prepared.signing_payload)
      const result = await postJson<Issued>(path, { document: prepared.document, signature })
      setIssued(result)
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Unable to issue credentials.')
    } finally { setPendingId('') }
  }

  const latest = submissions.filter((submission) => !submissions.some((other) =>
    other.result_id === submission.result_id && other.version > submission.version))
  return <section className="issuance-panel" aria-labelledby="issuance-title">
    <h3 id="issuance-title">Issue achievement credentials</h3>
    <label>Competition<select value={competitionId} onChange={(event) => setCompetitionId(event.target.value)}>
      {competitions.map((competition) => <option key={competition.id} value={competition.id}>{competition.title}</option>)}
    </select></label>
    {error && <p role="alert" className="unavailable">{error}</p>}
    {loading ? <p role="status">Loading results...</p> : !competitionId ? <p>No competitions are available.</p> :
      latest.length === 0 ? <p>No results have been submitted for this competition.</p> :
        <ul className="issuance-list">{latest.map((row) => <li key={row.id}>
          <div><strong>{row.award}</strong><span>Version {row.version}</span></div>
          <p>{row.recipients.map((recipient) => recipient.display_name).join(', ')}</p>
          <p>Proof SHA-256: <code>{row.proof_digest}</code></p>
          <button type="button" disabled={Boolean(pendingId)} onClick={() => void issue(row)}>
            <BadgeCheck size={17} aria-hidden="true" />{pendingId === row.id ? 'Issuing...' : 'Review and sign issuance'}
          </button>
        </li>)}</ul>}
    {issued && <div role="status" className="issuance-confirmation">
      <strong>Fabric confirmed the issuance.</strong>
      <p>Transaction: <code>{issued.transaction_id}</code></p>
      <p>Issued: {new Date(issued.issued_at).toLocaleString()}</p>
      <ul>{issued.credentials.map((credential) => <li key={credential.id}>
        Credential <code>{credential.id}</code> for recipient <code>{credential.recipient_id}</code>
      </li>)}</ul>
    </div>}
  </section>
}
