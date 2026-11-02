import { getLanguage } from '../i18n/language'
import { getToken, markNeedsReauth } from '../auth/session'
import { toProblem, toTransportError } from './problem'

/** Failure with a kind the UI translates itself; messages here are never shown to users. */
export class ApiError extends Error {
  readonly kind: 'http' | 'unexpected-response'
  readonly status?: number

  constructor(kind: 'http' | 'unexpected-response', status?: number) {
    super(kind === 'http' ? `HTTP ${status}` : 'Unexpected response')
    this.name = 'ApiError'
    this.kind = kind
    this.status = status
  }
}

/** fetch + Accept-Language for the backend's localized error texts. */
export function apiFetch(path: string, init?: RequestInit): Promise<Response> {
  const headers = new Headers(init?.headers)
  headers.set('Accept-Language', getLanguage())
  return fetch(path, { ...init, headers })
}

export type RequestOptions = {
  method?: 'GET' | 'POST' | 'PUT' | 'DELETE'
  body?: BodyInit
  /** Send the bearer token (and mark the session as needing re-auth on a 401). */
  auth?: boolean
  timeoutMs?: number
  /** A JSON body is serialized and sent as application/json. */
  json?: unknown
}

/**
 * Typed request: bearer token, timeout, non-2xx mapped to ApiProblem, transport failures to
 * NetworkError / TimeoutError. 204 resolves to undefined. Returns the status for 200 vs 201.
 */
export async function apiRequestWithStatus<T>(
  path: string,
  { method = 'GET', body, auth = false, timeoutMs = 15_000, json }: RequestOptions = {},
): Promise<{ status: number; body: T }> {
  const headers = new Headers()
  if (auth) {
    const token = getToken()
    if (token) headers.set('Authorization', `Bearer ${token}`)
  }
  let payload = body
  if (json !== undefined) {
    headers.set('Content-Type', 'application/json')
    payload = JSON.stringify(json)
  }
  let response: Response
  try {
    response = await apiFetch(path, { method, headers, body: payload, signal: AbortSignal.timeout(timeoutMs) })
  } catch (error) {
    throw toTransportError(error) ?? error
  }
  if (!response.ok) {
    const problem = await toProblem(response)
    if (auth && problem.status === 401) markNeedsReauth()
    throw problem
  }
  if (response.status === 204) return { status: 204, body: undefined as T }
  return { status: response.status, body: (await response.json()) as T }
}

export async function apiRequest<T>(path: string, options?: RequestOptions): Promise<T> {
  return (await apiRequestWithStatus<T>(path, options)).body
}

/** Content-Disposition filename, ASCII only (the server sends validated parts); otherwise the fallback. */
export function filenameFrom(header: string | null, fallback: string): string {
  const match = header ? /filename="([A-Za-z0-9._-]{1,120})"/.exec(header) : null
  return match ? match[1] : fallback
}

/**
 * Binary download (selfie, spreadsheet) with the bearer token: never a plain <img src> or <a href> to the API.
 * Same auth, 401 handling and problem mapping as apiRequest.
 */
export async function apiBlob(
  path: string,
  { timeoutMs = 30_000, signal, fallbackName = 'download' }: { timeoutMs?: number; signal?: AbortSignal; fallbackName?: string } = {},
): Promise<{ blob: Blob; filename: string }> {
  const headers = new Headers()
  const token = getToken()
  if (token) headers.set('Authorization', `Bearer ${token}`)
  const timeout = AbortSignal.timeout(timeoutMs)
  let response: Response
  try {
    response = await apiFetch(path, { method: 'GET', headers, signal: signal ? AbortSignal.any([signal, timeout]) : timeout })
  } catch (error) {
    throw toTransportError(error) ?? error
  }
  if (!response.ok) {
    const problem = await toProblem(response)
    if (problem.status === 401) markNeedsReauth()
    throw problem
  }
  return { blob: await response.blob(), filename: filenameFrom(response.headers.get('Content-Disposition'), fallbackName) }
}
