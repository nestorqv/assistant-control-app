import { ApiProblem, NetworkError, TimeoutError } from '../api/problem'
import type { TimeEntryResponse } from '../api/types'
import type { OutboxItem } from '../storage/db'

export type FlushOutcome = 'idle' | 'sent' | 'reauth' | 'consent' | 'retryAfter' | 'backoff' | 'failed'

export type FlushResult = {
  outcome: FlushOutcome
  /** items delivered in this run */
  sent: number
  /** when to try again, for retryAfter and backoff */
  retryInSeconds?: number
  /** true when the last attempt failed at network level (offline banner) */
  networkError?: boolean
}

export type FlushDeps = {
  employeeId: number
  /** Identity the token currently belongs to. An item is only sent when it matches (never under another person's token). */
  currentIdentity(): { employeeId: number; companyCode: string } | null
  outbox: {
    oldestPending(employeeId: number): Promise<OutboxItem | undefined>
    remove(seq: number): Promise<void>
    markFailed(seq: number, failure: { status: number; code?: string }): Promise<void>
    incrementAttempts(seq: number): Promise<void>
    /** Drops photo and coordinates of items past the maximum age. */
    expireOld(): Promise<number>
  }
  post(item: OutboxItem, sentAt: Date): Promise<{ status: 200 | 201; body: TimeEntryResponse }>
  online(): boolean
  /** false when the token is missing, expired (60 s margin) or a re-auth is already pending */
  tokenUsable(): boolean
  requireReauth(): void
  /** phone clock, or the server estimate when the phone clock is absurd */
  sentAt(): Date
  onSent?(item: OutboxItem, body: TimeEntryResponse, status: 200 | 201): void
  onFailed?(item: OutboxItem, failure: { status: number; code?: string }): void
}

export const BACKOFF_SECONDS = [5, 15, 30, 60, 300] as const
const DEFAULT_RETRY_AFTER_SECONDS = 30
const SERVER_BUSY_RETRY_SECONDS = 5

export function backoffSeconds(attempts: number): number {
  return BACKOFF_SECONDS[Math.min(Math.max(attempts, 1), BACKOFF_SECONDS.length) - 1]
}

const LOCK_NAME = 'attendance-outbox-flush'
let running = false
// Set by a call that arrives while a run is active; the run loops once more before it releases the guard.
let rerunWith: FlushDeps | null = null

async function withLock<T>(run: () => Promise<T>): Promise<T | undefined> {
  // Web Locks keep two tabs from flushing at once; without the API the in-memory flag is the only guard.
  if (typeof navigator !== 'undefined' && navigator.locks?.request) {
    return navigator.locks.request(LOCK_NAME, { ifAvailable: true }, async (lock) => (lock ? run() : undefined))
  }
  return run()
}

/**
 * Sends the oldest pending items strictly one at a time (FIFO) until the queue is empty or something
 * stops it. Safe to call from any trigger: a call while one runs does not start a second run, but it
 * asks the active run to look at the queue once more, so an item enqueued after the run read an empty
 * queue is not left waiting for the next trigger.
 */
export async function flushOnce(deps: FlushDeps): Promise<FlushResult> {
  if (running) {
    rerunWith = deps
    return { outcome: 'idle', sent: 0 }
  }
  running = true
  try {
    let current = deps
    let total = 0
    for (;;) {
      rerunWith = null
      const result = (await withLock(() => drain(current))) ?? { outcome: 'idle' as const, sent: 0 }
      total += result.sent
      const quiet = result.outcome === 'idle' || result.outcome === 'sent'
      if (rerunWith && quiet) {
        current = rerunWith
        continue
      }
      return { ...result, sent: total, outcome: total > 0 && result.outcome === 'idle' ? 'sent' : result.outcome }
    }
  } finally {
    running = false
    rerunWith = null
  }
}

async function drain(deps: FlushDeps): Promise<FlushResult> {
  let sent = 0
  await deps.outbox.expireOld()
  for (;;) {
    const item = await deps.outbox.oldestPending(deps.employeeId)
    if (!item) return { outcome: sent > 0 ? 'sent' : 'idle', sent }
    if (!deps.online()) return { outcome: sent > 0 ? 'sent' : 'idle', sent }
    if (!deps.tokenUsable()) {
      deps.requireReauth()
      return { outcome: 'reauth', sent }
    }
    if (!belongsToToken(deps, item)) return { outcome: 'idle', sent }
    try {
      const { status, body } = await deps.post(item, deps.sentAt())
      await deps.outbox.remove(item.seq)
      sent++
      deps.onSent?.(item, body, status)
    } catch (error) {
      return handleFailure(deps, item, error, sent)
    }
  }
}

/** Re-checked right before every send: the global token must belong to the same person as the item. */
function belongsToToken(deps: FlushDeps, item: OutboxItem): boolean {
  const identity = deps.currentIdentity()
  if (!identity) return false
  if (item.employeeId !== deps.employeeId || item.employeeId !== identity.employeeId) return false
  return item.companyCode === undefined || item.companyCode === identity.companyCode
}

async function handleFailure(deps: FlushDeps, item: OutboxItem, error: unknown, sent: number): Promise<FlushResult> {
  if (error instanceof ApiProblem) {
    if (error.status === 401) {
      deps.requireReauth()
      return { outcome: 'reauth', sent }
    }
    if (error.status === 403 && error.code === 'CONSENT_REQUIRED') return { outcome: 'consent', sent }
    if (error.status === 429 || (error.status === 503 && error.code === 'SERVER_BUSY')) {
      const fallback = error.status === 429 ? DEFAULT_RETRY_AFTER_SECONDS : SERVER_BUSY_RETRY_SECONDS
      // "Retry-After: 0" must not become a hot loop of immediate resends.
      return { outcome: 'retryAfter', sent, retryInSeconds: Math.max(1, error.retryAfterSeconds ?? fallback) }
    }
    if (error.status >= 500 || error.status === 408) return backoff(deps, item, sent, false)
    // Any other 4xx will never succeed by resending: record the failure and drop the photo now.
    const failure = { status: error.status, code: error.code }
    await deps.outbox.markFailed(item.seq, failure)
    deps.onFailed?.(item, failure)
    return { outcome: 'failed', sent }
  }
  return backoff(deps, item, sent, error instanceof NetworkError || error instanceof TimeoutError)
}

async function backoff(deps: FlushDeps, item: OutboxItem, sent: number, networkError: boolean): Promise<FlushResult> {
  await deps.outbox.incrementAttempts(item.seq)
  return { outcome: 'backoff', sent, retryInSeconds: backoffSeconds(item.attempts + 1), networkError }
}

export function resetFlushForTests() {
  running = false
  rerunWith = null
}
