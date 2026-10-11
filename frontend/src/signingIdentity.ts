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

async function provision(userId: string): Promise<SigningIdentityStatus> {
  let server = await requestServerKey('GET')
  let local = await loadSigningIdentity(userId)

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
