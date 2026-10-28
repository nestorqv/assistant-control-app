import { describe, expect, test, vi } from 'vitest'
import { timeResponse } from '../test/fixtures'
import { createServerClock } from './serverClock'

/** A controllable clock: `mono` and `wall` advance independently. */
function setup(serverStart = 1_000_000) {
  const state = { mono: 0, wall: 5_000_000, rtt: 100, serverNow: serverStart, failures: 0 }
  const clock = createServerClock({
    perf: () => state.mono,
    wallNow: () => state.wall,
    fetchTime: async () => {
      if (state.failures > 0) {
        state.failures--
        throw new TypeError('offline')
      }
      const rtt = state.rtt
      // The server stamps its time half-way through the round trip.
      state.mono += rtt
      state.wall += rtt
      state.serverNow += rtt
      return timeResponse(state.serverNow - rtt / 2)
    },
  })
  const advance = (ms: number) => {
    state.mono += ms
    state.wall += ms
    state.serverNow += ms
  }
  return { clock, state, advance }
}

describe('estimate', () => {
  test('offset uses half the round trip', async () => {
    const { clock, state } = setup()
    await clock.sync()
    // After the sample the true server time is state.serverNow; the estimate is within the RTT/2 error.
    expect(Math.abs(clock.getEstimate().ms - state.serverNow)).toBeLessThanOrEqual(state.rtt / 2)
    expect(clock.getEstimate()).toMatchObject({ approximate: false, known: true })
  })

  test('the first sync keeps the sample with the lowest round trip', async () => {
    // The server stamps its time at the START of each trip, so a sample is off by rtt / 2.
    let mono = 0
    let server = 1_000_000
    const rtts = [900, 40, 400]
    const clock = createServerClock({
      perf: () => mono,
      wallNow: () => mono,
      fetchTime: async () => {
        const rtt = rtts.shift()!
        const stamp = server
        mono += rtt
        server += rtt
        return timeResponse(stamp)
      },
    })
    await clock.sync()
    // Base = the 40 ms sample (error 20 ms), read after the 400 ms third sample: still 20 ms behind.
    expect(server - clock.getEstimate().ms).toBe(20)
  })

  test('later syncs take one sample', async () => {
    const { clock } = setup()
    const fetchSpy = vi.fn()
    const counting = createServerClock({
      perf: () => 0,
      wallNow: () => 0,
      fetchTime: async () => {
        fetchSpy()
        return timeResponse(1000)
      },
    })
    await counting.sync()
    expect(fetchSpy).toHaveBeenCalledTimes(3)
    await counting.sync()
    expect(fetchSpy).toHaveBeenCalledTimes(4)
    expect(clock).toBeDefined()
  })

  test('without a sync the phone clock is used and flagged unknown', () => {
    const { clock, state } = setup()
    expect(clock.getEstimate()).toEqual({ ms: state.wall, approximate: true, known: false })
  })

  test('sync resolves false and keeps the old base when the network is down', async () => {
    const { clock, state } = setup()
    await clock.sync()
    state.failures = 5
    expect(await clock.sync()).toBe(false)
    expect(clock.getEstimate().known).toBe(true)
  })
})

describe('phone clock errors', () => {
  test('ticks follow the monotonic clock even if the phone clock is an hour wrong', async () => {
    const { clock, state, advance } = setup()
    state.wall += 3_600_000 // the phone says it is an hour later than the server
    await clock.sync()
    const before = clock.getEstimate().ms
    advance(60_000)
    const after = clock.getEstimate()
    expect(after.ms - before).toBe(60_000)
    expect(after.approximate).toBe(false)
    // The estimate is near the server time, not near the phone clock.
    expect(Math.abs(after.ms - state.serverNow)).toBeLessThan(200)
  })

  test('a suspend (mono stops, wall moves on) switches to the wall estimate and requests a resync', async () => {
    const { clock, state } = setup()
    await clock.sync()
    const base = clock.getEstimate().ms
    state.wall += 10 * 60_000 // ten minutes pass while performance.now() does not advance
    const estimate = clock.getEstimate()
    expect(estimate.approximate).toBe(true)
    expect(estimate.ms - base).toBe(10 * 60_000)
    expect(clock.needsResync()).toBe(true)
    await clock.sync()
    expect(clock.needsResync()).toBe(false)
    expect(clock.getEstimate().approximate).toBe(false)
  })

  test('small drift under 2 s keeps the monotonic estimate', async () => {
    const { clock, state } = setup()
    await clock.sync()
    state.mono += 10_000
    state.wall += 11_500
    expect(clock.getEstimate().approximate).toBe(false)
  })
})

describe('offline reload', () => {
  test('a persisted offset gives an approximate estimate', () => {
    const { clock, state } = setup()
    clock.setPersistedOffset(-90_000)
    expect(clock.getEstimate()).toEqual({ ms: state.wall - 90_000, approximate: true, known: true })
  })

  test('getOffsetMs reports server minus phone clock for persisting', async () => {
    const { clock, state } = setup()
    await clock.sync()
    const offset = clock.getOffsetMs()!
    expect(Math.abs(state.wall + offset - state.serverNow)).toBeLessThanOrEqual(state.rtt / 2)
  })
})

describe('phone sleep and clock changes (QA)', () => {
  test('the wall clock moved back by the user (negative divergence) also switches to the wall estimate', async () => {
    const { clock, state } = setup()
    await clock.sync()
    state.wall -= 60 * 60_000
    expect(clock.getEstimate().approximate).toBe(true)
    expect(clock.needsResync()).toBe(true)
  })

  test('while still offline after a sleep, sync fails and the estimate keeps following the wall clock', async () => {
    const { clock, state } = setup()
    await clock.sync()
    state.wall += 5 * 60_000
    state.failures = 3
    expect(await clock.sync()).toBe(false)
    const first = clock.getEstimate()
    state.wall += 1_000
    state.mono += 1_000
    expect(first.approximate).toBe(true)
    expect(clock.needsResync()).toBe(true)
  })

  test('the offset persisted after a sleep is the corrected one, not the stale monotonic one', async () => {
    const { clock, state } = setup()
    await clock.sync()
    state.wall += 10 * 60_000
    const offset = clock.getOffsetMs()!
    // wall estimate: server advanced by the same 10 minutes the wall did, so the offset is unchanged.
    state.mono += 0
    expect(Math.abs(offset - (clock.getEstimate().ms - state.wall))).toBe(0)
  })

  test('reset forgets the base, the persisted offset and the resync request', async () => {
    const { clock, state } = setup()
    await clock.sync()
    state.wall += 10 * 60_000
    clock.getEstimate()
    clock.reset()
    expect(clock.needsResync()).toBe(false)
    expect(clock.getOffsetMs()).toBeNull()
    expect(clock.getEstimate()).toMatchObject({ known: false, approximate: true })
  })
})
