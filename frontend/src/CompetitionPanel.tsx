import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { Plus } from 'lucide-react'
import { apiRequest, postJson } from './api'
import ResultPanel from './ResultPanel'

export type OfficerClub = { id: string; name: string; slug: string; position: string }
export type Competition = {
  id: string
  club_id: string
  title: string
  description: string | null
  starts_at: string
  ends_at: string | null
  created_by_name: string
}

export default function CompetitionPanel({ clubs }: { clubs: OfficerClub[] }) {
  const [clubId, setClubId] = useState(clubs[0]?.id || '')
  const [competitions, setCompetitions] = useState<Competition[]>([])
  const [selectedId, setSelectedId] = useState('')
  const [loading, setLoading] = useState(false)
  const [pending, setPending] = useState(false)
  const [error, setError] = useState('')
  const [message, setMessage] = useState('')

  useEffect(() => {
    if (!clubId) return
    let active = true
    setLoading(true)
    setError('')
    setSelectedId('')
    void apiRequest<Competition[]>(`clubs/${clubId}/competitions`)
      .then((rows) => { if (active) setCompetitions(rows) })
      .catch((cause) => { if (active) setError(cause instanceof Error ? cause.message : 'Unable to load competitions.') })
      .finally(() => { if (active) setLoading(false) })
    return () => { active = false }
  }, [clubId])

  async function create(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const form = event.currentTarget
    const values = new FormData(form)
    const text = (field: string) => String(values.get(field) || '').trim()
    const start = new Date(text('starts_at'))
    const end = text('ends_at') ? new Date(text('ends_at')) : null
    if (end && end <= start) { setError('End time must be after start time.'); return }
    setPending(true)
    setError('')
    setMessage('')
    try {
      const created = await postJson<Competition>(`clubs/${clubId}/competitions`, {
        title: text('title'), description: text('description') || null,
        starts_at: start.toISOString(), ends_at: end?.toISOString() ?? null,
      })
      setCompetitions((current) => [created, ...current])
      setSelectedId(created.id)
      setMessage('Competition created.')
      form.reset()
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Unable to create competition.')
    } finally { setPending(false) }
  }

  const selected = competitions.find((competition) => competition.id === selectedId)
  return <section className="workflow" aria-labelledby="competitions-title">
    <div className="section-heading"><div><h1 id="competitions-title">Competitions</h1>
      <p>Club competitions and result submissions</p></div>
      <label>Club<select value={clubId} onChange={(event) => setClubId(event.target.value)}>
        {clubs.map((club) => <option value={club.id} key={club.id}>{club.name}</option>)}
      </select></label></div>
    {error && <p role="alert" className="unavailable">{error}</p>}
    {message && <p role="status" className="success-message">{message}</p>}
    <form className="workflow-form" onSubmit={(event) => void create(event)}>
      <h2>Create competition</h2>
      <fieldset disabled={pending || !clubId}>
        <label>Title<input name="title" required maxLength={250} /></label>
        <label>Starts<input name="starts_at" type="datetime-local" required /></label>
        <label>Ends<input name="ends_at" type="datetime-local" /></label>
        <label className="full-width">Description<textarea name="description" maxLength={5000} rows={3} /></label>
        <button className="primary" type="submit"><Plus size={18} aria-hidden="true" />Create</button>
      </fieldset>
    </form>
    <h2>Competition register</h2>
    {loading ? <p role="status">Loading competitions...</p> : competitions.length === 0 ?
      <p>No competitions have been created for this club.</p> : <div className="workflow-list">
        {competitions.map((competition) => <button type="button" key={competition.id}
          className={competition.id === selectedId ? 'selected' : ''}
          onClick={() => setSelectedId(competition.id)}>
          <strong>{competition.title}</strong><span>{new Date(competition.starts_at).toLocaleString()}</span>
        </button>)}
      </div>}
    {selected && <section className="workflow-detail" aria-label="Competition details">
      <h2>{selected.title}</h2>
      <p>{selected.description || 'No description.'}</p>
      <dl><dt>Starts</dt><dd>{new Date(selected.starts_at).toLocaleString()}</dd>
        <dt>Ends</dt><dd>{selected.ends_at ? new Date(selected.ends_at).toLocaleString() : 'Not set'}</dd>
        <dt>Created by</dt><dd>{selected.created_by_name}</dd></dl>
      <ResultPanel key={selected.id} clubId={clubId} competitionId={selected.id} />
    </section>}
  </section>
}
