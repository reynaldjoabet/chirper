/**
 * Client for the chirp API. Every response uses one envelope:
 * {"success":true,"data":...} or {"success":false,"error":"..."}, so callers get either the
 * unwrapped data or a thrown Error with the server's message — never the envelope itself.
 */

export type Chirp = {
  id: number
  author: string
  body: string
  createdAt: string // ISO-8601
  likes: number
}

export const MAX_BODY = 280

type Envelope<T> = { success: true; data: T } | { success: false; error: string }

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(path, init)
  // DELETE answers 204 with no body; res.json() would throw on it.
  if (res.status === 204) {
    return undefined as T
  }
  let payload: Envelope<T>
  try {
    payload = (await res.json()) as Envelope<T>
  } catch {
    throw new Error(`${res.status} ${res.statusText}`)
  }
  if (!payload.success) {
    throw new Error(payload.error)
  }
  return payload.data
}

export function listChirps(author?: string): Promise<Chirp[]> {
  const query = author ? `?author=${encodeURIComponent(author)}` : ''
  return request<Chirp[]>(`/api/chirps${query}`)
}

export function postChirp(author: string, body: string): Promise<Chirp> {
  return request<Chirp>('/api/chirps', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ author, body }),
  })
}

export function likeChirp(id: number): Promise<Chirp> {
  return request<Chirp>(`/api/chirps/${id}/like`, { method: 'POST' })
}

export function deleteChirp(id: number): Promise<void> {
  return request<void>(`/api/chirps/${id}`, { method: 'DELETE' })
}
