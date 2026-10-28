import { act, screen, waitFor } from '@testing-library/react'
import { describe, expect, test, vi } from 'vitest'
import { getSession, signIn, signOut } from '../auth/session'
import { enqueue, listAll } from '../storage/outbox'
import { getDb } from '../storage/db'
import { loadSnapshot } from '../storage/snapshot'
import { baselineJpegBytes, entryResponse, jsonResponse, loginResponse, problemResponse, today } from '../test/fixtures'
import { seedSession, stubFetch } from '../test/appHarness'
import { renderWithI18n } from '../test/renderWithI18n'
import { useOutbox } from './useOutbox'

const codesA = { companyCode: 'DEMO', employeeCode: '1001' }
const codesB = { companyCode: 'DEMO', employeeCode: '1002' }
const loginB = (over = {}) => loginResponse({ token: 'token-B', employee: { ...loginResponse().employee, id: 8, name: 'Luis Mora' }, ...over })

/** Shows the in-memory outbox state that must never leak from one person to the next. */
function Probe() {
  const outbox = useOutbox()
  return (
    <ul>
      <li data-testid="delivered">{outbox.lastDelivered ? `${outbox.lastDelivered.response.serverTime}|${outbox.lastDelivered.response.distanceMeters}` : 'none'}</li>
      <li data-testid="results">{Object.keys(outbox.lastResults).join(',') || 'none'}</li>
      <li data-testid="pending">{outbox.pending.length}</li>
    </ul>
  )
}

async function queue(employeeId: number, clientUuid: string, over: Record<string, unknown> = {}) {
  await enqueue({
    employeeId,
    type: 'CLOCK_IN',
    clientUuid,
    deviceTime: new Date().toISOString(),
    lat: 9.9,
    lng: -84.1,
    accuracy: 10,
    photo: baselineJpegBytes().buffer as ArrayBuffer,
    photoType: 'image/jpeg',
    createdAt: new Date().toISOString(),
    ...over,
  })
}

const text = (id: string) => screen.getByTestId(id).textContent

describe('M-1: nothing the previous person produced survives a change of session', () => {
  async function deliverAsA() {
    await seedSession()
    await queue(7, 'cu-a')
    stubFetch(
      (url) => (url === '/api/time-entries/today' ? jsonResponse(today()) : undefined),
      (url, init) =>
        url === '/api/time-entries' && init?.method === 'POST'
          ? jsonResponse(entryResponse({ id: 501, serverTime: '2026-10-07T13:58:00Z', distanceMeters: 1112 }), 201)
          : undefined,
    )
    renderWithI18n(<Probe />, { providers: true })
    await waitFor(() => expect(text('delivered')).toBe('2026-10-07T13:58:00Z|1112'))
    expect(text('results')).toBe('CLOCK_IN')
  }

  test('logout then sign-in as another employee in the same tree shows nothing of the first one', async () => {
    await deliverAsA()

    await act(async () => {
      await signOut()
    })
    await waitFor(() => expect(text('delivered')).toBe('none'))
    expect(text('results')).toBe('none')

    await act(async () => {
      await signIn(loginB(), codesB)
    })
    expect(getSession().identity?.employeeId).toBe(8)
    expect(text('delivered')).toBe('none')
    expect(text('results')).toBe('none')
    expect(text('pending')).toBe('0')
  })

  test('switching directly from A to B (no logout) also resets the state', async () => {
    await deliverAsA()
    await act(async () => {
      await signIn(loginB(), codesB)
    })
    await waitFor(() => expect(text('delivered')).toBe('none'))
    expect(text('results')).toBe('none')
  })

  test('a POST still in flight when the person signs out is not shown to the next person', async () => {
    await seedSession()
    await queue(7, 'cu-slow')
    let release!: () => void
    const gate = new Promise<void>((resolve) => (release = resolve))
    const { mock } = stubFetch(
      (url) => (url === '/api/time-entries/today' ? jsonResponse(today()) : undefined),
      async (url, init) => {
        if (url !== '/api/time-entries' || init?.method !== 'POST') return undefined
        await gate
        return jsonResponse(entryResponse({ id: 502, distanceMeters: 1112 }), 201)
      },
    )
    renderWithI18n(<Probe />, { providers: true })
    await waitFor(() => expect(mock.mock.calls.some(([url]) => String(url) === '/api/time-entries')).toBe(true))

    await act(async () => {
      await signOut()
      await signIn(loginB(), codesB)
    })
    await act(async () => {
      release()
      await new Promise((resolve) => setTimeout(resolve, 50))
    })
    expect(text('delivered')).toBe('none')
    expect(text('results')).toBe('none')
  })

  test("the first employee's snapshot is cleared on an account switch and the delivered result is never saved for the second", async () => {
    await deliverAsA()
    const db = await getDb()
    await db.put(
      'snapshot',
      { employeeId: 7, today: today(), lastCompleted: { workDate: '2026-10-07', clockInAt: 'a', clockOutAt: 'b' }, clockOffsetMs: 0, savedAt: 1 },
      'current',
    )
    await act(async () => {
      await signIn(loginB(), codesB)
    })
    const snapshot = await loadSnapshot()
    expect(snapshot === undefined || (snapshot.employeeId === 8 && snapshot.lastCompleted === undefined)).toBe(true)
  })
})

describe('L-1: other employees keep their unsent entries', () => {
  test("signing out and signing in as someone else leaves the first employee's items untouched", async () => {
    await seedSession()
    await queue(7, 'cu-keep')
    vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(false)
    stubFetch(() => undefined)
    renderWithI18n(<Probe />, { providers: true })
    await waitFor(() => expect(text('pending')).toBe('1'))

    await act(async () => {
      await signOut()
      await signIn(loginB(), codesB)
    })
    await waitFor(() => expect(text('pending')).toBe('0'))
    const items = await listAll()
    expect(items).toHaveLength(1)
    expect(items[0]).toMatchObject({ employeeId: 7, clientUuid: 'cu-keep', state: 'pending' })
    expect(items[0].photo).not.toBeNull()
  })

  test('the first employee gets their items sent when they sign in again', async () => {
    await seedSession()
    await queue(7, 'cu-later')
    vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(false)
    const { mock } = stubFetch(
      (url) => (url === '/api/time-entries/today' ? jsonResponse(today()) : undefined),
      (url, init) => (url === '/api/time-entries' && init?.method === 'POST' ? jsonResponse(entryResponse({ id: 503 }), 201) : undefined),
    )
    renderWithI18n(<Probe />, { providers: true })
    await act(async () => {
      await signOut()
      await signIn(loginB(), codesB)
      await signOut()
    })
    vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(true)
    await act(async () => {
      await signIn(loginResponse({ token: 'token-A2' }), codesA)
    })
    await waitFor(() => expect(mock.mock.calls.some(([url]) => String(url) === '/api/time-entries')).toBe(true))
    await waitFor(async () => expect(await listAll()).toEqual([]))
  })
})

describe('L-2: a backoff timer of the previous session never sends under the next token', () => {
  test("A's item is not posted with B's token after the timer fires", async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
    await seedSession()
    await queue(7, 'cu-a-backoff')
    const auth: (string | null)[] = []
    stubFetch(
      (url) => (url === '/api/time-entries/today' ? jsonResponse(today()) : undefined),
      (url, init) => {
        if (url !== '/api/time-entries' || init?.method !== 'POST') return undefined
        auth.push(new Headers(init.headers).get('Authorization'))
        return problemResponse(503, 'INTERNAL_ERROR')
      },
    )
    renderWithI18n(<Probe />, { providers: true })
    await vi.waitFor(() => expect(auth).toEqual(['Bearer token-1']))
    await act(async () => {
      await new Promise((resolve) => setImmediate(resolve))
    })

    await act(async () => {
      await signOut()
      await signIn(loginB(), codesB)
    })
    await act(async () => {
      await vi.advanceTimersByTimeAsync(400_000)
    })
    expect(auth.filter((value) => value === 'Bearer token-B')).toEqual([])
  })
})
