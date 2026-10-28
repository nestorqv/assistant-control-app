import { beforeEach, describe, expect, test, vi } from 'vitest'
import { ApiProblem, NetworkError, TimeoutError } from '../api/problem'
import type { TimeEntryResponse } from '../api/types'
import * as outbox from '../storage/outbox'
import type { OutboxItem } from '../storage/db'
import { baselineJpegBytes, entryResponse } from '../test/fixtures'
import { BACKOFF_SECONDS, backoffSeconds, flushOnce, resetFlushForTests, type FlushDeps } from './flush'

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

const ok = (status: 200 | 201 = 201, over: Partial<TimeEntryResponse> = {}) =>
  async () => ({ status, body: entryResponse(over) })

beforeEach(() => {
  resetFlushForTests()
})

describe('delivery', () => {
  test('sends strictly one at a time in FIFO order', async () => {
    await outbox.enqueue(newItem({ type: 'CLOCK_IN' }))
    await outbox.enqueue(newItem({ type: 'CLOCK_OUT' }))
    const log: string[] = []
    let releaseFirst!: () => void
    const post: Post = async (item) => {
      log.push(`start ${item.type}`)
      if (item.type === 'CLOCK_IN') await new Promise<void>((resolve) => (releaseFirst = resolve))
      log.push(`end ${item.type}`)
      return { status: 201, body: entryResponse({ type: item.type }) }
    }

    const running = flushOnce(deps(post))
    await vi.waitFor(() => expect(log).toEqual(['start CLOCK_IN']))
    // The second request must not start while the first is in flight.
    await new Promise((resolve) => setTimeout(resolve, 30))
    expect(log).toEqual(['start CLOCK_IN'])
    releaseFirst()
    const result = await running

    expect(log).toEqual(['start CLOCK_IN', 'end CLOCK_IN', 'start CLOCK_OUT', 'end CLOCK_OUT'])
    expect(result).toMatchObject({ outcome: 'sent', sent: 2 })
    expect(await outbox.listAll()).toEqual([])
  })

  test.each([201, 200] as const)('%i deletes the item including the photo and reports it', async (status) => {
    await outbox.enqueue(newItem())
    const onSent = vi.fn()
    const result = await flushOnce(deps(ok(status, { replayed: status === 200 }), { onSent }))
    expect(result).toMatchObject({ outcome: 'sent', sent: 1 })
    expect(await outbox.listAll()).toEqual([])
    expect(onSent).toHaveBeenCalledWith(expect.objectContaining({ type: 'CLOCK_IN' }), expect.objectContaining({ replayed: status === 200 }), status)
  })

  test('an empty queue is idle', async () => {
    expect(await flushOnce(deps(ok()))).toMatchObject({ outcome: 'idle', sent: 0 })
  })

  test('nothing is sent while offline', async () => {
    await outbox.enqueue(newItem())
    const post = vi.fn(ok())
    const result = await flushOnce(deps(post, { online: () => false }))
    expect(result.outcome).toBe('idle')
    expect(post).not.toHaveBeenCalled()
    expect(await outbox.countPending(7)).toBe(1)
  })

  test("items of another employee are never sent", async () => {
    await outbox.enqueue(newItem({ employeeId: 8 }))
    const post = vi.fn(ok())
    expect((await flushOnce(deps(post))).outcome).toBe('idle')
    expect(post).not.toHaveBeenCalled()
    expect(await outbox.countPending(8)).toBe(1)
  })

  test('a second call while one is running does nothing (single flight)', async () => {
    await outbox.enqueue(newItem())
    let release!: () => void
    const post = vi.fn(async (): ReturnType<Post> => {
      await new Promise<void>((resolve) => (release = resolve))
      return { status: 201, body: entryResponse() }
    })
    const first = flushOnce(deps(post))
    await vi.waitFor(() => expect(post).toHaveBeenCalledTimes(1))
    const second = await flushOnce(deps(post))
    expect(second).toMatchObject({ outcome: 'idle', sent: 0 })
    release()
    await first
    expect(post).toHaveBeenCalledTimes(1)
  })
})

describe('session and consent', () => {
  test('an unusable token asks for re-auth before any request and keeps the item', async () => {
    await outbox.enqueue(newItem())
    const post = vi.fn(ok())
    const requireReauth = vi.fn()
    const result = await flushOnce(deps(post, { tokenUsable: () => false, requireReauth }))
    expect(result.outcome).toBe('reauth')
    expect(requireReauth).toHaveBeenCalled()
    expect(post).not.toHaveBeenCalled()
    expect(await outbox.countPending(7)).toBe(1)
  })

  test('a 401 stops, keeps the item unchanged and requests re-auth', async () => {
    const seq = await outbox.enqueue(newItem())
    const requireReauth = vi.fn()
    const result = await flushOnce(deps(async () => { throw new ApiProblem(401) }, { requireReauth }))
    expect(result.outcome).toBe('reauth')
    expect(requireReauth).toHaveBeenCalled()
    const [stored] = await outbox.listItems(7)
    expect(stored).toMatchObject({ seq, state: 'pending', attempts: 0 })
    expect(stored.photo).not.toBeNull()
  })

  test('403 CONSENT_REQUIRED keeps the item and returns "consent"', async () => {
    await outbox.enqueue(newItem())
    const result = await flushOnce(deps(async () => { throw new ApiProblem(403, 'CONSENT_REQUIRED') }))
    expect(result.outcome).toBe('consent')
    expect(await outbox.countPending(7)).toBe(1)
  })

  test('after a re-auth the same item is resent with the same clientUuid', async () => {
    await outbox.enqueue(newItem({ clientUuid: 'fixed-uuid' }))
    const seen: string[] = []
    await flushOnce(deps(async (item) => { seen.push(item.clientUuid); throw new ApiProblem(401) }))
    await flushOnce(deps(async (item) => { seen.push(item.clientUuid); return { status: 201, body: entryResponse() } }))
    expect(seen).toEqual(['fixed-uuid', 'fixed-uuid'])
  })
})

describe('final failures', () => {
  test('409 NOT_CLOCKED_IN marks the item failed, nulls the photo and stops the flush', async () => {
    await outbox.enqueue(newItem({ type: 'CLOCK_OUT' }))
    await outbox.enqueue(newItem({ type: 'CLOCK_IN' }))
    const post = vi.fn(async () => { throw new ApiProblem(409, 'NOT_CLOCKED_IN') })
    const onFailed = vi.fn()

    const result = await flushOnce(deps(post, { onFailed }))

    expect(result.outcome).toBe('failed')
    expect(post).toHaveBeenCalledTimes(1)
    expect(onFailed).toHaveBeenCalled()
    const items = await outbox.listItems(7)
    expect(items[0]).toMatchObject({ state: 'failed', photo: null, failure: { status: 409, code: 'NOT_CLOCKED_IN' } })
    expect(items[1].state).toBe('pending')
    // The second item waits until the failure is acknowledged.
    expect((await flushOnce(deps(ok()))).outcome).toBe('idle')
  })

  test('acknowledging resumes with the next item', async () => {
    const first = await outbox.enqueue(newItem({ type: 'CLOCK_OUT' }))
    await outbox.enqueue(newItem({ type: 'CLOCK_IN' }))
    await flushOnce(deps(async () => { throw new ApiProblem(409, 'NOT_CLOCKED_IN') }))
    await outbox.remove(first)
    const result = await flushOnce(deps(ok()))
    expect(result).toMatchObject({ outcome: 'sent', sent: 1 })
  })

  test.each([
    [400, 'PHOTO_REQUIRED'],
    [400, 'INVALID_PHOTO'],
    [413, 'PAYLOAD_TOO_LARGE'],
    [403, 'NO_BRANCH_ASSIGNED'],
    [409, 'ALREADY_CLOCKED_IN'],
    [409, 'SHIFT_ALREADY_COMPLETED'],
    [409, 'CLIENT_UUID_CONFLICT'],
  ])('%i %s is a final failure', async (status, code) => {
    await outbox.enqueue(newItem())
    const result = await flushOnce(deps(async () => { throw new ApiProblem(status, code) }))
    expect(result.outcome).toBe('failed')
  })
})

describe('retries', () => {
  test.each([
    [429, undefined, 30],
    [429, 120, 120],
    [503, undefined, 5],
    [503, 17, 17],
  ])('%i with Retry-After %s retries in %i s', async (status, retryAfter, expected) => {
    await outbox.enqueue(newItem())
    const code = status === 503 ? 'SERVER_BUSY' : 'TOO_MANY_ATTEMPTS'
    const result = await flushOnce(deps(async () => { throw new ApiProblem(status, code, retryAfter) }))
    expect(result).toMatchObject({ outcome: 'retryAfter', retryInSeconds: expected })
    expect(await outbox.countPending(7)).toBe(1)
  })

  test('network errors back off 5, 15, 30, 60, 300 s and count attempts', async () => {
    await outbox.enqueue(newItem())
    const waits: number[] = []
    for (let i = 0; i < 6; i++) {
      const result = await flushOnce(deps(async () => { throw new NetworkError() }))
      expect(result).toMatchObject({ outcome: 'backoff', networkError: true })
      waits.push(result.retryInSeconds!)
    }
    expect(waits).toEqual([5, 15, 30, 60, 300, 300])
    expect((await outbox.listItems(7))[0].attempts).toBe(6)
    expect([...BACKOFF_SECONDS]).toEqual([5, 15, 30, 60, 300])
    expect(backoffSeconds(1)).toBe(5)
  })

  test('a timeout and a 5xx are retried too, but only the network kinds raise the offline flag', async () => {
    await outbox.enqueue(newItem())
    const timeout = await flushOnce(deps(async () => { throw new TimeoutError() }))
    expect(timeout).toMatchObject({ outcome: 'backoff', networkError: true })
    const server = await flushOnce(deps(async () => { throw new ApiProblem(502) }))
    expect(server).toMatchObject({ outcome: 'backoff', networkError: false })
    const internal = await flushOnce(deps(async () => { throw new ApiProblem(500, 'INTERNAL_ERROR') }))
    expect(internal.outcome).toBe('backoff')
    expect(await outbox.countPending(7)).toBe(1)
  })

  test('deviceSentAt differs per attempt while clientUuid and deviceTime stay the same', async () => {
    await outbox.enqueue(newItem({ clientUuid: 'same', deviceTime: '2026-10-07T13:58:00.000Z' }))
    const seen: { uuid: string; deviceTime: string; sentAt: string }[] = []
    const record: Post = async (item, sentAt) => {
      seen.push({ uuid: item.clientUuid, deviceTime: item.deviceTime, sentAt: sentAt.toISOString() })
      throw new NetworkError()
    }
    let now = Date.parse('2026-10-07T14:00:00Z')
    const clock = () => new Date((now += 60_000))
    await flushOnce(deps(record, { sentAt: clock }))
    await flushOnce(deps(record, { sentAt: clock }))
    expect(seen[0].uuid).toBe(seen[1].uuid)
    expect(seen[0].deviceTime).toBe(seen[1].deviceTime)
    expect(seen[0].sentAt).not.toBe(seen[1].sentAt)
  })
})

describe('ordering across a partial run', () => {
  test('the first item is delivered and the failure of the second keeps only the second', async () => {
    await outbox.enqueue(newItem({ type: 'CLOCK_IN' }))
    await outbox.enqueue(newItem({ type: 'CLOCK_OUT' }))
    let calls = 0
    const post: Post = async () => {
      if (++calls === 2) throw new NetworkError()
      return { status: 201, body: entryResponse() }
    }
    const result = await flushOnce(deps(post))
    expect(result).toMatchObject({ outcome: 'backoff', sent: 1 })
    const left: OutboxItem[] = await outbox.listPending(7)
    expect(left.map((i) => i.type)).toEqual(['CLOCK_OUT'])
  })
})

describe('L-2: the item must belong to the token at send time', () => {
  test('nothing is sent when the token belongs to another employee than the flush', async () => {
    await outbox.enqueue(newItem())
    const post = vi.fn(ok())
    const result = await flushOnce(deps(post, { currentIdentity: () => ({ employeeId: 8, companyCode: 'ACME' }) }))
    expect(post).not.toHaveBeenCalled()
    expect(result).toMatchObject({ outcome: 'idle', sent: 0 })
    expect(await outbox.listAll()).toHaveLength(1)
  })

  test('nothing is sent when there is no identity at all', async () => {
    await outbox.enqueue(newItem())
    const post = vi.fn(ok())
    await flushOnce(deps(post, { currentIdentity: () => null }))
    expect(post).not.toHaveBeenCalled()
  })

  test('an item captured under another company code is never sent', async () => {
    await outbox.enqueue(newItem({ companyCode: 'OTHER' }))
    const post = vi.fn(ok())
    await flushOnce(deps(post))
    expect(post).not.toHaveBeenCalled()
    expect(await outbox.listAll()).toHaveLength(1)
  })

  test('an item with the same company and employee is sent; an older item without a company code too', async () => {
    await outbox.enqueue(newItem({ companyCode: 'ACME' }))
    await outbox.enqueue(newItem())
    const post = vi.fn(ok())
    const result = await flushOnce(deps(post))
    expect(post).toHaveBeenCalledTimes(2)
    expect(result).toMatchObject({ outcome: 'sent', sent: 2 })
  })

  test('the identity is re-read before every send, so a sign-out in the middle stops the rest', async () => {
    await outbox.enqueue(newItem({ type: 'CLOCK_IN' }))
    await outbox.enqueue(newItem({ type: 'CLOCK_OUT' }))
    let signedIn = true
    const post = vi.fn(async (item: OutboxItem) => {
      signedIn = false
      return { status: 201 as const, body: entryResponse({ type: item.type }) }
    })
    await flushOnce(deps(post, { currentIdentity: () => (signedIn ? { employeeId: 7, companyCode: 'ACME' } : null) }))
    expect(post).toHaveBeenCalledTimes(1)
  })
})

describe('the flush guard does not lose a call that arrives while the run is finishing', () => {
  test('an item enqueued after the run read an empty queue is sent by the same run', async () => {
    const post = vi.fn(ok())
    let late: Promise<unknown> | undefined
    let injected = false
    const racing = {
      ...outbox,
      oldestPending: async (employeeId: number) => {
        const found = await outbox.oldestPending(employeeId)
        if (!injected) {
          injected = true
          // The run has just seen an empty queue; a new entry and a second trigger arrive before it releases the guard.
          await outbox.enqueue(newItem())
          late = flushOnce(deps(post))
        }
        return found
      },
    }
    const first = await flushOnce(deps(post, { outbox: racing }))
    expect(await late).toMatchObject({ outcome: 'idle', sent: 0 })
    expect(post).toHaveBeenCalledTimes(1)
    expect(first).toMatchObject({ outcome: 'sent', sent: 1 })
    expect(await outbox.listAll()).toEqual([])
  })

  test('a rerun is not requested when the run stopped for a reason (no hammering after a backoff)', async () => {
    await outbox.enqueue(newItem())
    const post = vi.fn(async () => {
      throw new NetworkError()
    })
    let late: Promise<unknown> | undefined
    const result = await flushOnce(
      deps(post, {
        sentAt: () => {
          late ??= flushOnce(deps(post))
          return new Date()
        },
      }),
    )
    await late
    expect(result.outcome).toBe('backoff')
    expect(post).toHaveBeenCalledTimes(1)
  })
})

describe('L-4: expired items are never sent', () => {
  test('a pending item older than the maximum age is failed, loses photo and coordinates, and is not posted', async () => {
    const old = new Date(Date.now() - outbox.OUTBOX_MAX_AGE_MS - 60_000).toISOString()
    await outbox.enqueue(newItem({ createdAt: old, lat: 9.9, lng: -84.1, accuracy: 8 }))
    const post = vi.fn(ok())
    await flushOnce(deps(post))
    expect(post).not.toHaveBeenCalled()
    const [stored] = await outbox.listAll()
    expect(stored).toMatchObject({ state: 'failed', photo: null, failure: { code: outbox.OUTBOX_EXPIRED_CODE } })
    expect(stored).not.toHaveProperty('lat')
    expect(stored).not.toHaveProperty('lng')
    expect(stored).not.toHaveProperty('accuracy')
  })

  test('a failed final answer also drops the coordinates', async () => {
    await outbox.enqueue(newItem({ lat: 9.9, lng: -84.1, accuracy: 8 }))
    const post = vi.fn(async () => {
      throw new ApiProblem(409, 'ALREADY_CLOCKED_IN')
    })
    await flushOnce(deps(post))
    const [stored] = await outbox.listAll()
    expect(stored).toMatchObject({ state: 'failed', photo: null })
    expect(stored).not.toHaveProperty('lat')
    expect(stored).not.toHaveProperty('lng')
    expect(stored).not.toHaveProperty('accuracy')
  })
})
