import { describe, expect, test, vi } from 'vitest'
import { baselineJpegBytes } from '../test/fixtures'
import { getDb } from './db'
import {
  clearAll,
  expireOld,
  OUTBOX_EXPIRED_CODE,
  OUTBOX_MAX_AGE_MS,
  countPending,
  enqueue,
  incrementAttempts,
  listAll,
  listItems,
  listPending,
  markFailed,
  MAX_PENDING,
  MIN_FREE_BYTES,
  oldestPending,
  OutboxFullError,
  remove,
  StorageFullError,
  type NewOutboxItem,
} from './outbox'

function item(over: Partial<NewOutboxItem> = {}): NewOutboxItem {
  return {
    employeeId: 7,
    type: 'CLOCK_IN',
    clientUuid: crypto.randomUUID(),
    deviceTime: '2026-10-07T13:58:00.000Z',
    lat: 9.9,
    lng: -84.1,
    accuracy: 12,
    photo: baselineJpegBytes().buffer as ArrayBuffer,
    photoType: 'image/jpeg',
    createdAt: '2026-10-07T13:58:00.000Z',
    ...over,
  }
}

describe('ordering and filtering', () => {
  test('items come back oldest first (FIFO by seq)', async () => {
    const a = await enqueue(item({ type: 'CLOCK_IN' }))
    const b = await enqueue(item({ type: 'CLOCK_OUT' }))
    expect(b).toBeGreaterThan(a)
    expect((await listPending(7)).map((i) => i.seq)).toEqual([a, b])
    expect((await oldestPending(7))?.seq).toBe(a)
    await remove(a)
    expect((await oldestPending(7))?.seq).toBe(b)
  })

  test('the per-employee filter never returns another employee', async () => {
    await enqueue(item({ employeeId: 7 }))
    await enqueue(item({ employeeId: 8 }))
    expect(await countPending(7)).toBe(1)
    expect((await listItems(8))[0].employeeId).toBe(8)
    expect(await listAll()).toHaveLength(2)
  })

  test('a failed item blocks oldestPending until it is removed', async () => {
    const first = await enqueue(item())
    await enqueue(item({ type: 'CLOCK_OUT' }))
    await markFailed(first, { status: 409, code: 'ALREADY_CLOCKED_IN' })
    expect(await oldestPending(7)).toBeUndefined()
    await remove(first)
    expect((await oldestPending(7))?.type).toBe('CLOCK_OUT')
  })
})

describe('limits', () => {
  test('the 9th pending item is refused', async () => {
    for (let i = 0; i < MAX_PENDING; i++) await enqueue(item())
    await expect(enqueue(item())).rejects.toBeInstanceOf(OutboxFullError)
    expect(await listAll()).toHaveLength(MAX_PENDING)
  })

  test('a storage estimate under 5 MB free refuses the item', async () => {
    vi.stubGlobal('navigator', { ...navigator, storage: { estimate: async () => ({ quota: 100 * 1024 * 1024, usage: 100 * 1024 * 1024 - MIN_FREE_BYTES + 1 }) } })
    await expect(enqueue(item())).rejects.toBeInstanceOf(StorageFullError)
    expect(await listAll()).toEqual([])
  })

  test('enough free space is accepted, and persist() is requested once', async () => {
    const persist = vi.fn().mockResolvedValue(true)
    vi.stubGlobal('navigator', { ...navigator, storage: { estimate: async () => ({ quota: 1e9, usage: 0 }), persist } })
    await enqueue(item())
    await enqueue(item())
    expect(persist).toHaveBeenCalledTimes(1)
  })

  test('a QuotaExceededError while writing maps to StorageFullError', async () => {
    const db = await getDb()
    vi.spyOn(db, 'add').mockRejectedValue(new DOMException('full', 'QuotaExceededError'))
    await expect(enqueue(item())).rejects.toBeInstanceOf(StorageFullError)
  })
})

describe('content', () => {
  test('the photo round-trips as an ArrayBuffer with identical bytes', async () => {
    await enqueue(item())
    const [stored] = await listPending(7)
    // Checked by tag: jsdom and the structured clone of fake-indexeddb use different realms.
    expect(Object.prototype.toString.call(stored.photo)).toBe('[object ArrayBuffer]')
    expect(new Uint8Array(stored.photo!)).toEqual(baselineJpegBytes())
    expect(stored).toMatchObject({ state: 'pending', attempts: 0, lat: 9.9, photoType: 'image/jpeg' })
  })

  test('markFailed removes the photo bytes at once and records the failure', async () => {
    const seq = await enqueue(item())
    await markFailed(seq, { status: 409, code: 'NOT_CLOCKED_IN' })
    const [stored] = await listItems(7)
    expect(stored.photo).toBeNull()
    expect(stored).toMatchObject({ state: 'failed', failure: { status: 409, code: 'NOT_CLOCKED_IN' } })
  })

  test('incrementAttempts counts retries', async () => {
    const seq = await enqueue(item())
    await incrementAttempts(seq)
    await incrementAttempts(seq)
    expect((await listItems(7))[0].attempts).toBe(2)
  })

  test('clearAll empties the queue', async () => {
    await enqueue(item())
    await enqueue(item())
    await clearAll()
    expect(await listAll()).toEqual([])
  })
})

describe('privacy: coordinates and age', () => {
  test('markFailed drops the photo and the coordinates', async () => {
    const seq = await enqueue(item())
    await markFailed(seq, { status: 409, code: 'ALREADY_CLOCKED_IN' })
    const [stored] = await listAll()
    expect(stored).toMatchObject({ state: 'failed', photo: null, failure: { status: 409 } })
    expect(stored).not.toHaveProperty('lat')
    expect(stored).not.toHaveProperty('lng')
    expect(stored).not.toHaveProperty('accuracy')
  })

  test('the maximum age is 7 days', () => {
    expect(OUTBOX_MAX_AGE_MS).toBe(7 * 24 * 60 * 60 * 1000)
  })

  test('expireOld fails items older than the maximum age (any employee), dropping photo and coordinates', async () => {
    const now = Date.parse('2026-10-27T12:00:00Z')
    await enqueue(item({ employeeId: 7, createdAt: new Date(now - OUTBOX_MAX_AGE_MS - 1).toISOString() }))
    await enqueue(item({ employeeId: 8, createdAt: new Date(now - OUTBOX_MAX_AGE_MS - 5 * 60_000).toISOString() }))
    expect(await expireOld(now)).toBe(2)
    for (const stored of await listAll()) {
      expect(stored).toMatchObject({ state: 'failed', photo: null, failure: { status: 0, code: OUTBOX_EXPIRED_CODE } })
      expect(stored).not.toHaveProperty('lat')
      expect(stored).not.toHaveProperty('lng')
      expect(stored).not.toHaveProperty('accuracy')
    }
  })

  test('an item exactly at the limit, a newer one and an already failed one are left alone', async () => {
    const now = Date.parse('2026-10-27T12:00:00Z')
    await enqueue(item({ createdAt: new Date(now - OUTBOX_MAX_AGE_MS).toISOString() }))
    await enqueue(item({ createdAt: new Date(now - 60_000).toISOString() }))
    const failedSeq = await enqueue(item({ createdAt: new Date(now - OUTBOX_MAX_AGE_MS * 2).toISOString() }))
    await markFailed(failedSeq, { status: 400, code: 'INVALID_PHOTO' })
    expect(await expireOld(now)).toBe(0)
    const all = await listAll()
    expect(all.map((i) => i.state)).toEqual(['pending', 'pending', 'failed'])
    expect(all[2].failure?.code).toBe('INVALID_PHOTO')
  })
})
