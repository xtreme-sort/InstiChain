import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { CheckCircle2, LogOut, Mail, ShieldCheck } from 'lucide-react'

export type Account = { id: string; email: string; display_name: string }

async function submitVerification(path: string, body: object): Promise<string> {
  const response = await fetch(`/api/auth/email/${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
    signal: AbortSignal.timeout(15000),
  })
  const result = await response.json().catch(() => null)
  if (!response.ok) {
    const detail = result?.detail
    throw new Error(typeof detail === 'string' ? detail : response.status === 422
      ? 'Check your institute email address and required fields.'
      : 'The service is unavailable. Please try again.')
  }
  return result.message
}

async function fetchSession(): Promise<Account | null> {
  const response = await fetch('/api/auth/session', { signal: AbortSignal.timeout(15000) })
  if (!response.ok) return null
  return (await response.json()) as Account
}

type Props = {
  account: Account | null
  onSignedIn: (account: Account) => void
  onSignedOut: () => void
}

export default function EmailVerification({ account, onSignedIn, onSignedOut }: Props) {
  const [token, setToken] = useState(() => new URLSearchParams(window.location.hash.slice(1)).get('token') || '')
  const [email, setEmail] = useState('')
  const [displayName, setDisplayName] = useState('')
  const [pending, setPending] = useState(false)
  const [message, setMessage] = useState('')
  const [error, setError] = useState('')
  const [resendAt, setResendAt] = useState(0)
  const [remaining, setRemaining] = useState(0)

  useEffect(() => {
    if (window.location.hash) window.history.replaceState(null, '', window.location.pathname)
  }, [])

  useEffect(() => {
    if (!resendAt) return
    const tick = () => setRemaining(Math.max(0, Math.ceil((resendAt - Date.now()) / 1000)))
    tick()
    const timer = window.setInterval(tick, 1000)
    return () => window.clearInterval(timer)
  }, [resendAt])

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setPending(true)
    setError('')
    setMessage('')
    try {
      const result = await submitVerification(token ? 'confirm' : 'request', token
        ? { token, display_name: displayName }
        : { email: email.trim() })
      if (token) {
        const signedIn = await fetchSession()
        setToken('')
        if (signedIn) {
          onSignedIn(signedIn)
        } else {
          setMessage(result)
        }
      } else {
        setMessage(result)
        setRemaining(60)
        setResendAt(Date.now() + 60000)
      }
    } catch (cause) {
      setError(cause instanceof Error && cause.name === 'Error' ? cause.message : 'Unable to reach the service. Please try again.')
    } finally {
      setPending(false)
    }
  }

  async function logout() {
    setPending(true)
    try {
      await fetch('/api/auth/logout', { method: 'POST', signal: AbortSignal.timeout(15000) })
    } catch {
      // Clearing local state below is sufficient even if the request failed.
    } finally {
      setPending(false)
      onSignedOut()
    }
  }

  if (account) return (
    <section className="account" aria-labelledby="account-title">
      <CheckCircle2 size={32} className="connected" aria-hidden="true" />
      <h1 id="account-title">Signed in</h1>
      <p role="status">Signed in as {account.display_name} ({account.email}).</p>
      <button type="button" onClick={() => void logout()} disabled={pending}>
        <LogOut size={18} aria-hidden="true" />
        Log out
      </button>
    </section>
  )

  return (
    <section className="account" aria-labelledby="account-title">
      <h1 id="account-title">{token ? 'Verify institute email' : 'Sign in with your institute email'}</h1>
      <form onSubmit={submit}>
        {token ? <label htmlFor="display-name">Full name
          <input id="display-name" autoComplete="name" value={displayName}
            onChange={(event) => setDisplayName(event.target.value)} required maxLength={200} disabled={pending} />
        </label> : <label htmlFor="institute-email">Institute email
          <input id="institute-email" type="email" autoComplete="email" placeholder="name@smail.iitm.ac.in"
            value={email} onChange={(event) => setEmail(event.target.value)} required maxLength={254} disabled={pending} />
        </label>}
        <button className="primary" disabled={pending || (!token && remaining > 0)} type="submit">
          {token ? <ShieldCheck size={18} aria-hidden="true" /> : <Mail size={18} aria-hidden="true" />}
          {pending ? 'Please wait...' : token ? 'Verify email' : remaining > 0 ? `Resend in ${remaining}s` : resendAt ? 'Resend link' : 'Send sign-in link'}
        </button>
      </form>
      {message && <p role="status">{message}</p>}
      {error && <p role="alert" className="unavailable">{error}</p>}
      {token && <a href="/">Request a new link</a>}
    </section>
  )
}
