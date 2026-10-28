// Hand-written next to the backend records (there is no OpenAPI generator). src/api/contract.test.ts
// compares the enum unions below with the Java enums so a backend change cannot drift unnoticed.

export type Role = 'EMPLOYEE' | 'ADMIN'
export type NextAction = 'CLOCK_IN' | 'CLOCK_OUT' | 'NONE'
export type TimeEntryType = 'CLOCK_IN' | 'CLOCK_OUT'
export type TimeEntryStatus = 'VALID' | 'PENDING_REVIEW' | 'REJECTED'
export type ReviewReason =
  | 'NO_GPS'
  | 'OUTSIDE_GEOFENCE'
  | 'LOW_GPS_ACCURACY'
  | 'SYNCED_LATE'
  | 'DEVICE_CLOCK_AHEAD'
  | 'SHIFT_TOO_LONG'
export type BlockedReason = 'CONSENT_REQUIRED' | 'NO_BRANCH_ASSIGNED'

// auth/LoginResponse.java
export type LoginResponse = {
  token: string
  tokenType: 'Bearer'
  expiresAt: string
  employee: { id: number; name: string; jobTitle: string | null; role: Role; consentPending: boolean }
  company: { name: string; timeZone: string }
}

// timeentry/TimeResponse.java
export type TimeResponse = { serverTime: string; epochMillis: number; timeZone: string; localDateTime: string }

// timeentry/TodayResponse.java
export type EntrySummary = {
  id: number
  serverTime: string
  status: TimeEntryStatus
  reviewReasons: ReviewReason[]
  distanceMeters: number | null
  insideGeofence: boolean | null
  lateMinutes: number | null
  hasPhoto: boolean
}

export type TodayResponse = {
  workDate: string
  nextAction: NextAction
  blockedReason: BlockedReason | null
  clockIn: EntrySummary | null
  clockOut: EntrySummary | null
  workedMinutes: number | null
  inProgress: boolean
  missingClockOut: boolean
  forgottenWorkDate: string | null
  schedule: { startTime: string; endTime: string; graceMinutes: number; overnight: boolean } | null
  // No coordinates, by design: the client never computes a distance.
  branch: { name: string; radiusMeters: number } | null
  serverTime: string
  timeZone: string
  consentPending: boolean
}

// timeentry/TimeEntryResponse.java
export type TimeEntryResponse = {
  id: number
  clientUuid: string
  type: TimeEntryType
  serverTime: string
  workDate: string
  status: TimeEntryStatus
  reviewReasons: ReviewReason[]
  syncedLate: boolean
  skewSeconds: number | null
  distanceMeters: number | null
  radiusMeters: number | null
  insideGeofence: boolean | null
  lateMinutes: number | null
  hasPhoto: boolean
  replayed: boolean
}

// What the screen shows for one entry, from either a POST response or today.clockIn/clockOut.
export type EntryOutcome = Pick<
  EntrySummary,
  'status' | 'reviewReasons' | 'distanceMeters' | 'insideGeofence' | 'lateMinutes' | 'serverTime'
>
