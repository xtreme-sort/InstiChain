import { useEffect, useState } from 'react'
import { Award, Link2, RefreshCw, Settings2, ShieldCheck, Trophy, UserRound } from 'lucide-react'
import EmailVerification from './EmailVerification'
import AdminPanel from './AdminPanel'
import AdvisorPanel from './AdvisorPanel'
import CompetitionPanel from './CompetitionPanel'
import CredentialVault from './CredentialVault'
import type { OfficerClub } from './CompetitionPanel'
import type { Account } from './EmailVerification'

type Connection = 'checking' | 'connected' | 'unavailable'
type Workspace = 'account' | 'admin' | 'advisor' | 'competitions' | 'credentials'

export default function App() {
  const [connection, setConnection] = useState<Connection>('checking')
  const [attempt, setAttempt] = useState(0)
  const [account, setAccount] = useState<Account | null>(null)
  const [sessionChecked, setSessionChecked] = useState(false)
  const [workspace, setWorkspace] = useState<Workspace>('account')
  const [access, setAccess] = useState({ admin: false, advisor: false })
  const [officerClubs, setOfficerClubs] = useState<OfficerClub[]>([])
  const [accessLoading, setAccessLoading] = useState(false)
  const [accessError, setAccessError] = useState('')
  const [linkMode, setLinkMode] = useState(
    () => Boolean(new URLSearchParams(window.location.hash.slice(1)).get('token')),
  )

  useEffect(() => {
    const controller = new AbortController()
    const timeout = window.setTimeout(() => controller.abort(), 5000)
    let active = true
    setConnection('checking')

    async function checkConnection() {
      try {
        const response = await fetch('/api/health', { signal: controller.signal })
        if (!response.ok) throw new Error('API unavailable')
        const body: unknown = await response.json()
        if (!body || typeof body !== 'object' || !('status' in body) || body.status !== 'ok') {
          throw new Error('Unexpected health response')
        }
        if (active) setConnection('connected')
      } catch {
        if (active) setConnection('unavailable')
      } finally {
        window.clearTimeout(timeout)
      }
    }

    void checkConnection()
    return () => {
      active = false
      controller.abort()
      window.clearTimeout(timeout)
    }
  }, [attempt])

  useEffect(() => {
    let active = true
    async function restoreSession() {
      try {
        const response = await fetch('/api/auth/session', { signal: AbortSignal.timeout(5000) })
        if (response.ok && active) setAccount((await response.json()) as Account)
      } catch {
        // No session to restore; the sign-in form is shown instead.
      } finally {
        if (active) setSessionChecked(true)
      }
    }
    void restoreSession()
    return () => {
      active = false
    }
  }, [])

  useEffect(() => {
    if (!account) {
      setWorkspace('account')
      setAccess({ admin: false, advisor: false })
      setOfficerClubs([])
      return
    }
    let active = true
    setAccessLoading(true)
    setAccessError('')
    const roles = Promise.all(['/api/admin/access', '/api/advisor/access'].map(async (path) => {
      const response = await fetch(path, { signal: AbortSignal.timeout(5000) })
      if (response.status === 403) return false
      if (!response.ok) throw new Error('Unable to check your workspace access.')
      return true
    }))
    const clubs = fetch('/api/officer/clubs', { signal: AbortSignal.timeout(5000) }).then(async (response) => {
      if (!response.ok) throw new Error('Unable to check club officer access.')
      return await response.json() as OfficerClub[]
    })
    void Promise.all([roles, clubs]).then(([[admin, advisor], assignedClubs]) => {
      if (active) { setAccess({ admin, advisor }); setOfficerClubs(assignedClubs) }
    }).catch((cause) => {
      if (active) setAccessError(cause instanceof Error ? cause.message : 'Unable to check workspace access.')
    }).finally(() => { if (active) setAccessLoading(false) })
    return () => { active = false }
  }, [account?.id])

  return (
    <>
      <header className="header">
        <Link2 size={26} aria-hidden="true" />
        <span className="brand">InstiChain</span>
        <span className="version">0.1.0</span>
      </header>
      <main>
        {account && !linkMode && <nav className="workspace-nav" aria-label="Workspaces">
          <button type="button" className={workspace === 'account' ? 'selected' : ''}
            aria-current={workspace === 'account' ? 'page' : undefined} onClick={() => setWorkspace('account')}>
            <UserRound size={17} aria-hidden="true" /> Account
          </button>
          <button type="button" className={workspace === 'credentials' ? 'selected' : ''}
            aria-current={workspace === 'credentials' ? 'page' : undefined} onClick={() => setWorkspace('credentials')}>
            <Award size={17} aria-hidden="true" /> My credentials
          </button>
          {access.admin && <button type="button" className={workspace === 'admin' ? 'selected' : ''}
            aria-current={workspace === 'admin' ? 'page' : undefined} onClick={() => setWorkspace('admin')}>
            <Settings2 size={17} aria-hidden="true" /> Administration
          </button>}
          {access.advisor && <button type="button" className={workspace === 'advisor' ? 'selected' : ''}
            aria-current={workspace === 'advisor' ? 'page' : undefined} onClick={() => setWorkspace('advisor')}>
            <ShieldCheck size={17} aria-hidden="true" /> Advisor
          </button>}
          {officerClubs.length > 0 && <button type="button" className={workspace === 'competitions' ? 'selected' : ''}
            aria-current={workspace === 'competitions' ? 'page' : undefined} onClick={() => setWorkspace('competitions')}>
            <Trophy size={17} aria-hidden="true" /> Competitions
          </button>}
        </nav>}
        {accessLoading && <p role="status">Checking workspace access...</p>}
        {accessError && <p role="alert" className="unavailable">{accessError}</p>}
        {sessionChecked && (!account || linkMode || workspace === 'account') && (
          <EmailVerification account={account} onSignedIn={setAccount} onSignedOut={() => setAccount(null)}
            onLinkModeChange={setLinkMode} />
        )}
        {account && !linkMode && workspace === 'admin' && access.admin && <AdminPanel key={account.id} />}
        {account && !linkMode && workspace === 'advisor' && access.advisor &&
          <AdvisorPanel key={`advisor-${account.id}`} userId={account.id} />}
        {account && !linkMode && workspace === 'competitions' && officerClubs.length > 0 &&
          <CompetitionPanel key={`competitions-${account.id}`} clubs={officerClubs} />}
        {account && !linkMode && workspace === 'credentials' && <CredentialVault key={account.id} />}
        <h2>Service status</h2>
        <section className="service" aria-label="Backend connection">
          <span>InstiChain API</span>
          <span className={`status ${connection}`} role="status">
            <span className="dot" aria-hidden="true" />
            {connection === 'checking' ? 'Connecting...' : connection === 'connected' ? 'Connected' : 'Unavailable'}
          </span>
          <button
            type="button"
            title="Check connection"
            aria-label="Check connection"
            disabled={connection === 'checking'}
            onClick={() => setAttempt((value) => value + 1)}
          >
            <RefreshCw size={18} aria-hidden="true" />
          </button>
        </section>
      </main>
    </>
  )
}
