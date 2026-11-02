import type { AdminEntrySummary, DashboardRow } from './api/adminTypes'

export type EvidenceKind = 'photoGps' | 'gpsAt' | 'photoOnly' | 'manual' | 'tablet' | 'none'

/** "Edited" is derived from the times, not from `adjusted` (which is also true after a plain approval). */
export function isEdited(entry: AdminEntrySummary): boolean {
  return entry.source !== 'ADMIN' && entry.effectiveTime !== entry.serverTime
}

export function evidenceKind(entry: AdminEntrySummary): EvidenceKind {
  if (entry.source === 'ADMIN') return 'manual'
  if (entry.source === 'TABLET') return 'tablet'
  const outside = entry.reviewReasons.includes('OUTSIDE_GEOFENCE') && entry.distanceMeters !== null
  if (outside) return 'gpsAt'
  if (entry.hasPhoto && entry.hasGps) return 'photoGps'
  if (entry.hasPhoto) return 'photoOnly'
  return 'none'
}

/**
 * One evidence button per row: the entry that needs attention (pending or rejected), otherwise the clock-in.
 * The other entry of the day is one click away inside the dialog.
 */
export function targetEntry(row: DashboardRow): AdminEntrySummary | null {
  const attention = [row.clockIn, row.clockOut].find((e) => e !== null && e.status !== 'VALID')
  return attention ?? row.clockIn ?? row.clockOut
}

/** The "record clock-in / clock-out" shortcut of rows that are missing an entry. */
export function createAction(row: DashboardRow): 'CLOCK_IN' | 'CLOCK_OUT' | null {
  if (row.dayStatus === 'NOT_CLOCKED') return 'CLOCK_IN'
  // Never while the shift is in progress (an overnight shift can still be open the next morning).
  if (row.dayStatus === 'MISSING_CLOCK_OUT' && !row.inProgress) return 'CLOCK_OUT'
  return null
}
