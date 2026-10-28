import { describe, expect, test, vi } from 'vitest'
import { getDb } from '../storage/db'
import { enqueue, listAll, OUTBOX_EXPIRED_CODE } from '../storage/outbox'
import { baselineJpegBytes, loginResponse } from '../test/fixtures'
import {
  getSession,
  getToken,
  initSession,
  isTokenUsable,
  markNeedsReauth,
  reauthenticated,
  resetSessionForTests,
  SESSION_KEY,
  WIPE_PENDING_KEY,
  signIn,
  signOut,
} from './session'

const codes = { companyCode: 'DEMO', employeeCode: '1001' }
const PIN = '48207315'

async function boot() {
  resetSessionForTests()
  await initSession()
}

describe('storage layout', () => {
  test('the token is in sessionStorage and the identity in IndexedDB; the PIN is nowhere', async () => {
    await boot()
    await signIn(loginResponse(), codes)

    const stored = JSON.parse(sessionStorage.getItem(SESSION_KEY)!)
    expect(stored.token).toBe('token-1')
    expect(localStorage.getItem(SESSION_KEY)).toBeNull()

    const db = await getDb()
    const identity = await db.get('identity', 'current')
    expect(identity).toMatchObject({ employeeId: 7, companyCode: 'DEMO', employeeCode: '1001', timeZone: 'America/Costa_Rica' })
    expect(JSON.stringify(identity)).not.toContain('token-1')

    const everything = JSON.stringify([
      { ...sessionStorage },
      { ...localStorage },
      await db.getAll('identity'),
      await db.getAll('outbox'),
      await db.getAll('snapshot'),
    ])
    expect(everything).not.toContain(PIN)
  })

  test('the last company code is remembered in localStorage', async () => {
    await boot()
    await signIn(loginResponse(), codes)
    expect(localStorage.getItem('attendance.lastCompanyCode')).toBe('DEMO')
  })

  test('a reload restores the token and the identity', async () => {
    await boot()
    await signIn(loginResponse(), codes)
    resetSessionForTests()
    await initSession()
    expect(getSession()).toMatchObject({ ready: true, token: 'token-1' })
    expect(getSession().identity?.employeeCode).toBe('1001')
  })

  test('closing the app drops the token but keeps the identity (cold open)', async () => {
    await boot()
    await signIn(loginResponse(), codes)
    sessionStorage.clear()
    resetSessionForTests()
    await initSession()
    expect(getToken()).toBeNull()
    expect(getSession().identity?.name).toBe('María Pérez')
  })

  test('a token that belongs to another employee than the stored identity is dropped', async () => {
    await boot()
    await signIn(loginResponse(), codes)
    sessionStorage.setItem(SESSION_KEY, JSON.stringify({ token: 'x', expiresAt: 9e12, employeeId: 99, consentPending: false }))
    resetSessionForTests()
    await initSession()
    expect(getToken()).toBeNull()
    expect(sessionStorage.getItem(SESSION_KEY)).toBeNull()
  })
})

describe('token expiry', () => {
  test('isTokenUsable keeps a 60 s margin before expiresAt', async () => {
    await boot()
    await signIn(loginResponse({ expiresAt: '2026-10-07T14:00:00Z' }), codes)
    const expiry = Date.parse('2026-10-07T14:00:00Z')
    expect(isTokenUsable(expiry - 61_000)).toBe(true)
    expect(isTokenUsable(expiry - 59_000)).toBe(false)
    expect(isTokenUsable(expiry + 1)).toBe(false)
  })

  test('a 401 marks re-auth, clears the token and keeps the identity', async () => {
    await boot()
    await signIn(loginResponse(), codes)
    markNeedsReauth()
    expect(getSession()).toMatchObject({ needsReauth: true, token: null })
    expect(getSession().identity).not.toBeNull()
    expect(sessionStorage.getItem(SESSION_KEY)).toBeNull()
  })

  test('re-authenticating as the same employee stores the new token; another employee is refused', async () => {
    await boot()
    await signIn(loginResponse(), codes)
    markNeedsReauth()

    const other = loginResponse({ token: 'other', employee: { ...loginResponse().employee, id: 8 } })
    expect(reauthenticated(other)).toBe(false)
    expect(getSession().needsReauth).toBe(true)
    expect(getToken()).toBeNull()

    expect(reauthenticated(loginResponse({ token: 'token-2' }))).toBe(true)
    expect(getSession()).toMatchObject({ needsReauth: false, token: 'token-2' })
  })
})

describe('signOut', () => {
  test('wipes the token, identity and snapshot but keeps the unsent queue', async () => {
    await boot()
    await signIn(loginResponse(), codes)
    await enqueue({
      employeeId: 7,
      type: 'CLOCK_IN',
      clientUuid: 'u',
      deviceTime: '2026-10-07T13:58:00Z',
      photo: baselineJpegBytes().buffer as ArrayBuffer,
      photoType: 'image/jpeg',
      createdAt: '2026-10-07T13:58:00Z',
    })
    const db = await getDb()
    await db.put('snapshot', { employeeId: 7, today: {} as never, clockOffsetMs: 0, savedAt: 1 }, 'current')

    await signOut()

    expect(getSession()).toMatchObject({ identity: null, token: null })
    expect(sessionStorage.getItem(SESSION_KEY)).toBeNull()
    expect(await db.getAll('identity')).toEqual([])
    expect(await listAll()).toHaveLength(1)
    expect(await db.getAll('snapshot')).toEqual([])
    // The last company code is only a convenience and stays.
    expect(localStorage.getItem('attendance.lastCompanyCode')).toBe('DEMO')
  })
})

describe('storage that throws', () => {
  test('the session still works from memory when sessionStorage throws', async () => {
    await boot()
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new DOMException('denied', 'SecurityError')
    })
    await signIn(loginResponse(), codes)
    expect(getToken()).toBe('token-1')
  })

  test('a corrupt stored session is ignored', async () => {
    sessionStorage.setItem(SESSION_KEY, '{not json')
    await boot()
    expect(getToken()).toBeNull()
  })
})

describe('L-2: the local wipe is verified', () => {
  async function seedForWipe() {
    await boot()
    await signIn(loginResponse(), codes)
    const db = await getDb()
    await db.put('snapshot', { employeeId: 7, today: {} as never, clockOffsetMs: 0, savedAt: 1 }, 'current')
    return db
  }

  test('a failing step is retried and logged without personal data', async () => {
    const db = await seedForWipe()
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => undefined)
    vi.spyOn(IDBObjectStore.prototype, 'clear').mockImplementationOnce(() => {
      throw new Error('blocked')
    })

    expect(await signOut()).toBe(true)

    expect(await db.getAll('identity')).toEqual([])
    expect(await db.getAll('snapshot')).toEqual([])
    expect(localStorage.getItem(WIPE_PENDING_KEY)).toBeNull()
    expect(warn).toHaveBeenCalledTimes(1)
    const logged = JSON.stringify(warn.mock.calls)
    for (const secret of ['token-1', 'Pérez', '1001', 'DEMO']) expect(logged).not.toContain(secret)
  })

  test('a wipe that keeps failing is reported, flagged, repeated at the next start, and no identity is restored meanwhile', async () => {
    const db = await seedForWipe()
    vi.spyOn(console, 'warn').mockImplementation(() => undefined)
    const clear = vi.spyOn(IDBObjectStore.prototype, 'clear').mockImplementation(() => {
      throw new Error('blocked')
    })

    expect(await signOut()).toBe(false)
    expect(localStorage.getItem(WIPE_PENDING_KEY)).toBe('1')
    expect(getSession().identity).toBeNull()
    expect(await db.getAll('identity')).toHaveLength(1)

    clear.mockRestore()
    resetSessionForTests()
    await initSession()
    expect(getSession()).toMatchObject({ ready: true, identity: null, token: null })
    expect(await db.getAll('identity')).toEqual([])
    expect(await db.getAll('snapshot')).toEqual([])
    expect(localStorage.getItem(WIPE_PENDING_KEY)).toBeNull()
  })

  test('a start while the wipe still fails restores no identity', async () => {
    await seedForWipe()
    localStorage.setItem(WIPE_PENDING_KEY, '1')
    vi.spyOn(console, 'warn').mockImplementation(() => undefined)
    vi.spyOn(IDBObjectStore.prototype, 'clear').mockImplementation(() => {
      throw new Error('blocked')
    })
    resetSessionForTests()
    await initSession()
    expect(getSession()).toMatchObject({ ready: true, identity: null })
    expect(localStorage.getItem(WIPE_PENDING_KEY)).toBe('1')
  })

  test('signing in again after a failed wipe clears the flag so the new login is not wiped at the next start', async () => {
    await seedForWipe()
    localStorage.setItem(WIPE_PENDING_KEY, '1')
    await signIn(loginResponse({ token: 'token-2' }), codes)
    expect(localStorage.getItem(WIPE_PENDING_KEY)).toBeNull()
    resetSessionForTests()
    await initSession()
    expect(getSession().identity?.employeeId).toBe(7)
  })
})

describe('M-1: account switch', () => {
  test("signing in as another employee clears the previous employee's snapshot; the same employee keeps theirs", async () => {
    await boot()
    await signIn(loginResponse(), codes)
    const db = await getDb()
    await db.put('snapshot', { employeeId: 7, today: {} as never, clockOffsetMs: 0, savedAt: 1 }, 'current')

    await signIn(loginResponse({ token: 'again' }), codes)
    expect(await db.getAll('snapshot')).toHaveLength(1)

    await signIn(loginResponse({ token: 'token-B', employee: { ...loginResponse().employee, id: 8 } }), { companyCode: 'DEMO', employeeCode: '1002' })
    expect(await db.getAll('snapshot')).toEqual([])
    expect(getSession().identity?.employeeId).toBe(8)
  })
})

describe('L-4: maximum age at app open', () => {
  test('initSession expires stale unsent items (photo and coordinates dropped) before the session is ready', async () => {
    const db = await getDb()
    await enqueue({
      employeeId: 7,
      type: 'CLOCK_IN',
      clientUuid: 'old',
      deviceTime: '2026-01-01T00:00:00Z',
      lat: 9.9,
      lng: -84.1,
      accuracy: 5,
      photo: baselineJpegBytes().buffer as ArrayBuffer,
      photoType: 'image/jpeg',
      createdAt: new Date(Date.now() - 8 * 24 * 60 * 60 * 1000).toISOString(),
    })
    await boot()
    const [stored] = await db.getAll('outbox')
    expect(stored).toMatchObject({ state: 'failed', photo: null, failure: { code: OUTBOX_EXPIRED_CODE } })
    expect(stored).not.toHaveProperty('lat')
  })
})
