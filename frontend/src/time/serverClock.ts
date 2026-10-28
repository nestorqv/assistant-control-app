import { getTime } from '../api/timeEntries'
import type { TimeResponse } from '../api/types'

export type ClockDeps = {
  /** monotonic milliseconds (performance.now) */
  perf: () => number
  /** phone wall clock (Date.now) */
  wallNow: () => number
  fetchTime: () => Promise<TimeResponse>
}

export type ClockEstimate = {
  ms: number
  /** true when based on the phone clock: suspend/clock change, or a persisted offset after a reload */
  approximate: boolean
  /** false until a sync or a persisted offset exists; then ms is just the phone clock */
  known: boolean
}

type Base = { server: number; mono: number; wall: number; rtt: number }

/** If monotonic and wall time disagree by more than this, the phone slept or its clock was changed. */
const DIVERGENCE_MS = 2_000
const FIRST_SYNC_SAMPLES = 3

const defaultDeps: ClockDeps = {
  perf: () => performance.now(),
  wallNow: () => Date.now(),
  fetchTime: getTime,
}

/**
 * Server clock estimate. Reads never use the phone clock while the monotonic base is valid, so a
 * wrong phone clock does not move the display; only a divergence falls back to wall time.
 */
export function createServerClock(deps: ClockDeps = defaultDeps) {
  let base: Base | null = null
  let persistedOffset: number | null = null
  let resyncRequested = false
  const listeners = new Set<() => void>()

  function notify() {
    listeners.forEach((listener) => listener())
  }

  async function sample(): Promise<Base> {
    const t0 = deps.perf()
    const response = await deps.fetchTime()
    const t1 = deps.perf()
    const rtt = t1 - t0
    return { server: response.epochMillis + rtt / 2, mono: t1, wall: deps.wallNow(), rtt }
  }

  function getEstimate(): ClockEstimate {
    if (base) {
      const monoElapsed = deps.perf() - base.mono
      const wallElapsed = deps.wallNow() - base.wall
      if (Math.abs(monoElapsed - wallElapsed) > DIVERGENCE_MS) {
        resyncRequested = true
        return { ms: base.server + wallElapsed, approximate: true, known: true }
      }
      return { ms: base.server + monoElapsed, approximate: false, known: true }
    }
    if (persistedOffset !== null) return { ms: deps.wallNow() + persistedOffset, approximate: true, known: true }
    return { ms: deps.wallNow(), approximate: true, known: false }
  }

  return {
    getEstimate,

    /** Takes up to 3 samples the first time (lowest RTT wins), 1 afterwards. Returns false when offline. */
    async sync(): Promise<boolean> {
      const wanted = base ? 1 : FIRST_SYNC_SAMPLES
      let best: Base | null = null
      for (let i = 0; i < wanted; i++) {
        try {
          const result = await sample()
          if (!best || result.rtt < best.rtt) best = result
        } catch {
          break
        }
      }
      if (!best) return false
      base = best
      resyncRequested = false
      notify()
      return true
    },

    /** True after a divergence was seen; the provider then resyncs. */
    needsResync: () => resyncRequested,

    /** server - phone clock at this moment, to persist for an offline reload; null before any sync. */
    getOffsetMs(): number | null {
      if (!base) return persistedOffset
      return getEstimate().ms - deps.wallNow()
    },

    setPersistedOffset(offsetMs: number | null) {
      persistedOffset = offsetMs
      notify()
    },

    reset() {
      base = null
      persistedOffset = null
      resyncRequested = false
      notify()
    },

    subscribe(listener: () => void) {
      listeners.add(listener)
      return () => {
        listeners.delete(listener)
      }
    },
  }
}

export type ServerClock = ReturnType<typeof createServerClock>

export const serverClock: ServerClock = createServerClock()
