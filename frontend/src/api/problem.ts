/** A non-2xx answer. `code` is the backend ErrorCode; security-layer 401/403 have empty bodies, so it may be undefined. */
export class ApiProblem extends Error {
  readonly status: number
  readonly code?: string
  readonly retryAfterSeconds?: number

  constructor(status: number, code?: string, retryAfterSeconds?: number) {
    super(`HTTP ${status}${code ? ` ${code}` : ''}`)
    this.name = 'ApiProblem'
    this.status = status
    this.code = code
    this.retryAfterSeconds = retryAfterSeconds
  }
}

/** The request never got an answer (offline, DNS, refused, captive portal). */
export class NetworkError extends Error {
  constructor() {
    super('Network error')
    this.name = 'NetworkError'
  }
}

export class TimeoutError extends Error {
  constructor() {
    super('Timeout')
    this.name = 'TimeoutError'
  }
}

/** Delta-seconds only; an HTTP-date is ignored. */
export function parseRetryAfter(value: string | null): number | undefined {
  if (value === null || !/^\d+$/.test(value.trim())) return undefined
  return Number(value.trim())
}

export async function toProblem(response: Response): Promise<ApiProblem> {
  const retryAfter = parseRetryAfter(response.headers.get('Retry-After'))
  let code: string | undefined
  if ((response.headers.get('content-type') ?? '').includes('json')) {
    try {
      const body = (await response.json()) as { code?: unknown }
      if (typeof body.code === 'string') code = body.code
    } catch {
      // A broken body leaves the code unknown; the status still decides.
    }
  }
  return new ApiProblem(response.status, code, retryAfter)
}

/** Maps what fetch() throws to the two client-side kinds. Anything else is rethrown by the caller. */
export function toTransportError(error: unknown): NetworkError | TimeoutError | null {
  if (error instanceof DOMException && (error.name === 'TimeoutError' || error.name === 'AbortError')) {
    return new TimeoutError()
  }
  if (error instanceof TypeError) return new NetworkError()
  return null
}
