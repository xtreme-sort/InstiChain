import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { Check, Copy, Link2, Share2, XCircle } from 'lucide-react'
import QRCode from 'qrcode'
import { apiRequest, postJson } from './api'

export type ShareableCredential = { id: string; award: string; competition_title: string; lifecycle_status: string }
type Share = {
  id: string
  label: string | null
  created_at: string
  expires_at: string
  revoked_at: string | null
  credential_ids: string[]
}
type Created = { id: string; token: string; expires_at: string; credential_ids: string[] }

/** The token rides in the fragment, which browsers never send to the server. */
export function shareUrl(token: string): string {
  return `${window.location.origin}/verify#share=${encodeURIComponent(token)}`
}

function shareState(share: Share): 'active' | 'expired' | 'revoked' {
  if (share.revoked_at) return 'revoked'
  return new Date(share.expires_at) <= new Date() ? 'expired' : 'active'
}

export default function ShareManager({ credentials }: { credentials: ShareableCredential[] }) {
  const shareable = credentials.filter((credential) => credential.lifecycle_status === 'active')
  const [shares, setShares] = useState<Share[]>([])
  const [selected, setSelected] = useState<string[]>([])
  const [created, setCreated] = useState<{ url: string; qr: string } | null>(null)
  const [copied, setCopied] = useState(false)
  const [pending, setPending] = useState(false)
  const [error, setError] = useState('')
  const [message, setMessage] = useState('')

  async function reload() { setShares(await apiRequest<Share[]>('shares')) }

  useEffect(() => {
    void reload().catch((cause) => setError(cause instanceof Error ? cause.message : 'Unable to load share links.'))
  }, [])

  function toggle(id: string) {
    setSelected((current) => current.includes(id) ? current.filter((value) => value !== id) : [...current, id])
  }

  async function create(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const form = new FormData(event.currentTarget)
    const days = Number(form.get('days'))
    setPending(true); setError(''); setMessage(''); setCreated(null); setCopied(false)
    try {
      const result = await postJson<Created>('shares', {
        request_id: crypto.randomUUID(),
        label: String(form.get('label') || '').trim() || null,
        expires_at: new Date(Date.now() + days * 86_400_000).toISOString(),
        credential_ids: selected,
      })
      const url = shareUrl(result.token)
      setCreated({ url, qr: await QRCode.toDataURL(url, { margin: 1, width: 220 }) })
      setSelected([])
      await reload()
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Unable to create a share link.')
    } finally {
      setPending(false)
    }
  }

  async function copy() {
    if (!created) return
    try {
      await navigator.clipboard.writeText(created.url)
      setCopied(true)
    } catch {
      setError('Copying failed. Select the link text and copy it manually.')
    }
  }

  async function revoke(share: Share) {
    setPending(true); setError(''); setMessage('')
    try {
      await apiRequest<null>(`shares/${share.id}/revoke`, { method: 'POST' })
      setMessage('Link revoked. It stops working immediately.')
      await reload()
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Unable to revoke this link.')
    } finally {
      setPending(false)
    }
  }

  const titles = new Map(credentials.map((credential) => [credential.id, credential.award]))
  return <section className="share-manager" aria-labelledby="share-title">
    <h2 id="share-title"><Share2 size={19} aria-hidden="true" /> Share with a verifier</h2>
    <p>A verifier opens the link without an account and sees only the credentials you select,
      checked live against the ledger.</p>
    {error && <p role="alert" className="unavailable">{error}</p>}
    {message && <p role="status">{message}</p>}
    {shareable.length === 0 ? <p>Only active credentials can be shared.</p> :
      <form onSubmit={(event) => void create(event)}>
        <fieldset disabled={pending}>
          <legend>Credentials to include</legend>
          {shareable.map((credential) => <label className="share-option" key={credential.id}>
            <input type="checkbox" checked={selected.includes(credential.id)} onChange={() => toggle(credential.id)} />
            <span><strong>{credential.award}</strong> {credential.competition_title}</span>
          </label>)}
          <label>Label (only you see this)<input name="label" maxLength={120} placeholder="e.g. Internship application" /></label>
          <label>Link expires after
            <select name="days" defaultValue="7">
              <option value="1">1 day</option><option value="7">7 days</option>
              <option value="14">14 days</option><option value="30">30 days</option>
            </select></label>
          <button className="primary" type="submit" disabled={selected.length === 0}>
            <Link2 size={17} aria-hidden="true" /> Create link</button>
        </fieldset>
      </form>}
    {created && <div className="share-created" role="region" aria-label="New share link">
      <p><strong>Copy this link now.</strong> For your security it is shown only once.</p>
      <img src={created.qr} width={220} height={220} alt="QR code for the share link" />
      <code className="share-url">{created.url}</code>
      <button type="button" onClick={() => void copy()}>
        {copied ? <Check size={17} aria-hidden="true" /> : <Copy size={17} aria-hidden="true" />}
        {copied ? 'Copied' : 'Copy link'}</button>
    </div>}
    <h3>Your links</h3>
    {shares.length === 0 ? <p>You have not created any share links.</p> :
      <ul className="share-list">{shares.map((share) => {
        const state = shareState(share)
        return <li key={share.id}>
          <div><strong>{share.label || 'Untitled link'}</strong>
            <span className={`share-state ${state}`}>{state}</span></div>
          <small>{share.credential_ids.map((id) => titles.get(id) || 'Credential').join(', ')}</small>
          <small>Expires {new Date(share.expires_at).toLocaleString()}</small>
          {state === 'active' && <button type="button" disabled={pending} onClick={() => void revoke(share)}
            aria-label={`Revoke ${share.label || 'untitled link'}`}>
            <XCircle size={16} aria-hidden="true" /> Revoke</button>}
        </li>
      })}</ul>}
  </section>
}
