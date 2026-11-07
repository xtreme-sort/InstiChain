import { useEffect, useState } from 'react'
import { Check, Copy, KeyRound, LifeBuoy, RefreshCw, RotateCw } from 'lucide-react'
import { apiRequest } from './api'
import { loadOrProvisionSigningIdentity, requestKeyRecovery, rotateSigningKey } from './signingKeyStore'
import type { SigningIdentityStatus } from './signingKeyStore'

type Props = { userId: string }
type KeyStatus = {
  advisor_key: { id: string; created_at: string } | null
  pending_recovery: { id: string; expires_at: string } | null
}

export default function SigningIdentity({ userId }: Props) {
  const [identity, setIdentity] = useState<SigningIdentityStatus | null>(null)
  const [error, setError] = useState('')
  const [copied, setCopied] = useState(false)
  const [attempt, setAttempt] = useState(0)
  const [keyStatus, setKeyStatus] = useState<KeyStatus | null>(null)
  const [busy, setBusy] = useState(false)
  const [notice, setNotice] = useState('')

  useEffect(() => {
    let active = true
    setIdentity(null)
    setError('')
    void loadOrProvisionSigningIdentity(userId)
      .then((result) => { if (active) setIdentity(result) })
      .catch((cause) => {
        if (active) setError(cause instanceof Error ? cause.message : 'Unable to load the signing identity.')
      })
    return () => { active = false }
  }, [userId, attempt])

  useEffect(() => {
    let active = true
    void apiRequest<KeyStatus>('keys/status').then((result) => { if (active) setKeyStatus(result) })
      .catch(() => { if (active) setKeyStatus(null) })
    return () => { active = false }
  }, [userId, attempt])

  async function change(kind: 'rotate' | 'recover') {
    const prompt = kind === 'rotate'
      ? 'Replace your signing key? Credentials you already signed stay valid, but the old key can no longer sign.'
      : 'Request a new signing key? An administrator must confirm your identity before it can be used.'
    if (!window.confirm(prompt)) return
    setBusy(true); setError(''); setNotice('')
    try {
      if (kind === 'rotate') {
        await rotateSigningKey(userId)
        setNotice('Signing key rotated. The change is recorded on the ledger.')
      } else {
        const request = await requestKeyRecovery(userId)
        setNotice(`Recovery requested. Ask the institute administrator to approve it before ${new Date(request.expires_at).toLocaleString()}.`)
      }
      setAttempt((value) => value + 1)
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'The key change failed.')
    } finally {
      setBusy(false)
    }
  }

  async function copyPublicKey() {
    if (!identity) return
    await navigator.clipboard.writeText(identity.publicKey)
    setCopied(true)
    window.setTimeout(() => setCopied(false), 1500)
  }

  return (
    <div className="signing-identity">
      <h2><KeyRound size={19} aria-hidden="true" />Signing identity</h2>
      {!identity && !error && <p role="status">Preparing signing key...</p>}
      {error && <div className="identity-error">
        <p role="alert" className="unavailable">{error}</p>
        <button type="button" title="Retry signing-key setup" aria-label="Retry signing-key setup"
          onClick={() => setAttempt((value) => value + 1)}>
          <RefreshCw size={17} aria-hidden="true" />
        </button>
      </div>}
      {identity && <dl>
        <dt>Public key</dt>
        <dd className="identity-public-key"><code>{identity.publicKey}</code>
          <button type="button" title="Copy public key" aria-label="Copy public key"
            onClick={() => void copyPublicKey()}>
            {copied ? <Check size={16} aria-hidden="true" /> : <Copy size={16} aria-hidden="true" />}
          </button>
        </dd>
        <dt>Fingerprint</dt>
        <dd><code>{identity.fingerprint}</code></dd>
        <dt>Private key</dt>
        <dd className={identity.privateKeyAvailable ? 'connected' : 'unavailable'}>
          {identity.privateKeyAvailable ? 'Available on this device' : 'Unavailable on this device'}
        </dd>
      </dl>}
      {notice && <p role="status">{notice}</p>}
      {keyStatus?.pending_recovery && <p>
        A recovery request is waiting for administrator approval (expires {new Date(keyStatus.pending_recovery.expires_at).toLocaleString()}).
      </p>}
      <div className="key-actions">
        {identity?.privateKeyAvailable && keyStatus?.advisor_key &&
          <button type="button" disabled={busy} onClick={() => void change('rotate')}>
            <RotateCw size={16} aria-hidden="true" /> Rotate signing key</button>}
        {(error || (identity && !identity.privateKeyAvailable)) && !keyStatus?.pending_recovery &&
          <button type="button" disabled={busy} onClick={() => void change('recover')}>
            <LifeBuoy size={16} aria-hidden="true" /> Request key recovery</button>}
      </div>
    </div>
  )
}
