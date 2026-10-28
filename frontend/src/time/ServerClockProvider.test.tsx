import { act, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { timeResponse } from '../test/fixtures'
import { createServerClock } from './serverClock'
import { ServerClockProvider } from './ServerClockProvider'
import { useServerClock } from './useServerClock'

function Probe() {
  const { ms, approximate } = useServerClock()
  return (
    <p>
      <span data-testid="ms">{ms}</span>
      <span data-testid="approx">{String(approximate)}</span>
    </p>
  )
}

function setup(serverStart = Date.parse('2026-10-07T13:59:30Z')) {
  const state = { mono: 0, wall: serverStart + 3_600_000, server: serverStart }
  const fetchTime = vi.fn(async () => timeResponse(state.server))
  const clock = createServerClock({ perf: () => state.mono, wallNow: () => state.wall, fetchTime })
  const advance = (ms: number) => {
    state.mono += ms
    state.wall += ms
    state.server += ms
  }
  return { state, clock, fetchTime, advance }
}

beforeEach(() => {
  // setImmediate stays real: fake-indexeddb schedules its work with it.
  vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval', 'Date'] })
})

afterEach(() => {
  vi.useRealTimers()
})

describe('ServerClockProvider', () => {
  test('syncs on mount and the display follows the server, not the phone clock', async () => {
    const { clock, fetchTime, state } = setup()
    render(
      <ServerClockProvider clock={clock}>
        <Probe />
      </ServerClockProvider>,
    )
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0)
    })
    expect(fetchTime).toHaveBeenCalled()
    expect(Number(screen.getByTestId('ms').textContent)).toBe(state.server)
    expect(screen.getByTestId('approx').textContent).toBe('false')
  })

  test('resyncs when the page becomes visible and when the connection returns', async () => {
    const { clock, fetchTime } = setup()
    render(
      <ServerClockProvider clock={clock}>
        <Probe />
      </ServerClockProvider>,
    )
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0)
    })
    const before = fetchTime.mock.calls.length
    await act(async () => {
      document.dispatchEvent(new Event('visibilitychange'))
      await vi.advanceTimersByTimeAsync(0)
    })
    expect(fetchTime.mock.calls.length).toBeGreaterThan(before)
    const afterVisible = fetchTime.mock.calls.length
    await act(async () => {
      window.dispatchEvent(new Event('online'))
      await vi.advanceTimersByTimeAsync(0)
    })
    expect(fetchTime.mock.calls.length).toBeGreaterThan(afterVisible)
  })

  test('resyncs every 10 minutes while visible', async () => {
    const { clock, fetchTime } = setup()
    render(
      <ServerClockProvider clock={clock}>
        <Probe />
      </ServerClockProvider>,
    )
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0)
    })
    const before = fetchTime.mock.calls.length
    await act(async () => {
      await vi.advanceTimersByTimeAsync(10 * 60_000 + 10)
    })
    expect(fetchTime.mock.calls.length).toBeGreaterThan(before)
  })

  test('re-renders on the minute boundary of the estimate, not every second', async () => {
    const { clock, state, advance } = setup(Date.parse('2026-10-07T13:59:30Z'))
    let renders = 0
    function Counting() {
      useServerClock()
      renders++
      return null
    }
    render(
      <ServerClockProvider clock={clock} autoSync={false}>
        <Counting />
      </ServerClockProvider>,
    )
    await act(async () => {
      await clock.sync()
    })
    const afterSync = renders
    // 29 seconds later nothing has changed on the minute display.
    await act(async () => {
      advance(29_000)
      await vi.advanceTimersByTimeAsync(29_000)
    })
    expect(renders).toBe(afterSync)
    // Crossing 14:00:00 re-renders once.
    await act(async () => {
      advance(1_500)
      await vi.advanceTimersByTimeAsync(1_500)
    })
    expect(renders).toBeGreaterThan(afterSync)
    expect(renders - afterSync).toBeLessThanOrEqual(2)
    expect(state.server).toBeGreaterThan(Date.parse('2026-10-07T14:00:00Z'))
  })

  test('an offline reload uses the persisted offset and is flagged approximate', async () => {
    const { clock } = setup()
    clock.setPersistedOffset(-5_000)
    render(
      <ServerClockProvider clock={clock} autoSync={false}>
        <Probe />
      </ServerClockProvider>,
    )
    expect(screen.getByTestId('approx').textContent).toBe('true')
  })
})
