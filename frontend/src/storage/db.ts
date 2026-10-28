import { openDB, type DBSchema, type IDBPDatabase } from 'idb'
import type { Role, TimeEntryType, TodayResponse } from '../api/types'

/** Non-secret identity of the person using this device. The token and the PIN are never stored here. */
export type Identity = {
  employeeId: number
  name: string
  role: Role
  companyName: string
  timeZone: string
  companyCode: string
  employeeCode: string
}

export type OutboxItem = {
  seq: number
  employeeId: number
  /** Company of the identity that captured it; checked against the token's identity at send time. Absent on older items. */
  companyCode?: string
  type: TimeEntryType
  clientUuid: string
  deviceTime: string
  lat?: number
  lng?: number
  accuracy?: number
  // ArrayBuffer, never Blob: structured clone of Blobs is unreliable (old iOS Safari, jsdom).
  photo: ArrayBuffer | null
  photoType: string
  createdAt: string
  attempts: number
  state: 'pending' | 'failed'
  failure?: { status: number; code?: string }
}

/** Last completed shift, kept so the overnight "last shift" line survives a reload (POST response only). */
export type LastCompleted = { workDate: string; clockInAt: string; clockOutAt: string }

export type Snapshot = {
  employeeId: number
  today: TodayResponse
  lastCompleted?: LastCompleted
  /** server minus phone clock, for the approximate clock after an offline reload */
  clockOffsetMs: number | null
  savedAt: number
}

interface AttendanceDB extends DBSchema {
  outbox: { key: number; value: OutboxItem; indexes: { byEmployee: number } }
  identity: { key: string; value: Identity }
  snapshot: { key: string; value: Snapshot }
}

let dbPromise: Promise<IDBPDatabase<AttendanceDB>> | null = null

export function getDb(): Promise<IDBPDatabase<AttendanceDB>> {
  if (!dbPromise) {
    const opening: Promise<IDBPDatabase<AttendanceDB>> = openDB<AttendanceDB>('attendance', 1, {
      upgrade(db) {
        const outbox = db.createObjectStore('outbox', { keyPath: 'seq', autoIncrement: true })
        outbox.createIndex('byEmployee', 'employeeId')
        db.createObjectStore('identity')
        db.createObjectStore('snapshot')
      },
      // Another tab wants to upgrade or delete: close so it is not blocked, and reopen on the next use.
      blocking() {
        void opening.then((db) => db.close())
        dbPromise = null
      },
      terminated() {
        dbPromise = null
      },
    })
    dbPromise = opening
    opening.catch(() => {
      if (dbPromise === opening) dbPromise = null
    })
  }
  return dbPromise
}

export async function resetDbForTests(): Promise<void> {
  const pending = dbPromise
  dbPromise = null
  if (pending) (await pending.catch(() => null))?.close()
}
