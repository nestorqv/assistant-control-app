import { createContext } from 'react'
import type { TimeEntryResponse, TimeEntryType } from '../api/types'
import type { Fix } from '../capture/geolocation'
import type { OutboxItem } from '../storage/db'

export type Capture = { type: TimeEntryType; photo: ArrayBuffer; photoType: string; fix: Fix | null }

export type DeliveredEntry = { id: number; response: TimeEntryResponse; replayed: boolean }

export type OutboxValue = {
  pending: OutboxItem[]
  failed: OutboxItem[]
  flushing: boolean
  online: boolean
  /** the last request failed at network level even if navigator.onLine says online */
  networkProblem: boolean
  /** server answers kept in memory so the result line shows them until the next `today` */
  lastResults: Partial<Record<TimeEntryType, TimeEntryResponse>>
  /** changes after every delivery or acknowledged failure: reload `today` */
  syncVersion: number
  /** the most recent delivery, for announcements (id increases with each one) */
  lastDelivered: DeliveredEntry | null
  enqueueCapture(capture: Capture): Promise<OutboxItem>
  acknowledgeFailure(seq: number): Promise<void>
  flushNow(): void
}

const noop = async () => {
  throw new Error('OutboxProvider is missing')
}

export const OutboxContext = createContext<OutboxValue>({
  pending: [],
  failed: [],
  flushing: false,
  online: true,
  networkProblem: false,
  lastResults: {},
  syncVersion: 0,
  lastDelivered: null,
  enqueueCapture: noop,
  acknowledgeFailure: noop,
  flushNow: () => undefined,
})
