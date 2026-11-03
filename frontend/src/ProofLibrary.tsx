import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { Cloud, Link2, RefreshCw, Unplug, Upload } from 'lucide-react'
import { apiRequest, postJson } from './api'

type Connection = { connected: boolean; status: string; scopes: string }
export type Proof = {
  id: string
  display_name: string
  mime_type: string
  byte_size: number
  sha256_digest: string
  availability: string
}
type Health = { archive_status: string; provider_status: string; checked_at: string | null }

export default function ProofLibrary({ selectedId, onSelect }: {
  selectedId: string; onSelect: (id: string) => void
}) {
  const [connection, setConnection] = useState<Connection | null>(null)
  const [proofs, setProofs] = useState<Proof[]>([])
  const [health, setHealth] = useState<Health | null>(null)
  const [pending, setPending] = useState(false)
  const [error, setError] = useState('')

  useEffect(() => {
    let active = true
    void Promise.all([
      apiRequest<Connection>('storage/google/status'), apiRequest<Proof[]>('proofs'),
    ]).then(([status, items]) => {
      if (active) { setConnection(status); setProofs(items) }
    }).catch((cause) => {
      if (active) setError(cause instanceof Error ? cause.message : 'Unable to load proofs.')
    })
    return () => { active = false }
  }, [])

  async function connect() {
    setPending(true); setError('')
    try {
      const result = await apiRequest<{ authorization_url: string }>('storage/google/connect', { method: 'POST' })
      const target = new URL(result.authorization_url)
      if (target.protocol !== 'https:' || target.hostname !== 'accounts.google.com') {
        throw new Error('The authorization destination is invalid.')
      }
      window.location.assign(target.toString())
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Unable to connect Drive.')
      setPending(false)
    }
  }

  async function disconnect() {
    if (!window.confirm('Disconnect Google Drive? Archived proofs and issued credentials will remain.')) return
    setPending(true); setError('')
    try {
      await apiRequest<void>('storage/google/disconnect', { method: 'POST' })
      setConnection({ connected: false, status: 'disconnected', scopes: '' })
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Unable to disconnect Drive.')
    } finally { setPending(false) }
  }

  async function importFile(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const form = event.currentTarget
    const fileId = String(new FormData(form).get('file_id') || '').trim()
    setPending(true); setError('')
    try {
      const proof = await postJson<Proof>('proofs/import', { file_id: fileId })
      setProofs((current) => [proof, ...current.filter((item) => item.id !== proof.id)])
      onSelect(proof.id)
      form.reset()
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Unable to import the proof.')
    } finally { setPending(false) }
  }

  async function check() {
    if (!selectedId) return
    setPending(true); setError('')
    try {
      setHealth(await apiRequest<Health>(`proofs/${selectedId}/check`, { method: 'POST' }))
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Unable to check proof integrity.')
    } finally { setPending(false) }
  }

  return <section className="proof-library" aria-labelledby="proof-library-title">
    <h3 id="proof-library-title"><Cloud size={18} aria-hidden="true" />Archived proofs</h3>
    {error && <p role="alert" className="unavailable">{error}</p>}
    {connection === null ? <p role="status">Loading Drive connection...</p> : connection.connected ?
      <div className="proof-connection"><span>Google Drive connected</span>
        <button type="button" disabled={pending} onClick={() => void disconnect()} title="Disconnect Google Drive">
          <Unplug size={16} aria-hidden="true" />Disconnect
        </button></div> :
      <button type="button" disabled={pending} onClick={() => void connect()}>
        <Link2 size={16} aria-hidden="true" />Connect Google Drive
      </button>}
    {connection?.connected && <form className="proof-import" onSubmit={(event) => void importFile(event)}>
      <label>Drive file ID<input name="file_id" required maxLength={200} pattern="[A-Za-z0-9_-]+" /></label>
      <button type="submit" disabled={pending}><Upload size={16} aria-hidden="true" />Import</button>
    </form>}
    {proofs.length > 0 && <div className="proof-selection">
      <label>Selected proof<select value={selectedId} onChange={(event) => { onSelect(event.target.value); setHealth(null) }}>
        <option value="">Choose a proof</option>
        {proofs.map((proof) => <option value={proof.id} key={proof.id}>{proof.display_name} ({proof.byte_size} bytes)</option>)}
      </select></label>
      <button type="button" disabled={pending || !selectedId} onClick={() => void check()} title="Check proof integrity">
        <RefreshCw size={16} aria-hidden="true" />Check
      </button>
    </div>}
    {health && <p role="status">Archive: {health.archive_status.replaceAll('_', ' ')}; Drive: {health.provider_status.replaceAll('_', ' ')}.</p>}
  </section>
}
