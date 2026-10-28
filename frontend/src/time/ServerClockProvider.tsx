import { useEffect, useMemo, useState, type ReactNode } from 'react'
import { loadSnapshot } from '../storage/snapshot'
import { ServerClockContext } from './serverClockContext'
import { serverClock as defaultClock, type ServerClock } from './serverClock'

const RESYNC_EVERY_MS = 10 * 60_000
const MINUTE_MS = 60_000

type Props = { children: ReactNode; clock?: ServerClock; autoSync?: boolean }

/**
 * Keeps the server clock fresh: sync on open, when the page becomes visible, when the connection
 * returns, every 10 minutes while visible and after a divergence. The display only re-renders on
 * minute boundaries of the estimate (battery), and only while the page is visible.
 */
export function ServerClockProvider({ children, clock = defaultClock, autoSync = true }: Props) {
  const [tick, setTick] = useState(0)

  useEffect(() => {
    if (!autoSync) return
    let cancelled = false
    // After an offline reload the persisted offset gives an approximate clock until the first sync.
    void loadSnapshot()
      .then((snapshot) => {
        if (!cancelled && snapshot?.clockOffsetMs != null && !clock.getEstimate().known) {
          clock.setPersistedOffset(snapshot.clockOffsetMs)
        }
      })
      .catch(() => undefined)

    const sync = () => void clock.sync()
    const onVisibility = () => {
      if (document.visibilityState === 'visible') sync()
    }
    sync()
    document.addEventListener('visibilitychange', onVisibility)
    window.addEventListener('online', sync)
    const interval = setInterval(() => {
      if (document.visibilityState === 'visible') sync()
    }, RESYNC_EVERY_MS)
    return () => {
      cancelled = true
      document.removeEventListener('visibilitychange', onVisibility)
      window.removeEventListener('online', sync)
      clearInterval(interval)
    }
  }, [clock, autoSync])

  useEffect(() => {
    let timer: ReturnType<typeof setTimeout> | undefined
    function schedule() {
      const { ms } = clock.getEstimate()
      timer = setTimeout(() => {
        if (document.visibilityState === 'visible') {
          if (clock.needsResync()) void clock.sync()
          setTick((n) => n + 1)
        }
        schedule()
      }, MINUTE_MS - (ms % MINUTE_MS) + 50)
    }
    schedule()
    // Coming back to the app: show the right minute at once instead of waiting for the timer.
    const onVisibility = () => {
      if (document.visibilityState === 'visible') setTick((n) => n + 1)
    }
    document.addEventListener('visibilitychange', onVisibility)
    return () => {
      clearTimeout(timer)
      document.removeEventListener('visibilitychange', onVisibility)
    }
  }, [clock])

  // A sync changes the estimate even between ticks.
  useEffect(() => clock.subscribe(() => setTick((n) => n + 1)), [clock])

  const value = useMemo(() => ({ clock, tick }), [clock, tick])
  return <ServerClockContext value={value}>{children}</ServerClockContext>
}
