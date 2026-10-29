export async function apiRequest<T>(path: string, options: RequestInit = {}): Promise<T> {
  const response = await fetch(`/api/${path}`, {
    ...options,
    headers: options.body ? { 'Content-Type': 'application/json', ...options.headers } : options.headers,
    signal: options.signal ?? AbortSignal.timeout(options.method === 'POST' ? 60000 : 15000),
  })
  const text = await response.text()
  let body: Record<string, unknown> | null = null
  try { body = text ? JSON.parse(text) as Record<string, unknown> : null } catch { /* Use the HTTP status below. */ }
  if (!response.ok) {
    const detail = body?.detail
    throw new Error(typeof detail === 'string' ? detail : `Request failed (HTTP ${response.status}).`)
  }
  return body as T
}

export function postJson<T>(path: string, body: object): Promise<T> {
  return apiRequest<T>(path, { method: 'POST', body: JSON.stringify(body) })
}
