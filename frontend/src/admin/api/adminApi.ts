import { apiBlob, apiRequest } from '../../api/client'
import type { TimeEntryStatus, TimeEntryType } from '../../api/types'
import type {
  AdminEmployeeDetail,
  AdminEmployeeView,
  AdminEntryDetail,
  BranchInput,
  BranchView,
  CreatedEmployee,
  DashboardResponse,
  EmployeeInput,
  Page,
  PinResetReason,
  PinResetResult,
  ReviewCount,
  ReviewItem,
  ScheduleDay,
} from './adminTypes'

const BASE = '/api/admin'

function query(params: Record<string, string | number | null | undefined>): string {
  const search = new URLSearchParams()
  for (const [key, value] of Object.entries(params)) {
    if (value !== null && value !== undefined && value !== '') search.set(key, String(value))
  }
  const text = search.toString()
  return text ? `?${text}` : ''
}

export function fetchDashboard(date: string | null, branchId: number | null): Promise<DashboardResponse> {
  return apiRequest(`${BASE}/dashboard${query({ date, branchId })}`, { auth: true })
}

export function fetchBranches(): Promise<BranchView[]> {
  return apiRequest(`${BASE}/branches`, { auth: true })
}

export type EmployeeOption = { id: number; code: string; name: string; active: boolean }

/** The employee list; a superset of EmployeeOption, so the review filter keeps working with it. */
export function fetchEmployees(): Promise<AdminEmployeeView[]> {
  return apiRequest(`${BASE}/employees`, { auth: true })
}

export function fetchReviewCount(): Promise<ReviewCount> {
  return apiRequest(`${BASE}/review-queue/count`, { auth: true })
}

export type ReviewQuery = {
  status: Extract<TimeEntryStatus, 'PENDING_REVIEW' | 'REJECTED'>
  from: string | null
  to: string | null
  employeeId: number | null
  page: number
}

export const REVIEW_PAGE_SIZE = 25

export function fetchReviewQueue(q: ReviewQuery): Promise<Page<ReviewItem>> {
  return apiRequest(
    `${BASE}/review-queue${query({ status: q.status, from: q.from, to: q.to, employeeId: q.employeeId, page: q.page, size: REVIEW_PAGE_SIZE })}`,
    { auth: true },
  )
}

export function fetchEntry(id: number): Promise<AdminEntryDetail> {
  return apiRequest(`${BASE}/time-entries/${id}`, { auth: true })
}

/** The selfie as a blob; a 404 (no photo) is an ApiProblem the caller maps to "no photo". */
export function fetchPhoto(id: number, signal?: AbortSignal) {
  return apiBlob(`${BASE}/time-entries/${id}/photo`, { signal, fallbackName: 'photo.jpg' })
}

export function approveEntry(id: number, reason: string, expectedStatus: TimeEntryStatus): Promise<AdminEntryDetail> {
  return apiRequest(`${BASE}/time-entries/${id}/approve`, { method: 'POST', auth: true, json: { reason, expectedStatus } })
}

export function rejectEntry(id: number, reason: string, expectedStatus: TimeEntryStatus): Promise<AdminEntryDetail> {
  return apiRequest(`${BASE}/time-entries/${id}/reject`, { method: 'POST', auth: true, json: { reason, expectedStatus } })
}

export function correctEntry(
  id: number,
  body: { reason: string; expectedStatus: TimeEntryStatus; expectedEffectiveTime: string; localTime: string },
): Promise<AdminEntryDetail> {
  return apiRequest(`${BASE}/time-entries/${id}/correct`, { method: 'POST', auth: true, json: body })
}

export function createEntry(body: {
  employeeId: number
  type: TimeEntryType
  workDate: string
  localTime: string
  reason: string
}): Promise<AdminEntryDetail> {
  return apiRequest(`${BASE}/time-entries`, { method: 'POST', auth: true, json: body })
}

// Management (Stage 4b). Every edit / activate / deactivate carries the version the admin last saw.

export function createBranch(body: BranchInput): Promise<BranchView> {
  return apiRequest(`${BASE}/branches`, { method: 'POST', auth: true, json: body })
}

export function updateBranch(id: number, body: BranchInput & { expectedVersion: number }): Promise<BranchView> {
  return apiRequest(`${BASE}/branches/${id}`, { method: 'PUT', auth: true, json: body })
}

export function setBranchActive(id: number, active: boolean, expectedVersion: number): Promise<BranchView> {
  return apiRequest(`${BASE}/branches/${id}/${active ? 'activate' : 'deactivate'}`, {
    method: 'POST',
    auth: true,
    json: { expectedVersion },
  })
}

export function fetchEmployee(id: number): Promise<AdminEmployeeDetail> {
  return apiRequest(`${BASE}/employees/${id}`, { auth: true })
}

export function createEmployee(body: EmployeeInput & { code: string }): Promise<CreatedEmployee> {
  return apiRequest(`${BASE}/employees`, { method: 'POST', auth: true, json: body })
}

export function updateEmployee(id: number, body: EmployeeInput & { expectedVersion: number }): Promise<AdminEmployeeView> {
  return apiRequest(`${BASE}/employees/${id}`, { method: 'PUT', auth: true, json: body })
}

export function setEmployeeActive(id: number, active: boolean, expectedVersion: number): Promise<AdminEmployeeView> {
  return apiRequest(`${BASE}/employees/${id}/${active ? 'activate' : 'deactivate'}`, {
    method: 'POST',
    auth: true,
    json: { expectedVersion },
  })
}

export function unlockEmployee(id: number): Promise<AdminEmployeeView> {
  return apiRequest(`${BASE}/employees/${id}/unlock`, { method: 'POST', auth: true })
}

/** The result carries the new PIN: the caller shows it once and drops it. */
export function resetPin(id: number, reason: { reasonCode?: PinResetReason; note?: string }): Promise<PinResetResult> {
  return apiRequest(`${BASE}/employees/${id}/pin-reset`, { method: 'POST', auth: true, json: reason })
}

export function fetchSchedule(id: number): Promise<ScheduleDay[]> {
  return apiRequest(`${BASE}/employees/${id}/schedule`, { auth: true })
}

export function saveSchedule(id: number, expected: ScheduleDay[], days: ScheduleDay[]): Promise<ScheduleDay[]> {
  return apiRequest(`${BASE}/employees/${id}/schedule`, { method: 'PUT', auth: true, json: { expected, days } })
}
