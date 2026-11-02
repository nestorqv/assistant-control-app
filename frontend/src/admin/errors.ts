import { ApiProblem } from '../api/problem'
import { describeError } from '../errors/errorMessages'
import type { AdminTranslate } from './adminI18n'

/** Same texts as the employee app, except SERVER_BUSY: admin writes are never retried automatically. */
export function adminErrorText(error: unknown, t: AdminTranslate): string {
  if (error instanceof ApiProblem && error.code === 'SERVER_BUSY') return t('admin.error.serverBusy')
  return describeError(error, t)
}

export function problemCode(error: unknown): string | undefined {
  return error instanceof ApiProblem ? error.code : undefined
}

export function problemStatus(error: unknown): number | undefined {
  return error instanceof ApiProblem ? error.status : undefined
}
