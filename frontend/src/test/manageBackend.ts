import { configure } from '@testing-library/react'
import { vi } from 'vitest'
import type { AdminEmployeeDetail, AdminEmployeeView, BranchView, ScheduleDay } from '../admin/api/adminTypes'
import { jsonResponse } from './fixtures'
import { stubFetch } from './appHarness'

configure({ asyncUtilTimeout: 5000 })
vi.setConfig({ testTimeout: 20_000 })

// The names, codes and PINs below are made up. The signed-in admin (adminIdentity) is employee 9.

export function employeeView(over: Partial<AdminEmployeeView> = {}): AdminEmployeeView {
  return {
    id: 20,
    code: 'ABC-123',
    name: 'Nombre Apellido',
    jobTitle: 'Cajera',
    role: 'EMPLOYEE',
    active: true,
    branchId: 1,
    branchName: 'Sucursal Uno',
    branchActive: true,
    consentGiven: true,
    locked: false,
    lockedUntil: null,
    hasSchedule: true,
    version: 3,
    ...over,
  }
}

export function detailView(over: Partial<AdminEmployeeDetail> = {}): AdminEmployeeDetail {
  return { ...employeeView(), lastPinReset: null, ...over }
}

export function branchView(over: Partial<BranchView> = {}): BranchView {
  return { id: 1, name: 'Sucursal Uno', latitude: 9.9325, longitude: -84.0796, radiusMeters: 100, active: true, activeEmployees: 2, version: 0, ...over }
}

export const monday: ScheduleDay = { dayOfWeek: 1, startTime: '08:00', endTime: '17:00', graceMinutes: 5 }

export type Call = { url: string; method: string; body: unknown }

export type ManageOptions = {
  employees?: AdminEmployeeView[]
  branches?: BranchView[]
  detail?: AdminEmployeeDetail
  schedule?: ScheduleDay[]
  /** answered first; return undefined to fall through to the defaults */
  routes?: (url: string, method: string, body: unknown) => Response | undefined
}

/** A fake management API. Every non-GET call is recorded in `writes`. */
export function manageBackend(options: ManageOptions = {}) {
  const writes: Call[] = []
  const employees = options.employees ?? [employeeView()]
  const branches = options.branches ?? [branchView(), branchView({ id: 2, name: 'Sucursal Dos', activeEmployees: 0 })]
  const detail = options.detail ?? detailView()
  const stub = stubFetch((url, init) => {
    const method = init?.method ?? 'GET'
    const body = init?.body ? JSON.parse(String(init.body)) : null
    if (method !== 'GET') writes.push({ url, method, body })
    const custom = options.routes?.(url, method, body)
    if (custom) return custom
    if (url === '/api/health') return jsonResponse({ status: 'UP', service: 'x', serverTime: '2026-10-13T18:00:00Z' })
    if (url === '/api/admin/review-queue/count') return jsonResponse({ pending: 0, today: '2026-10-07', timeZone: 'America/Costa_Rica' })
    if (method === 'GET' && url === '/api/admin/employees') return jsonResponse(employees)
    if (method === 'GET' && url === '/api/admin/branches') return jsonResponse(branches)
    if (method === 'GET' && /^\/api\/admin\/employees\/\d+$/.test(url)) return jsonResponse(detail)
    if (method === 'GET' && /^\/api\/admin\/employees\/\d+\/schedule$/.test(url)) return jsonResponse(options.schedule ?? [monday])
    return undefined
  })
  return { ...stub, writes }
}
