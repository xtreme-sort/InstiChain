import { useEffect, useState } from 'react'
import { Check, Copy, KeyRound, RefreshCw } from 'lucide-react'
import { loadOrProvisionSigningIdentity } from './signingIdentity'
import type { SigningIdentityStatus } from './signingIdentity'

type Props = { userId: string }

export default function SigningIdentity({ userId }: Props) {
  const [identity, setIdentity] = useState<SigningIdentityStatus | null>(null)
  const [error, setError] = useState('')
  const [copied, setCopied] = useState(false)
  const [attempt, setAttempt] = useState(0)

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
    </div>
  )
}
