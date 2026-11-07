import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { KeyRound, ShieldAlert } from 'lucide-react'
import { apiRequest, postJson } from './api'

type Recovery = {
  id: string
  email: string
  display_name: string
  public_key: string
  created_at: string
  expires_at: string
  advisor_key: string | null
}
type Revoked = {
  key_id: string
  revoked_at: string
  credentials_for_review: { id: string; award: string; competition: string; issued_at: string }[]
}

export default function KeyAdministration() {
  const [requests, setRequests] = useState<Recovery[]>([])
  const [revoked, setRevoked] = useState<Revoked | null>(null)
  const [pending, setPending] = useState(false)
  const [error, setError] = useState('')
  const [message, setMessage] = useState('')

  async function reload() { setRequests(await apiRequest<Recovery[]>('admin/key-recovery-requests')) }

  useEffect(() => {
    void reload().catch((cause) => setError(cause instanceof Error ? cause.message : 'Unable to load recovery requests.'))
  }, [])

  async function act(work: () => Promise<unknown>, done: string) {
    setPending(true); setError(''); setMessage('')
    try { await work(); setMessage(done); await reload() }
    catch (cause) { setError(cause instanceof Error ? cause.message : 'The key change failed.') }
    finally { setPending(false) }
  }

  function approve(event: FormEvent<HTMLFormElement>, request: Recovery) {
    event.preventDefault()
    const reference = String(new FormData(event.currentTarget).get('reference') || '').trim()
    void act(() => postJson(`admin/key-recovery-requests/${request.id}/approve`,
      { verification_reference: reference }), `New key approved for ${request.display_name}.`)
  }

  function revoke(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const form = new FormData(event.currentTarget)
    const when = String(form.get('compromised_at') || '')
    void act(async () => setRevoked(await postJson<Revoked>(`admin/keys/${String(form.get('key_id')).trim()}/revocation`, {
      reason: String(form.get('reason') || '').trim(),
      compromised_at: when ? new Date(when).toISOString() : null,
    })), 'Key revoked. Review the credentials it signed since the compromise.')
  }

  return <section className="key-administration" aria-labelledby="key-admin-title">
    <h3 id="key-admin-title"><KeyRound size={18} aria-hidden="true" /> Signing-key recovery</h3>
    <p>Approve a request only after confirming the person's identity through an institutional channel.
      Their old keys are retired; signatures made with them stay verifiable.</p>
    {error && <p role="alert" className="unavailable">{error}</p>}
    {message && <p role="status">{message}</p>}
    {requests.length === 0 ? <p>No pending recovery requests.</p> : <ul className="recovery-list">
      {requests.map((request) => <li key={request.id}>
        <strong>{request.display_name}</strong> <span>{request.email}</span>
        <small>{request.advisor_key ? 'Current advisor key will be retired.' : 'No active advisor key.'}
          {' '}Expires {new Date(request.expires_at).toLocaleString()}.</small>
        <code>{request.public_key}</code>
        <form onSubmit={(event) => approve(event, request)}>
          <fieldset disabled={pending}>
            <label>Verification reference<input name="reference" required maxLength={500}
              placeholder="e.g. Faculty office ticket 42" /></label>
            <div className="key-actions">
              <button className="primary" type="submit">Approve new key</button>
              <button type="button" onClick={() => void act(() => apiRequest(
                `admin/key-recovery-requests/${request.id}/cancel`, { method: 'POST' }), 'Request cancelled.')}>Cancel request</button>
            </div>
          </fieldset>
        </form>
      </li>)}</ul>}
    <h3><ShieldAlert size={18} aria-hidden="true" /> Revoke a compromised advisor key</h3>
    <form onSubmit={revoke}>
      <fieldset disabled={pending}>
        <label>Advisor key ID<input name="key_id" required pattern="[0-9a-f-]{36}" /></label>
        <label>Reason (recorded on the ledger)<input name="reason" required maxLength={500} /></label>
        <label>Compromised since (leave blank for now)<input name="compromised_at" type="datetime-local" /></label>
        <button className="primary" type="submit">Revoke key</button>
      </fieldset>
    </form>
    {revoked && <div className="revocation-review" role="region" aria-label="Credentials to review">
      <p>Revoked from {new Date(revoked.revoked_at).toLocaleString()}.</p>
      {revoked.credentials_for_review.length === 0 ? <p>The key signed no credentials since then.</p> : <>
        <p>These credentials were signed after the compromise. Their authority check now fails;
          revoke any that are not genuine.</p>
        <ul>{revoked.credentials_for_review.map((credential) =>
          <li key={credential.id}>{credential.award}, {credential.competition} ({new Date(credential.issued_at).toLocaleDateString()})</li>)}</ul>
      </>}
    </div>}
  </section>
}
