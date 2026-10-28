import { useContext } from 'react'
import { ServerClockContext } from './serverClockContext'
import type { ClockEstimate, ServerClock } from './serverClock'

/** The estimate at render time (re-rendered each minute) plus the store for imperative reads. */
export function useServerClock(): ClockEstimate & { clock: ServerClock } {
  const { clock } = useContext(ServerClockContext)
  return { ...clock.getEstimate(), clock }
}
