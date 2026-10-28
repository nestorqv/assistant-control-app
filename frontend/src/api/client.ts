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
  method?: 'GET' | 'POST'
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
