// Hand-written next to the Java records in backend/.../admin (src/api/contract.test.ts checks the enums).
// Instants are ISO strings; LocalDate values are YYYY-MM-DD in the company time zone.
import type { ReviewReason, Role, TimeEntrySource, TimeEntryStatus, TimeEntryType } from '../../api/types'

export type DayStatus =
  | 'REJECTED'
  | 'OUTSIDE_AREA'
  | 'NEEDS_REVIEW'
  | 'MISSING_CLOCK_OUT'
  | 'COMPLETE'
  | 'LATE'
  | 'ON_TIME'
  | 'UNSCHEDULED'
  | 'NOT_YET_DUE'
  | 'NOT_CLOCKED'
  | 'DAY_OFF'

export type AdjustmentAction = 'APPROVE' | 'REJECT' | 'CORRECT_TIME' | 'CREATE_ENTRY'

// admin/AdminEntrySummary.java
export type AdminEntrySummary = {
  id: number
  type: TimeEntryType
  effectiveTime: string
  serverTime: string
  status: TimeEntryStatus
  reviewReasons: ReviewReason[]
  source: TimeEntrySource
  distanceMeters: number | null
  insideGeofence: boolean | null
  hasPhoto: boolean
  hasGps: boolean
  adjusted: boolean
}

export type Schedule = { startTime: string; endTime: string; graceMinutes: number; overnight: boolean }

// admin/DashboardResponse.java
export type DashboardRow = {
  employee: { id: number; code: string; name: string; jobTitle: string | null; active: boolean }
  branchName: string | null
  clockIn: AdminEntrySummary | null
  clockOut: AdminEntrySummary | null
  workedMinutes: number | null
  inProgress: boolean
  lateMinutes: number | null
  dayStatus: DayStatus
  schedule: Schedule | null
}

export type Kpis = {
  present: number
  expected: number
  late: number
  notClocked: number
  notYetDue: number
  toReview: number
  toReviewByReason: Partial<Record<ReviewReason, number>>
}

export type DashboardResponse = {
  date: string
  today: string
  timeZone: string
  companyName: string
  branchId: number | null
  kpis: Kpis
  rows: DashboardRow[]
  generatedAt: string
}

// admin/ReviewItem.java
export type EmployeeRef = { id: number; code: string; name: string; jobTitle: string | null }

export type ReviewEntry = AdminEntrySummary & {
  deviceTime: string | null
  skewSeconds: number | null
  syncedLate: boolean
  gpsAccuracyMeters: number | null
  lateMinutes: number | null
}

export type ReviewItem = { employee: EmployeeRef; workDate: string; ownEntry: boolean; entry: ReviewEntry; pair: AdminEntrySummary | null }
export type Page<T> = { items: T[]; page: number; size: number; totalItems: number }
export type ReviewCount = { pending: number; today: string; timeZone: string }

// admin/AdminEntryDetail.java
export type AdjustmentView = {
  id: number
  action: AdjustmentAction
  field: 'status' | 'effective_time' | 'late_minutes' | 'entry'
  oldValue: string | null
  newValue: string
  reason: string
  adminName: string
  selfApproved: boolean
  createdAt: string
}

export type AdminEntryDetail = {
  id: number
  type: TimeEntryType
  workDate: string
  employee: EmployeeRef
  ownEntry: boolean
  source: TimeEntrySource
  serverTime: string
  effectiveTime: string
  deviceTime: string | null
  skewSeconds: number | null
  syncedLate: boolean
  status: TimeEntryStatus
  reviewReasons: ReviewReason[]
  distanceMeters: number | null
  gpsAccuracyMeters: number | null
  insideGeofence: boolean | null
  lateMinutes: number | null
  hasPhoto: boolean
  hasGps: boolean
  adjusted: boolean
  location: { latitude: number; longitude: number } | null
  branch: { id: number; name: string; latitude: number; longitude: number; radiusMeters: number }
  pair: AdminEntrySummary | null
  adjustments: AdjustmentView[]
}

// admin/AdminBranchController.java
export type BranchView = {
  id: number
  name: string
  latitude: number
  longitude: number
  radiusMeters: number
  active: boolean
  activeEmployees: number
  version: number
}

export type BranchInput = { name: string; latitude: number; longitude: number; radiusMeters: number }

// admin/AdminEmployeeView.java: never the PIN hash, failed attempts or token version
export type AdminEmployeeView = {
  id: number
  code: string
  name: string
  jobTitle: string | null
  role: Role
  active: boolean
  branchId: number | null
  branchName: string | null
  branchActive: boolean | null
  consentGiven: boolean
  locked: boolean
  lockedUntil: string | null
  hasSchedule: boolean
  version: number
}

export type PinResetReason = 'FORGOT_PIN' | 'ACCOUNT_LOCKED' | 'POSSIBLE_MISUSE' | 'OTHER'
export const PIN_RESET_REASONS = ['FORGOT_PIN', 'ACCOUNT_LOCKED', 'POSSIBLE_MISUSE', 'OTHER'] as const satisfies readonly PinResetReason[]

// admin/AdminEmployeeDetail.java: the view plus the latest PIN reset (never the PIN)
export type LastPinReset = { at: string; byName: string | null; reasonCode: PinResetReason | null; note: string | null }
export type AdminEmployeeDetail = AdminEmployeeView & { lastPinReset: LastPinReset | null }

// The only two responses that carry a PIN (Cache-Control: no-store). Never put them in a shared cache.
export type CreatedEmployee = { employee: AdminEmployeeView; pin: string }
export type PinResetResult = { employee: AdminEmployeeDetail; pin: string }

export type EmployeeInput = { name: string; jobTitle: string | null; role: Role; branchId: number | null }

export type IsoDay = 1 | 2 | 3 | 4 | 5 | 6 | 7
export type ScheduleDay = { dayOfWeek: IsoDay; startTime: string; endTime: string; graceMinutes: number }
