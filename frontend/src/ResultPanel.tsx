import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { Pencil, Plus } from 'lucide-react'
import { apiRequest, postJson } from './api'

export type Submission = {
  id: string
  result_id: string
  competition_id: string
  version: number
  award: string
  proof_digest: string
  submitted_by_name: string
  submitted_at: string
  recipients: { id: string; email: string; display_name: string }[]
}

type Props = { clubId: string; competitionId: string }

export default function ResultPanel({ clubId, competitionId }: Props) {
  const [submissions, setSubmissions] = useState<Submission[]>([])
  const [correcting, setCorrecting] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)
  const [pending, setPending] = useState(false)
  const [error, setError] = useState('')
  const [message, setMessage] = useState('')
  const base = `clubs/${clubId}/competitions/${competitionId}/results`

  useEffect(() => {
    let active = true
    setLoading(true)
    setError('')
    setCorrecting(null)
    void apiRequest<Submission[]>(base).then((rows) => {
      if (active) setSubmissions(rows)
    }).catch((cause) => {
      if (active) setError(cause instanceof Error ? cause.message : 'Unable to load results.')
    }).finally(() => { if (active) setLoading(false) })
    return () => { active = false }
  }, [base])

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const form = event.currentTarget
    const fields = new FormData(form)
    const award = String(fields.get('award') || '').trim()
    const digest = String(fields.get('proof_digest') || '').trim().toLowerCase()
    const emails = String(fields.get('recipient_emails') || '').split(/[\s,;]+/).map((value) => value.trim()).filter(Boolean)
    if (!/^[0-9a-f]{64}$/.test(digest)) { setError('Enter a 64-character SHA-256 digest.'); return }
    if (!emails.length || new Set(emails.map((value) => value.toLowerCase())).size !== emails.length) {
      setError('Enter at least one unique recipient email.'); return
    }
    setPending(true)
    setError('')
    setMessage('')
    try {
      const path = correcting ? `${base}/${correcting}/versions` : base
      const created = await postJson<Submission>(path, {
        award, proof_digest: digest, recipient_emails: emails,
      })
      setSubmissions((current) => [...current, created])
      setCorrecting(null)
      setMessage(`Result version ${created.version} recorded.`)
      form.reset()
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Unable to submit result.')
    } finally { setPending(false) }
  }

  const latest = submissions.filter((submission) => !submissions.some((other) =>
    other.result_id === submission.result_id && other.version > submission.version))
  const source = submissions.find((submission) => submission.result_id === correcting &&
    submission.version === Math.max(...submissions.filter((row) => row.result_id === correcting).map((row) => row.version)))

  return <section className="results-panel" aria-labelledby="results-title">
    <h2 id="results-title">Results</h2>
    {error && <p role="alert" className="unavailable">{error}</p>}
    {message && <p role="status" className="success-message">{message}</p>}
    {loading ? <p role="status">Loading results...</p> : latest.length === 0 ? <p>No results submitted yet.</p> :
      <ul className="result-list">{latest.map((row) => <li key={row.id}>
        <div><strong>{row.award}</strong> <span>Version {row.version}</span></div>
        <p>{row.recipients.map((recipient) => recipient.display_name).join(', ')}</p>
        <p>Submitted by {row.submitted_by_name} on {new Date(row.submitted_at).toLocaleString()}</p>
        <details><summary>Submission history and proof digest</summary>
          {submissions.filter((version) => version.result_id === row.result_id).map((version) =>
            <p key={version.id}>Version {version.version}: {version.award} | SHA-256 <code>{version.proof_digest}</code></p>)}
        </details>
        <button type="button" className="text-command" onClick={() => setCorrecting(row.result_id)}>
          <Pencil size={16} aria-hidden="true" />Correct result
        </button>
      </li>)}</ul>}
    <form key={correcting || 'new'} className="workflow-form" onSubmit={(event) => void submit(event)}>
      <h3>{correcting ? 'Submit corrected version' : 'Submit result'}</h3>
      <fieldset disabled={pending}>
        <label>Award<input name="award" required maxLength={250} defaultValue={source?.award || ''} /></label>
        <label>Proof SHA-256 digest<input name="proof_digest" required minLength={64} maxLength={64}
          pattern="[0-9a-fA-F]{64}" defaultValue={source?.proof_digest || ''} /></label>
        <label className="full-width">Verified recipient emails<textarea name="recipient_emails" required rows={2}
          defaultValue={source?.recipients.map((recipient) => recipient.email).join('\n') || ''} /></label>
        <p className="full-width">Enter the SHA-256 digest of your supporting file. File upload and verification are not yet available.</p>
        <button className="primary" type="submit">{correcting ? <Pencil size={18} aria-hidden="true" /> : <Plus size={18} aria-hidden="true" />}
          {correcting ? 'Submit correction' : 'Submit result'}</button>
      </fieldset>
      {correcting && <button type="button" className="text-command" onClick={() => setCorrecting(null)}>Cancel correction</button>}
    </form>
  </section>
}
