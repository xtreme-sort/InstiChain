import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { RotateCcw, UserPlus } from 'lucide-react'
import { signPreparedPayload } from './signingIdentity'

type Club = {
  id: string
  name: string
  slug: string
  description: string | null
  appointment_id: string
  starts_at: string
  ends_at: string
}

type ClubRole = {
  id: string
  club_id: string
  user_id: string
  position: 'club_head' | 'office_bearer'
  starts_at: string
  ends_at: string
  email: string
  display_name: string
  status: 'active' | 'scheduled' | 'expired' | 'revoked'
  revoked_at: string | null
}

type PreparedAction = {
  document: Record<string, unknown>
  signing_payload: string
}

type Props = { userId: string }

async function advisorRequest<T>(path: string, body?: object): Promise<T> {
  const response = await fetch(`/api/advisor/${path}`, {
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
    // Non-JSON proxy errors use the status fallback below.
  }
  if (!response.ok) {
    const detail = data?.detail
    const message = data?.message
    throw new Error(typeof detail === 'string' ? detail
      : typeof message === 'string' && message ? message
      : `The server rejected this request (HTTP ${response.status}). Check the backend terminal for details.`)
  }
  return data as T
}

function roleLabel(position: ClubRole['position']): string {
  return position === 'club_head' ? 'Club head' : 'Office bearer'
}

export default function AdvisorPanel({ userId }: Props) {
  const [allowed, setAllowed] = useState(false)
  const [clubs, setClubs] = useState<Club[]>([])
  const [clubId, setClubId] = useState('')
  const [roles, setRoles] = useState<ClubRole[]>([])
  const [pending, setPending] = useState(false)
  const [error, setError] = useState('')
  const [message, setMessage] = useState('')

  useEffect(() => {
    let active = true
    void (async () => {
      try {
        const response = await fetch('/api/advisor/access', { signal: AbortSignal.timeout(5000) })
        if (response.status === 401 || response.status === 403) return
        if (!response.ok) throw new Error('Unable to check faculty advisor access.')
        const assignedClubs = await advisorRequest<Club[]>('clubs')
        if (active) {
          setAllowed(true)
          setClubs(assignedClubs)
          setClubId(assignedClubs[0]?.id || '')
        }
      } catch (cause) {
        if (active) setError(cause instanceof Error ? cause.message : 'Unable to load advisor access.')
      }
    })()
    return () => { active = false }
  }, [])

  useEffect(() => {
    if (!allowed || !clubId) {
      setRoles([])
      return
    }
    let active = true
    setError('')
    void advisorRequest<ClubRole[]>(`clubs/${clubId}/roles`)
      .then((rows) => { if (active) setRoles(rows) })
      .catch((cause) => {
        if (active) setError(cause instanceof Error ? cause.message : 'Unable to load club roles.')
      })
    return () => { active = false }
  }, [allowed, clubId])

  async function signAndSubmit(preparePath: string, submitPath: string, draft: object) {
    const prepared = await advisorRequest<PreparedAction>(preparePath, draft)
    const signature = await signPreparedPayload(userId, prepared.signing_payload)
    await advisorRequest<Record<string, unknown>>(submitPath, {
      document: prepared.document,
      signature,
    })
  }

  async function refreshRoles() {
    setRoles(await advisorRequest<ClubRole[]>(`clubs/${clubId}/roles`))
  }

  async function grantRole(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const form = event.currentTarget
    const values = new FormData(form)
    const value = (name: string) => String(values.get(name) || '').trim()
    setPending(true)
    setError('')
    setMessage('')
    try {
      await signAndSubmit(`clubs/${clubId}/role-grants/prepare`, `clubs/${clubId}/role-grants`, {
        email: value('email'),
        position: value('position'),
        starts_at: new Date(value('starts_at')).toISOString(),
        ends_at: new Date(value('ends_at')).toISOString(),
      })
      setMessage('Club role granted and recorded on Fabric.')
      form.reset()
      await refreshRoles()
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Unable to grant the club role.')
    } finally {
      setPending(false)
    }
  }

  async function revokeRole(event: FormEvent<HTMLFormElement>, role: ClubRole) {
    event.preventDefault()
    const form = event.currentTarget
    const reason = String(new FormData(form).get('reason') || '').trim()
    setPending(true)
    setError('')
    setMessage('')
    try {
      const base = `clubs/${clubId}/roles/${role.id}/revocations`
      await signAndSubmit(`${base}/prepare`, base, { reason })
      setMessage(`${roleLabel(role.position)} role revoked and recorded on Fabric.`)
      await refreshRoles()
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Unable to revoke the club role.')
    } finally {
      setPending(false)
    }
  }

  if (!allowed) return error ? <p role="alert" className="unavailable">{error}</p> : null

  return (
    <section className="advisor-panel" aria-labelledby="advisor-title">
      <div className="section-heading">
        <div>
          <h2 id="advisor-title">Faculty advisor</h2>
          <p>Manage authorized student roles for your assigned club.</p>
        </div>
        <label>Assigned club<select value={clubId} onChange={(event) => setClubId(event.target.value)}>
          {clubs.map((club) => <option key={club.id} value={club.id}>{club.name}</option>)}
        </select></label>
      </div>

      {error && <p role="alert" className="unavailable">{error}</p>}
      {message && <p role="status" className="success-message">{message}</p>}

      <form className="role-grant-form" onSubmit={(event) => void grantRole(event)}>
        <h3>Assign student role</h3>
        <fieldset disabled={pending || !clubId}>
          <label>Verified student email<input name="email" type="email" required maxLength={254} /></label>
          <label>Role<select name="position" required defaultValue="club_head">
            <option value="club_head">Club head</option>
            <option value="office_bearer">Office bearer</option>
          </select></label>
          <label>Term starts (local time)<input name="starts_at" type="datetime-local" required /></label>
          <label>Term ends (local time)<input name="ends_at" type="datetime-local" required /></label>
          <button className="primary" type="submit"><UserPlus size={18} aria-hidden="true" />Assign role</button>
        </fieldset>
      </form>

      <div className="role-register">
        <h3>Club role register</h3>
        {!roles.length && <p>No student roles have been assigned for this club.</p>}
        <ul>
          {roles.map((role) => <li key={role.id}>
            <div className="role-summary">
              <div><strong>{role.display_name}</strong><span>{role.email}</span></div>
              <div><strong>{roleLabel(role.position)}</strong><span className={`role-status ${role.status}`}>{role.status}</span></div>
              <span>{new Date(role.starts_at).toLocaleString()} to {new Date(role.ends_at).toLocaleString()}</span>
            </div>
            {(role.status === 'active' || role.status === 'scheduled') &&
              <form className="revoke-form" onSubmit={(event) => void revokeRole(event, role)}>
                <label>Revocation reason<input name="reason" required maxLength={500} /></label>
                <button type="submit" disabled={pending} title="Revoke role">
                  <RotateCcw size={17} aria-hidden="true" /><span>Revoke</span>
                </button>
              </form>}
          </li>)}
        </ul>
      </div>
    </section>
  )
}
