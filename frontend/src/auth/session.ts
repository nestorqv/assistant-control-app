import type { LoginResponse } from '../api/types'
import { clearIdentity, loadIdentity, saveIdentity } from '../storage/identity'
import type { Identity } from '../storage/db'
import { expireOld } from '../storage/outbox'
import { clearSnapshot, loadSnapshot } from '../storage/snapshot'

export type SessionState = {
  /** The identity has been read from IndexedDB; route guards wait for this. */
  ready: boolean
  identity: Identity | null
  /** In memory and in sessionStorage only. Null on a cold open or after a 401. */
  token: string | null
  expiresAt: number | null
  consentPending: boolean
  needsReauth: boolean
}

export const SESSION_KEY = 'attendance.session'
export const LAST_COMPANY_KEY = 'attendance.lastCompanyCode'
/** Present while a local wipe has not been confirmed; the next start repeats it and restores no identity. */
export const WIPE_PENDING_KEY = 'attendance.wipePending'
const WIPE_ATTEMPTS = 3
/** The token counts as expired this long before expiresAt, so the PIN is asked before a send, not after a 401. */
export const TOKEN_MARGIN_MS = 60_000

type StoredSession = { token: string; expiresAt: number; employeeId: number; consentPending: boolean }

const initialState: SessionState = {
  ready: false,
  identity: null,
  token: null,
  expiresAt: null,
  consentPending: false,
  needsReauth: false,
}

let state: SessionState = initialState
let initPromise: Promise<void> | null = null
const listeners = new Set<() => void>()

function set(next: Partial<SessionState>) {
  state = { ...state, ...next }
  listeners.forEach((listener) => listener())
}

// Storage can throw (private mode, disabled storage): the session then lives in memory only.
function readStoredSession(): StoredSession | null {
  try {
    const raw = sessionStorage.getItem(SESSION_KEY)
    if (!raw) return null
    const value = JSON.parse(raw) as Partial<StoredSession>
    if (typeof value.token !== 'string' || typeof value.expiresAt !== 'number' || typeof value.employeeId !== 'number') {
      return null
    }
    return { token: value.token, expiresAt: value.expiresAt, employeeId: value.employeeId, consentPending: value.consentPending === true }
  } catch {
    return null
  }
}

function writeStoredSession(value: StoredSession | null) {
  try {
    if (value) sessionStorage.setItem(SESSION_KEY, JSON.stringify(value))
    else sessionStorage.removeItem(SESSION_KEY)
  } catch {
    // Memory only.
  }
}

export function getSession(): SessionState {
  return state
}

export function subscribe(listener: () => void) {
  listeners.add(listener)
  return () => {
    listeners.delete(listener)
  }
}

export function getToken(): string | null {
  return state.token
}

export function isTokenUsable(nowMs: number): boolean {
  return state.token !== null && state.expiresAt !== null && nowMs < state.expiresAt - TOKEN_MARGIN_MS
}

/** Loads the identity (IndexedDB) and the token (sessionStorage). Safe to call more than once. */
export function initSession(): Promise<void> {
  initPromise ??= (async () => {
    let identity: Identity | null = null
    // Unsent items past their maximum age lose photo and coordinates before anything else happens.
    try {
      await expireOld()
    } catch (error) {
      logNonSensitive('expire-outbox', error)
    }
    // A wipe that did not finish last time is finished first; no identity is restored while it is pending.
    const wipeUnfinished = isWipePending()
    if (wipeUnfinished) await wipePersonalData()
    if (!wipeUnfinished || !isWipePending()) {
      try {
        identity = (await loadIdentity()) ?? null
      } catch {
        identity = null
      }
    }
    const stored = readStoredSession()
    // A token without a matching identity cannot tag outbox items, so it is dropped.
    if (stored && identity && stored.employeeId === identity.employeeId) {
      set({
        ready: true,
        identity,
        token: stored.token,
        expiresAt: stored.expiresAt,
        consentPending: stored.consentPending,
      })
    } else {
      if (stored) writeStoredSession(null)
      set({ ready: true, identity })
    }
  })()
  return initPromise
}

export function getLastCompanyCode(): string {
  try {
    return localStorage.getItem(LAST_COMPANY_KEY) ?? ''
  } catch {
    return ''
  }
}

function identityFrom(login: LoginResponse, codes: { companyCode: string; employeeCode: string }): Identity {
  return {
    employeeId: login.employee.id,
    name: login.employee.name,
    role: login.employee.role,
    companyName: login.company.name,
    timeZone: login.company.timeZone,
    companyCode: codes.companyCode,
    employeeCode: codes.employeeCode,
  }
}

/** Stores a fresh login. The PIN is never part of any of this. */
export async function signIn(login: LoginResponse, codes: { companyCode: string; employeeCode: string }) {
  const identity = identityFrom(login, codes)
  await clearSnapshotOfOtherEmployee(identity.employeeId)
  const expiresAt = Date.parse(login.expiresAt)
  writeStoredSession({
    token: login.token,
    expiresAt,
    employeeId: identity.employeeId,
    consentPending: login.employee.consentPending,
  })
  try {
    localStorage.setItem(LAST_COMPANY_KEY, codes.companyCode)
  } catch {
    // Convenience only.
  }
  try {
    await saveIdentity(identity)
    // The stored identity is overwritten and the snapshot is this person's own: nothing is left to wipe.
    setWipePending(false)
  } catch {
    // Without IndexedDB there is no offline queue, but the session itself still works.
  }
  set({
    ready: true,
    identity,
    token: login.token,
    expiresAt,
    consentPending: login.employee.consentPending,
    needsReauth: false,
  })
}

/** Re-auth with the PIN. Returns false (and stores nothing) when the login is for a different employee. */
export function reauthenticated(login: LoginResponse): boolean {
  if (!state.identity || state.identity.employeeId !== login.employee.id) return false
  const expiresAt = Date.parse(login.expiresAt)
  writeStoredSession({
    token: login.token,
    expiresAt,
    employeeId: login.employee.id,
    consentPending: login.employee.consentPending,
  })
  set({ token: login.token, expiresAt, consentPending: login.employee.consentPending, needsReauth: false })
  return true
}

/** A 401: the token is useless. Identity, queue and screen stay. */
export function markNeedsReauth() {
  if (!state.identity) return
  writeStoredSession(null)
  set({ token: null, expiresAt: null, needsReauth: true })
}

/** The token is gone or about to expire and something is waiting to be sent. */
export function requireReauth() {
  if (!state.identity || state.needsReauth) return
  markNeedsReauth()
}

/** The server answered 403 CONSENT_REQUIRED: send the user to the consent screen. */
export function requireConsent() {
  const stored = readStoredSession()
  if (stored) writeStoredSession({ ...stored, consentPending: true })
  set({ consentPending: true })
}

export function setConsentGiven() {
  const stored = readStoredSession()
  if (stored) writeStoredSession({ ...stored, consentPending: false })
  set({ consentPending: false })
}

function logNonSensitive(step: string, error: unknown) {
  // Step name and error class only: never ids, codes, tokens or stored values.
  console.warn('[session] local storage step failed', { step, error: error instanceof Error ? error.name : 'unknown' })
}

function isWipePending(): boolean {
  try {
    return localStorage.getItem(WIPE_PENDING_KEY) === '1'
  } catch {
    return false
  }
}

function setWipePending(pending: boolean) {
  try {
    if (pending) localStorage.setItem(WIPE_PENDING_KEY, '1')
    else localStorage.removeItem(WIPE_PENDING_KEY)
  } catch {
    // Without localStorage the retry still happens in this run.
  }
}

/** Runs one storage step up to WIPE_ATTEMPTS times; failures are logged without personal data. */
async function runWithRetry(step: string, run: () => Promise<void>): Promise<boolean> {
  for (let attempt = 1; attempt <= WIPE_ATTEMPTS; attempt++) {
    try {
      await run()
      return true
    } catch (error) {
      logNonSensitive(step, error)
    }
  }
  return false
}

/** Identity and snapshot only: unsent entries are never part of a wipe (they stay isolated per employee). */
async function wipePersonalData(): Promise<boolean> {
  setWipePending(true)
  const results = await Promise.all([runWithRetry('clear-identity', clearIdentity), runWithRetry('clear-snapshot', clearSnapshot)])
  const complete = results.every(Boolean)
  if (complete) setWipePending(false)
  return complete
}

/** Another person's last server result must not stay at rest after an account switch. */
async function clearSnapshotOfOtherEmployee(employeeId: number) {
  try {
    const snapshot = await loadSnapshot()
    if (snapshot && snapshot.employeeId !== employeeId) await runWithRetry('clear-snapshot', clearSnapshot)
  } catch (error) {
    logNonSensitive('load-snapshot', error)
  }
}

/**
 * Local wipe: token, identity and snapshot. The caller asked for confirmation first. Unsent entries stay on the
 * phone, tagged with their employee, and are sent when that employee signs in again (until the maximum age).
 * Returns false when storage could not be cleared; the wipe is then repeated at the next start.
 */
export async function signOut(): Promise<boolean> {
  writeStoredSession(null)
  set({ identity: null, token: null, expiresAt: null, consentPending: false, needsReauth: false })
  return wipePersonalData()
}

export function resetSessionForTests() {
  state = initialState
  initPromise = null
  listeners.clear()
}
