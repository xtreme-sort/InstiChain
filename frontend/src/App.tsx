import { useEffect, useState } from 'react'
import { Link2, RefreshCw } from 'lucide-react'
import EmailVerification from './EmailVerification'
import AdminPanel from './AdminPanel'
import type { Account } from './EmailVerification'

type Connection = 'checking' | 'connected' | 'unavailable'

export default function App() {
  const [connection, setConnection] = useState<Connection>('checking')
  const [attempt, setAttempt] = useState(0)
  const [account, setAccount] = useState<Account | null>(null)
  const [sessionChecked, setSessionChecked] = useState(false)
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

  return (
    <>
      <header className="header">
        <Link2 size={26} aria-hidden="true" />
        <span className="brand">InstiChain</span>
        <span className="version">0.1.0</span>
      </header>
      <main>
        {sessionChecked && (
          <EmailVerification account={account} onSignedIn={setAccount} onSignedOut={() => setAccount(null)}
            onLinkModeChange={setLinkMode} />
        )}
        {account && !linkMode && <AdminPanel key={account.id} />}
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
