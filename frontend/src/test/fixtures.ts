// Builders that match the backend JSON exactly (src/api/types.ts mirrors the Java records).
import type {
  EntrySummary,
  LoginResponse,
  TimeEntryResponse,
  TimeResponse,
  TodayResponse,
} from '../api/types'

export const json = { 'content-type': 'application/json' }

export function jsonResponse(body: unknown, status = 200, headers: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), { status, headers: { ...json, ...headers } })
}

/** application/problem+json, as ApiExceptionHandler sends it. */
export function problemResponse(status: number, code: string, headers: Record<string, string> = {}): Response {
  return new Response(JSON.stringify({ type: 'about:blank', title: code, status, code }), {
    status,
    headers: { 'content-type': 'application/problem+json', ...headers },
  })
}

export function loginResponse(over: Partial<LoginResponse> = {}): LoginResponse {
  return {
    token: 'token-1',
    tokenType: 'Bearer',
    expiresAt: '2099-01-01T00:00:00Z',
    employee: { id: 7, name: 'María Pérez', jobTitle: 'Cajera', role: 'EMPLOYEE', consentPending: false },
    company: { name: 'DEMO', timeZone: 'America/Costa_Rica' },
    ...over,
  }
}

export function entrySummary(over: Partial<EntrySummary> = {}): EntrySummary {
  return {
    id: 1,
    serverTime: '2026-10-07T13:58:00Z',
    status: 'VALID',
    reviewReasons: [],
    distanceMeters: 35,
    insideGeofence: true,
    lateMinutes: null,
    hasPhoto: true,
    ...over,
  }
}

export function today(over: Partial<TodayResponse> = {}): TodayResponse {
  return {
    workDate: '2026-10-07',
    nextAction: 'CLOCK_IN',
    blockedReason: null,
    clockIn: null,
    clockOut: null,
    workedMinutes: null,
    inProgress: false,
    missingClockOut: false,
    forgottenWorkDate: null,
    schedule: { startTime: '08:00:00', endTime: '17:00:00', graceMinutes: 10, overnight: false },
    branch: { name: 'Central', radiusMeters: 100 },
    serverTime: '2026-10-07T13:58:00.123456Z',
    timeZone: 'America/Costa_Rica',
    consentPending: false,
    ...over,
  }
}

export function entryResponse(over: Partial<TimeEntryResponse> = {}): TimeEntryResponse {
  return {
    id: 10,
    clientUuid: '3f1c2d4e-5a6b-4c7d-8e9f-0a1b2c3d4e5f',
    type: 'CLOCK_IN',
    serverTime: '2026-10-07T13:58:00Z',
    workDate: '2026-10-07',
    status: 'VALID',
    reviewReasons: [],
    syncedLate: false,
    skewSeconds: 0,
    distanceMeters: 35,
    radiusMeters: 100,
    insideGeofence: true,
    lateMinutes: null,
    hasPhoto: true,
    replayed: false,
    ...over,
  }
}

export function timeResponse(epochMillis: number, timeZone = 'America/Costa_Rica'): TimeResponse {
  return {
    serverTime: new Date(epochMillis).toISOString(),
    epochMillis,
    timeZone,
    localDateTime: '2026-10-07T07:58:00',
  }
}

/** Smallest valid baseline JPEG header: SOI, APP0, SOF0, SOS, EOI. */
export function baselineJpegBytes(): Uint8Array {
  return new Uint8Array([
    0xff, 0xd8, 0xff, 0xe0, 0x00, 0x04, 0x4a, 0x46, 0xff, 0xc0, 0x00, 0x0b, 0x08, 0x00, 0x10, 0x00, 0x10, 0x01,
    0x01, 0x11, 0x00, 0xff, 0xda, 0x00, 0x02, 0xff, 0xd9,
  ])
}
