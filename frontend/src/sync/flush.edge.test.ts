import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { ApiProblem, NetworkError } from '../api/problem'
import type { OutboxItem } from '../storage/db'
import * as outbox from '../storage/outbox'
import { baselineJpegBytes, entryResponse } from '../test/fixtures'
import { flushOnce, resetFlushForTests, type FlushDeps } from './flush'

// Extra edge cases for the delivery rules (QA pass on Stage 3).

function newItem(over: Partial<outbox.NewOutboxItem> = {}): outbox.NewOutboxItem {
  return {
    employeeId: 7,
    type: 'CLOCK_IN',
    clientUuid: crypto.randomUUID(),
    deviceTime: '2026-10-07T13:58:00.000Z',
    photo: baselineJpegBytes().buffer as ArrayBuffer,
    photoType: 'image/jpeg',
    createdAt: new Date().toISOString(),
    ...over,
  }
}

type Post = FlushDeps['post']

function deps(post: Post, over: Partial<FlushDeps> = {}): FlushDeps {
  return {
    employeeId: 7,
    currentIdentity: () => ({ employeeId: 7, companyCode: 'ACME' }),
    outbox,
    post,
    online: () => true,
    tokenUsable: () => true,
    requireReauth: vi.fn(),
    sentAt: () => new Date(),
    ...over,
  }
}

const fail = (error: unknown): Post => async () => {
  throw error
}

beforeEach(() => {
  resetFlushForTests()
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('retry delay bounds', () => {
  test.each([429, 503])('%i with "Retry-After: 0" never schedules an immediate resend', async (status) => {
    await outbox.enqueue(newItem())
    const result = await flushOnce(deps(fail(new ApiProblem(status, status === 503 ? 'SERVER_BUSY' : 'TOO_MANY_ATTEMPTS', 0))))
    expect(result).toMatchObject({ outcome: 'retryAfter' })
    expect(result.retryInSeconds).toBeGreaterThanOrEqual(1)
  })
})

describe('retryable versus terminal', () => {
  test.each([
    [500, 'INTERNAL_ERROR'],
    [502, undefined],
    [503, undefined],
    [503, 'DATABASE_DOWN'],
    [504, undefined],
    [408, undefined],
  ])('%i %s is retried with backoff, keeps the item and is not an offline signal', async (status, code) => {
    await outbox.enqueue(newItem())
    const result = await flushOnce(deps(fail(new ApiProblem(status, code))))
    expect(result).toMatchObject({ outcome: 'backoff', networkError: false })
    const [item] = await outbox.listPending(7)
    expect(item).toMatchObject({ attempts: 1, state: 'pending' })
    expect(item.photo).not.toBeNull()
  })

  test.each([
    [400, undefined],
    [403, undefined],
    [403, 'FORBIDDEN'],
    [404, undefined],
    [405, undefined],
    [413, undefined],
    [415, undefined],
    [422, undefined],
  ])('%i %s is terminal: marked failed and the photo is dropped', async (status, code) => {
    await outbox.enqueue(newItem())
    const result = await flushOnce(deps(fail(new ApiProblem(status, code))))
    expect(result.outcome).toBe('failed')
    const [item] = await outbox.listItems(7)
    expect(item).toMatchObject({ state: 'failed', photo: null, failure: { status } })
  })

  test('a 200 with an unreadable body (captive portal) is retried, never lost', async () => {
    await outbox.enqueue(newItem())
    const result = await flushOnce(deps(fail(new SyntaxError('Unexpected token <'))))
    expect(result).toMatchObject({ outcome: 'backoff', networkError: false })
    expect(await outbox.countPending(7)).toBe(1)
  })
})

describe('ordering and loss under failure', () => {
  test('a network error on the CLOCK_IN never lets the CLOCK_OUT overtake it', async () => {
    await outbox.enqueue(newItem({ type: 'CLOCK_IN' }))
    await outbox.enqueue(newItem({ type: 'CLOCK_OUT' }))
    const sent: string[] = []
    const post: Post = async (item) => {
      sent.push(item.type)
      throw new NetworkError()
    }
    await flushOnce(deps(post))
    await flushOnce(deps(post))
    expect(sent).toEqual(['CLOCK_IN', 'CLOCK_IN'])
    expect((await outbox.listPending(7)).map((i) => i.type)).toEqual(['CLOCK_IN', 'CLOCK_OUT'])
  })

  test('a 401 on the second item keeps it, counts the first as delivered and asks for re-auth once', async () => {
    await outbox.enqueue(newItem({ type: 'CLOCK_IN' }))
    const second = newItem({ type: 'CLOCK_OUT' })
    await outbox.enqueue(second)
    const requireReauth = vi.fn()
    const seen: OutboxItem[] = []
    const post: Post = async (item) => {
      seen.push(item)
      if (item.type === 'CLOCK_OUT') throw new ApiProblem(401)
      return { status: 201, body: entryResponse() }
    }
    const result = await flushOnce(deps(post, { requireReauth }))
    expect(result).toMatchObject({ outcome: 'reauth', sent: 1 })
    expect(requireReauth).toHaveBeenCalledTimes(1)
    const left = await outbox.listPending(7)
    expect(left).toHaveLength(1)
    expect(left[0]).toMatchObject({ type: 'CLOCK_OUT', clientUuid: second.clientUuid, attempts: 0 })
  })

  test('replaying after a lost response sends the same clientUuid, and a 200 replay removes the item without a duplicate', async () => {
    const item = newItem()
    await outbox.enqueue(item)
    const uuids: string[] = []
    const lostResponse: Post = async (queued) => {
      uuids.push(queued.clientUuid)
      throw new NetworkError() // the server stored it, the answer never arrived
    }
    await flushOnce(deps(lostResponse))
    const replay: Post = async (queued) => {
      uuids.push(queued.clientUuid)
      return { status: 200, body: entryResponse({ replayed: true }) }
    }
    const result = await flushOnce(deps(replay))
    expect(result).toMatchObject({ outcome: 'sent', sent: 1 })
    expect(uuids).toEqual([item.clientUuid, item.clientUuid])
    expect(await outbox.listAll()).toEqual([])
  })

  test('the retry timer value follows the attempts of the item, not of the run', async () => {
    await outbox.enqueue(newItem())
    const seconds: (number | undefined)[] = []
    for (let i = 0; i < 6; i++) seconds.push((await flushOnce(deps(fail(new NetworkError())))).retryInSeconds)
    expect(seconds).toEqual([5, 15, 30, 60, 300, 300])
  })
})

describe('cross-tab lock', () => {
  test('when another tab holds the Web Lock nothing is sent', async () => {
    await outbox.enqueue(newItem())
    vi.stubGlobal('navigator', {
      ...navigator,
      locks: { request: async (_name: string, _opts: unknown, cb: (lock: null) => Promise<unknown>) => cb(null) },
    })
    const post = vi.fn()
    const result = await flushOnce(deps(post))
    expect(result).toMatchObject({ outcome: 'idle', sent: 0 })
    expect(post).not.toHaveBeenCalled()
    expect(await outbox.countPending(7)).toBe(1)
  })

  test('the in-process guard is released after a failure, so the next trigger can flush', async () => {
    await outbox.enqueue(newItem())
    await flushOnce(deps(fail(new NetworkError())))
    const result = await flushOnce(deps(async () => ({ status: 201, body: entryResponse() })))
    expect(result).toMatchObject({ outcome: 'sent', sent: 1 })
  })

  test('an item enqueued while a flush is sending is picked up by the same run', async () => {
    await outbox.enqueue(newItem({ type: 'CLOCK_IN' }))
    const types: string[] = []
    const post: Post = async (item) => {
      types.push(item.type)
      if (item.type === 'CLOCK_IN') await outbox.enqueue(newItem({ type: 'CLOCK_OUT' }))
      return { status: 201, body: entryResponse({ type: item.type }) }
    }
    const result = await flushOnce(deps(post))
    expect(types).toEqual(['CLOCK_IN', 'CLOCK_OUT'])
    expect(result.sent).toBe(2)
  })
})
