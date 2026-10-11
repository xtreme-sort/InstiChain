import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { Plus, UserCheck } from 'lucide-react'

type Advisor = { id: string; name: string; email: string; starts_at: string; ends_at: string; status: string }
type Club = { id: string; name: string; slug: string; description: string | null; advisors: Advisor[] }

async function adminRequest<T>(path: string, body?: object): Promise<T> {
  const response = await fetch(`/api/admin/${path}`, {
    method: body ? 'POST' : 'GET',
    headers: body ? { 'Content-Type': 'application/json' } : undefined,
    body: body ? JSON.stringify(body) : undefined,
    signal: AbortSignal.timeout(body ? 60000 : 15000),
  })
  const responseBody = await response.text()
  let data: Record<string, unknown> | null = null
  try {
    data = responseBody ? JSON.parse(responseBody) as Record<string, unknown> : null
  } catch {
    // A proxy or framework error page is handled by the status fallback below.
  }
  if (!response.ok) {
    const detail = data?.detail
    const message = data?.message
    throw new Error(typeof detail === 'string' ? detail : Array.isArray(detail)
      ? detail.map((item: { msg?: string }) => item.msg || 'Invalid value').join('. ')
      : typeof message === 'string' && message ? message
      : `The server rejected this request (HTTP ${response.status}). Check the backend terminal for details.`)
  }
  return data as T
}

export default function AdminPanel() {
  const [allowed, setAllowed] = useState(false)
  const [clubs, setClubs] = useState<Club[]>([])
  const [clubId, setClubId] = useState('')
  const [pending, setPending] = useState(false)
  const [error, setError] = useState('')
  const [message, setMessage] = useState('')

  useEffect(() => {
    let active = true
    void (async () => {
      try {
        const response = await fetch('/api/admin/access', { signal: AbortSignal.timeout(5000) })
        if (response.status === 403 || response.status === 401) return
        if (!response.ok) throw new Error('Unable to check administrator access.')
        const rows = await adminRequest<Club[]>('clubs')
        if (active) {
          setAllowed(true)
          setClubs(rows)
          setClubId(rows[0]?.id || '')
        }
      } catch (cause) {
        if (active) setError(cause instanceof Error ? cause.message : 'Unable to load administration.')
      }
    })()
    return () => { active = false }
  }, [])

  async function submit(event: FormEvent<HTMLFormElement>, kind: 'club' | 'advisor') {
    event.preventDefault()
    const form = event.currentTarget
    const values = new FormData(form)
    const value = (name: string) => String(values.get(name) || '').trim()
    setPending(true)
    setError('')
    setMessage('')
    try {
      if (kind === 'club') {
        const club = await adminRequest<{ id: string }>('clubs', { name: value('name'), slug: value('slug'), description: value('description') || null })
        setClubId(club.id)
      } else {
        await adminRequest<Record<string, unknown>>(`clubs/${clubId}/advisors`, {
          email: value('email'), verification_reference: value('verification_reference'),
          starts_at: new Date(value('starts_at')).toISOString(), ends_at: new Date(value('ends_at')).toISOString(),
        })
      }
      setMessage(kind === 'club' ? 'Club created.' : 'Advisor appointed.')
      form.reset()
      setClubs(await adminRequest<Club[]>('clubs'))
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Unable to save changes.')
    } finally {
      setPending(false)
    }
  }

  if (!allowed) return error ? <p role="alert" className="unavailable">{error}</p> : null

  return (
    <section className="administration" aria-labelledby="admin-title">
      <h2 id="admin-title">Institute administration</h2>
      {error && <p role="alert" className="unavailable">{error}</p>}
      {message && <p role="status">{message}</p>}
      <div className="admin-forms">
        <form onSubmit={(event) => void submit(event, 'club')}>
          <h3>Create club</h3>
          <fieldset disabled={pending}>
            <label>Club name<input name="name" required maxLength={200} /></label>
            <label>Slug<input name="slug" required maxLength={100} pattern="[a-z0-9]+(-[a-z0-9]+)*" placeholder="coding-club" /></label>
            <label>Description<textarea name="description" maxLength={2000} rows={3} /></label>
            <button className="primary" type="submit"><Plus size={18} aria-hidden="true" />Create club</button>
          </fieldset>
        </form>
        <form onSubmit={(event) => void submit(event, 'advisor')}>
          <h3>Appoint faculty advisor</h3>
          <fieldset disabled={pending || !clubs.length}>
            <label>Club<select required value={clubId} onChange={(event) => setClubId(event.target.value)}>
              {!clubs.length && <option value="">No clubs</option>}
              {clubs.map((club) => <option key={club.id} value={club.id}>{club.name}</option>)}
            </select></label>
            <label>Verified institute email<input name="email" type="email" required maxLength={254} /></label>
            <label>Institutional verification reference<input name="verification_reference" required maxLength={500} /></label>
            <label>Term starts (local time)<input name="starts_at" type="datetime-local" required /></label>
            <label>Term ends (local time)<input name="ends_at" type="datetime-local" required /></label>
            <button className="primary" type="submit"><UserCheck size={18} aria-hidden="true" />Appoint advisor</button>
          </fieldset>
        </form>
      </div>
      <h3>Clubs and advisor appointments</h3>
      {!clubs.length && <p>No clubs yet.</p>}
      <ul className="club-list">
        {clubs.map((club) => <li key={club.id}>
          <h4>{club.name}</h4>
          <p className="club-slug">{club.slug}</p>
          {club.description && <p>{club.description}</p>}
          {club.advisors.length ? club.advisors.map((advisor) => <div className="advisor-row" key={advisor.id}>
            <strong>{advisor.name}</strong><span>{advisor.status}</span>
            <span>{advisor.email}</span>
            <span>{new Date(advisor.starts_at).toLocaleDateString()} to {new Date(advisor.ends_at).toLocaleDateString()}</span>
          </div>) : <p>No advisor appointed.</p>}
        </li>)}
      </ul>
    </section>
  )
}
