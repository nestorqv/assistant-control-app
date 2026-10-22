import { getLanguage } from '../i18n/language'

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
