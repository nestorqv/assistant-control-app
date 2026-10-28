import { apiRequest } from './client'
import type { LoginResponse } from './types'

export function login(companyCode: string, employeeCode: string, pin: string): Promise<LoginResponse> {
  return apiRequest<LoginResponse>('/api/auth/login', {
    method: 'POST',
    json: { companyCode, employeeCode, pin },
  })
}

/** Best effort: ends every session of this employee on the server. Callers ignore failures. */
export function logout(timeoutMs = 3_000): Promise<void> {
  return apiRequest<void>('/api/auth/logout', { method: 'POST', auth: true, timeoutMs })
}
