import { createContext } from 'react'
import { serverClock, type ServerClock } from './serverClock'

/** `tick` changes on minute boundaries and after a sync, so consumers re-read the estimate. */
export type ServerClockValue = { clock: ServerClock; tick: number }

export const ServerClockContext = createContext<ServerClockValue>({ clock: serverClock, tick: 0 })
