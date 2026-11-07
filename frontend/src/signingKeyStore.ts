export type GeneratedIdentity = {
  publicKey: string
  privateKey: CryptoKey
}

export type SigningIdentityStatus = {
  publicKey: string
  fingerprint: string
  privateKeyAvailable: boolean
}

type StoredIdentity = GeneratedIdentity & { userId: string }
type ServerKey = {
  registered: boolean
  public_key?: string
  fingerprint?: string
}

const DATABASE_NAME = 'instichain-signing-identities'
const STORE_NAME = 'identities'
const provisioning = new Map<string, Promise<SigningIdentityStatus>>()

function openDatabase(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(DATABASE_NAME, 1)
    request.onupgradeneeded = () => {
      if (!request.result.objectStoreNames.contains(STORE_NAME)) {
        request.result.createObjectStore(STORE_NAME, { keyPath: 'userId' })
      }
    }
    request.onsuccess = () => resolve(request.result)
    request.onerror = () => reject(request.error || new Error('Unable to open secure browser storage.'))
  })
}

async function withStore<T>(mode: IDBTransactionMode, operation: (store: IDBObjectStore) => IDBRequest<T>): Promise<T> {
  const database = await openDatabase()
  return new Promise((resolve, reject) => {
    const transaction = database.transaction(STORE_NAME, mode)
    const request = operation(transaction.objectStore(STORE_NAME))
    request.onsuccess = () => resolve(request.result)
    request.onerror = () => reject(request.error || new Error('Unable to access the signing identity.'))
    transaction.oncomplete = () => database.close()
    transaction.onerror = () => {
      database.close()
      reject(transaction.error || new Error('Unable to save the signing identity.'))
    }
  })
}

function encodeBase64(bytes: Uint8Array): string {
  return window.btoa(String.fromCharCode(...bytes))
}

function decodeBase64(value: string): Uint8Array<ArrayBuffer> {
  const decoded = window.atob(value)
  const bytes = new Uint8Array(decoded.length)
  for (let index = 0; index < decoded.length; index += 1) bytes[index] = decoded.charCodeAt(index)
  return bytes
}

export async function generateSigningIdentity(): Promise<GeneratedIdentity> {
  if (!window.isSecureContext || !window.crypto?.subtle || !window.indexedDB) {
    throw new Error('Signing-key creation requires a secure browser context with IndexedDB support.')
  }
  const pair = await window.crypto.subtle.generateKey(
    { name: 'Ed25519' },
    false,
    ['sign', 'verify'],
  ) as CryptoKeyPair
  const publicKey = encodeBase64(new Uint8Array(await window.crypto.subtle.exportKey('raw', pair.publicKey)))
  return { publicKey, privateKey: pair.privateKey }
}

export async function saveSigningIdentity(userId: string, identity: GeneratedIdentity): Promise<void> {
  const stored: StoredIdentity = { userId, ...identity }
  await withStore('readwrite', (store) => store.put(stored))
}

async function loadSigningIdentity(userId: string): Promise<StoredIdentity | undefined> {
  return withStore('readonly', (store) => store.get(userId))
}

async function requestServerKey(method: 'GET' | 'POST', publicKey?: string): Promise<ServerKey> {
  const response = await fetch('/api/auth/signing-key', {
    method,
    headers: method === 'POST' ? { 'Content-Type': 'application/json' } : undefined,
    body: method === 'POST' ? JSON.stringify({ public_key: publicKey }) : undefined,
    signal: AbortSignal.timeout(15000),
  })
  const body = await response.json().catch(() => null)
  if (!response.ok) {
    throw new Error(typeof body?.detail === 'string' ? body.detail : 'Unable to prepare the signing identity.')
  }
  return body as ServerKey
}

function pendingId(userId: string): string { return `${userId}:pending` }

/** A replacement key is held as pending until the server confirms it, so a failed save can't lock the user out. */
async function promotePending(userId: string, serverKey: string | undefined): Promise<StoredIdentity | undefined> {
  const pending = await loadSigningIdentity(pendingId(userId))
  if (!pending || !serverKey || pending.publicKey !== serverKey) return undefined
  await saveSigningIdentity(userId, { publicKey: pending.publicKey, privateKey: pending.privateKey })
  await withStore('readwrite', (store) => store.delete(pendingId(userId)))
  return { ...pending, userId }
}

async function provision(userId: string): Promise<SigningIdentityStatus> {
  let server = await requestServerKey('GET')
  let local = await loadSigningIdentity(userId)
  if (server.registered && local?.publicKey !== server.public_key) {
    local = await promotePending(userId, server.public_key) ?? local
  }

  if (!server.registered) {
    if (!local) {
      local = { userId, ...await generateSigningIdentity() }
      await saveSigningIdentity(userId, local)
    }
    try {
      server = await requestServerKey('POST', local.publicKey)
    } catch (registrationError) {
      const confirmed = await requestServerKey('GET')
      if (!confirmed.registered || confirmed.public_key !== local.publicKey) throw registrationError
      server = confirmed
    }
  }

  const publicKey = server.public_key || ''
  return {
    publicKey,
    fingerprint: server.fingerprint || '',
    privateKeyAvailable: Boolean(local && local.publicKey === publicKey),
  }
}

export function loadOrProvisionSigningIdentity(userId: string): Promise<SigningIdentityStatus> {
  const active = provisioning.get(userId)
  if (active) return active
  const request = provision(userId).finally(() => provisioning.delete(userId))
  provisioning.set(userId, request)
  return request
}

export async function signPreparedPayload(userId: string, payload: string): Promise<string> {
  const identity = await loadSigningIdentity(userId)
  if (!identity?.privateKey) {
    throw new Error('Your signing private key is unavailable on this device.')
  }
  const signature = await window.crypto.subtle.sign(
    { name: 'Ed25519' },
    identity.privateKey,
    decodeBase64(payload),
  )
  return encodeBase64(new Uint8Array(signature))
}

async function signWith(privateKey: CryptoKey, bytes: Uint8Array<ArrayBuffer>): Promise<string> {
  return encodeBase64(new Uint8Array(await window.crypto.subtle.sign({ name: 'Ed25519' }, privateKey, bytes)))
}

async function postKeyApi<T>(path: string, body: object): Promise<T> {
  const response = await fetch(`/api/keys/${path}`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
    signal: AbortSignal.timeout(60000),
  })
  const data = await response.json().catch(() => null)
  if (!response.ok) throw new Error(typeof data?.detail === 'string' ? data.detail : 'The key change failed.')
  return data as T
}

/**
 * Replaces the current key. The old key signs the rotation and the new key signs the
 * same bytes to prove possession; the new private key never leaves the browser.
 */
export async function rotateSigningKey(userId: string): Promise<void> {
  const current = await loadSigningIdentity(userId)
  if (!current?.privateKey) throw new Error('Your current private key is unavailable on this device. Request a recovery instead.')
  const next = await generateSigningIdentity()
  await saveSigningIdentity(pendingId(userId), next)
  const prepared = await postKeyApi<{ document: object; signing_payload: string }>(
    'rotation/prepare', { new_public_key: next.publicKey })
  const bytes = decodeBase64(prepared.signing_payload)
  await postKeyApi('rotation', {
    document: prepared.document,
    signature: await signWith(current.privateKey, bytes),
    new_key_signature: await signWith(next.privateKey, bytes),
  })
  await promotePending(userId, next.publicKey)
  provisioning.delete(userId)
}

/** Asks an administrator to approve a fresh key after the old device was lost. */
export async function requestKeyRecovery(userId: string): Promise<{ id: string; expires_at: string }> {
  const next = await generateSigningIdentity()
  await saveSigningIdentity(pendingId(userId), next)
  // Must match the server's canonical JSON: sorted keys, no whitespace.
  const proof = JSON.stringify({ public_key: next.publicKey, purpose: 'instichain-key-recovery', user_id: userId })
  return postKeyApi('recovery-requests', {
    public_key: next.publicKey,
    proof: await signWith(next.privateKey, new TextEncoder().encode(proof) as Uint8Array<ArrayBuffer>),
  })
}
