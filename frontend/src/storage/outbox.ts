import { getDb, type OutboxItem } from './db'

export const MAX_PENDING = 8
export const MIN_FREE_BYTES = 5 * 1024 * 1024
/** Unsent items are dropped (photo and coordinates) after this long; well beyond the 4-day offline budget. */
export const OUTBOX_MAX_AGE_MS = 7 * 24 * 60 * 60 * 1000
export const OUTBOX_EXPIRED_CODE = 'EXPIRED'

export class OutboxFullError extends Error {
  constructor() {
    super('Outbox full')
    this.name = 'OutboxFullError'
  }
}

export class StorageFullError extends Error {
  constructor() {
    super('Storage full')
    this.name = 'StorageFullError'
  }
}

export type NewOutboxItem = Omit<OutboxItem, 'seq' | 'attempts' | 'state' | 'failure'>

let persistRequested = false

async function hasRoomForAPhoto(): Promise<boolean> {
  const estimate = await navigator.storage?.estimate?.()
  if (!estimate || estimate.quota === undefined || estimate.usage === undefined) return true
  return estimate.quota - estimate.usage >= MIN_FREE_BYTES
}

/** Applies the device limits, stores the item and returns its seq. Never keeps a photo it refused. */
export async function enqueue(item: NewOutboxItem): Promise<number> {
  const db = await getDb()
  const all = await db.getAll('outbox')
  if (all.filter((i) => i.state === 'pending').length >= MAX_PENDING) throw new OutboxFullError()
  if (!(await hasRoomForAPhoto())) throw new StorageFullError()
  try {
    const seq = await db.add('outbox', { ...item, attempts: 0, state: 'pending' } as OutboxItem)
    if (!persistRequested) {
      persistRequested = true
      // Best effort: asks the browser not to evict the outbox under storage pressure.
      void navigator.storage?.persist?.()?.catch(() => undefined)
    }
    return seq
  } catch (error) {
    if (error instanceof DOMException && error.name === 'QuotaExceededError') throw new StorageFullError()
    throw error
  }
}

/** All items of one employee in FIFO order (pending and failed). */
export async function listItems(employeeId: number): Promise<OutboxItem[]> {
  const db = await getDb()
  const items = await db.getAllFromIndex('outbox', 'byEmployee', employeeId)
  return items.sort((a, b) => a.seq - b.seq)
}

export async function listPending(employeeId: number): Promise<OutboxItem[]> {
  return (await listItems(employeeId)).filter((i) => i.state === 'pending')
}

/** Items of every employee, oldest first (used to detect another person's unsent entries). */
export async function listAll(): Promise<OutboxItem[]> {
  const db = await getDb()
  return (await db.getAll('outbox')).sort((a, b) => a.seq - b.seq)
}

/** The oldest pending item of this employee, but only while no failure is waiting to be acknowledged. */
export async function oldestPending(employeeId: number): Promise<OutboxItem | undefined> {
  const items = await listItems(employeeId)
  if (items.some((i) => i.state === 'failed')) return undefined
  return items.find((i) => i.state === 'pending')
}

export async function countPending(employeeId: number): Promise<number> {
  return (await listPending(employeeId)).length
}

export async function remove(seq: number): Promise<void> {
  const db = await getDb()
  await db.delete('outbox', seq)
}

function finalFailure(item: OutboxItem, failure: { status: number; code?: string }): OutboxItem {
  const copy: OutboxItem = { ...item, state: 'failed', failure, photo: null }
  delete copy.lat
  delete copy.lng
  delete copy.accuracy
  return copy
}

/** Final failure: keep the record so the user can acknowledge it, but drop the photo and the coordinates at once (privacy). */
export async function markFailed(seq: number, failure: { status: number; code?: string }): Promise<void> {
  const db = await getDb()
  const tx = db.transaction('outbox', 'readwrite')
  const item = await tx.store.get(seq)
  if (item) {
    await tx.store.put(finalFailure(item, failure))
  }
  await tx.done
}

/**
 * Pending items older than OUTBOX_MAX_AGE_MS (all employees) become failed/EXPIRED with photo and
 * coordinates dropped, so evidence never stays on the phone indefinitely. Returns how many expired.
 */
export async function expireOld(nowMs: number = Date.now()): Promise<number> {
  const db = await getDb()
  const tx = db.transaction('outbox', 'readwrite')
  let expired = 0
  for (const item of await tx.store.getAll()) {
    const createdMs = Date.parse(item.createdAt)
    if (item.state !== 'pending' || Number.isNaN(createdMs) || nowMs - createdMs <= OUTBOX_MAX_AGE_MS) continue
    await tx.store.put(finalFailure(item, { status: 0, code: OUTBOX_EXPIRED_CODE }))
    expired++
  }
  await tx.done
  return expired
}

export async function incrementAttempts(seq: number): Promise<void> {
  const db = await getDb()
  const tx = db.transaction('outbox', 'readwrite')
  const item = await tx.store.get(seq)
  if (item) await tx.store.put({ ...item, attempts: item.attempts + 1 })
  await tx.done
}

export async function clearAll(): Promise<void> {
  const db = await getDb()
  await db.clear('outbox')
}

export function resetOutboxModuleForTests() {
  persistRequested = false
}
