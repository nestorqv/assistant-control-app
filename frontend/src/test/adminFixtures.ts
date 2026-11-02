// Builders that match the admin JSON exactly (src/admin/api/adminTypes.ts mirrors the Java records).
import type {
  AdminEntryDetail,
  AdminEntrySummary,
  DashboardResponse,
  DashboardRow,
  ReviewItem,
} from '../admin/api/adminTypes'

export const adminIdentity = { employeeId: 9, name: 'Ana Mora', role: 'ADMIN' as const, companyName: 'DEMO', employeeCode: '9001' }

export function entry(over: Partial<AdminEntrySummary> = {}): AdminEntrySummary {
  return {
    id: 1,
    type: 'CLOCK_IN',
    effectiveTime: '2026-10-07T13:58:00Z',
    serverTime: over.effectiveTime ?? '2026-10-07T13:58:00Z',
    status: 'VALID',
    reviewReasons: [],
    source: 'MOBILE',
    distanceMeters: 35,
    insideGeofence: true,
    hasPhoto: true,
    hasGps: true,
    adjusted: false,
    ...over,
  }
}

export function row(name: string, over: Partial<DashboardRow> = {}): DashboardRow {
  return {
    employee: { id: Math.abs(hash(name)), code: '10' + (Math.abs(hash(name)) % 90), name, jobTitle: 'Cajera', active: true },
    branchName: 'Central',
    clockIn: null,
    clockOut: null,
    workedMinutes: null,
    inProgress: false,
    lateMinutes: null,
    dayStatus: 'NOT_CLOCKED',
    schedule: { startTime: '08:00:00', endTime: '17:00:00', graceMinutes: 5, overnight: false },
    ...over,
  }
}

function hash(text: string): number {
  let h = 7
  for (const ch of text) h = (h * 31 + ch.charCodeAt(0)) | 0
  return h
}

/** The blueprint (page 2), reduced to what the tests need: 9 of 12 present, 2 late, 3 not clocked, 1 outside. */
export function dashboard(over: Partial<DashboardResponse> = {}): DashboardResponse {
  return {
    date: '2026-10-07',
    today: '2026-10-07',
    timeZone: 'America/Costa_Rica',
    companyName: 'DEMO',
    branchId: null,
    kpis: { present: 9, expected: 12, late: 2, notClocked: 3, notYetDue: 1, toReview: 1, toReviewByReason: { OUTSIDE_GEOFENCE: 1 } },
    rows: [
      row('María Rojas', { clockIn: entry({ id: 11 }), dayStatus: 'ON_TIME', inProgress: true, lateMinutes: 0 }),
      row('Luis Vargas', {
        clockIn: entry({ id: 12, effectiveTime: '2026-10-07T14:01:00Z', status: 'PENDING_REVIEW', reviewReasons: ['OUTSIDE_GEOFENCE'], distanceMeters: 420, insideGeofence: false }),
        dayStatus: 'OUTSIDE_AREA',
        inProgress: true,
        lateMinutes: 0,
      }),
      row('Karla Méndez', {
        clockIn: entry({ id: 13, effectiveTime: '2026-10-07T12:00:00Z' }),
        clockOut: entry({ id: 14, type: 'CLOCK_OUT', effectiveTime: '2026-10-07T20:04:00Z' }),
        workedMinutes: 484,
        dayStatus: 'COMPLETE',
      }),
      row('Diego Soto', { dayStatus: 'NOT_CLOCKED' }),
    ],
    generatedAt: '2026-10-07T15:00:00Z',
    ...over,
  }
}

export function detail(over: Partial<AdminEntryDetail> = {}): AdminEntryDetail {
  return {
    id: 12,
    type: 'CLOCK_IN',
    workDate: '2026-10-07',
    employee: { id: 2, code: '1004', name: 'Luis Vargas', jobTitle: 'Cajero' },
    ownEntry: false,
    source: 'MOBILE',
    serverTime: '2026-10-07T14:01:00Z',
    effectiveTime: '2026-10-07T14:01:00Z',
    deviceTime: '2026-10-07T13:55:00Z',
    skewSeconds: 360,
    syncedLate: false,
    status: 'PENDING_REVIEW',
    reviewReasons: ['OUTSIDE_GEOFENCE'],
    distanceMeters: 420,
    gpsAccuracyMeters: 12,
    insideGeofence: false,
    lateMinutes: 0,
    hasPhoto: true,
    hasGps: true,
    adjusted: false,
    location: { latitude: 9.9325, longitude: -84.0796 },
    branch: { id: 1, name: 'Central', latitude: 9.93, longitude: -84.08, radiusMeters: 100 },
    pair: null,
    adjustments: [],
    ...over,
  }
}

export function reviewItem(over: Partial<ReviewItem> = {}): ReviewItem {
  const d = detail()
  return {
    employee: d.employee,
    workDate: d.workDate,
    ownEntry: false,
    entry: {
      ...entry({ id: d.id, effectiveTime: d.effectiveTime, serverTime: d.serverTime, status: 'PENDING_REVIEW', reviewReasons: ['OUTSIDE_GEOFENCE'], distanceMeters: 420, insideGeofence: false }),
      deviceTime: d.deviceTime,
      skewSeconds: 360,
      syncedLate: false,
      gpsAccuracyMeters: 12,
      lateMinutes: 0,
    },
    pair: null,
    ...over,
  }
}
