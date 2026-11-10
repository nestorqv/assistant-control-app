import { ApiProblem, NetworkError, TimeoutError } from '../api/problem'
import type { Translate } from '../i18n/i18nContext'
import type { MessageKey } from '../i18n/resources'

/** One key per backend ErrorCode. src/api/contract.test.ts parses ErrorCode.java to catch drift. */
export const ERROR_CODE_KEYS = {
  INVALID_CREDENTIALS: 'error.INVALID_CREDENTIALS',
  TOO_MANY_ATTEMPTS: 'error.TOO_MANY_ATTEMPTS',
  INVALID_REQUEST: 'error.INVALID_REQUEST',
  METHOD_NOT_ALLOWED: 'error.METHOD_NOT_ALLOWED',
  NOT_ACCEPTABLE: 'error.NOT_ACCEPTABLE',
  UNSUPPORTED_MEDIA_TYPE: 'error.UNSUPPORTED_MEDIA_TYPE',
  NOT_FOUND: 'error.NOT_FOUND',
  INTERNAL_ERROR: 'error.INTERNAL_ERROR',
  INVALID_PIN: 'error.INVALID_PIN',
  CONSENT_REQUIRED: 'error.CONSENT_REQUIRED',
  NO_BRANCH_ASSIGNED: 'error.NO_BRANCH_ASSIGNED',
  ALREADY_CLOCKED_IN: 'error.ALREADY_CLOCKED_IN',
  NOT_CLOCKED_IN: 'error.NOT_CLOCKED_IN',
  SHIFT_ALREADY_COMPLETED: 'error.SHIFT_ALREADY_COMPLETED',
  CLIENT_UUID_CONFLICT: 'error.CLIENT_UUID_CONFLICT',
  PHOTO_REQUIRED: 'error.PHOTO_REQUIRED',
  INVALID_PHOTO: 'error.INVALID_PHOTO',
  PAYLOAD_TOO_LARGE: 'error.PAYLOAD_TOO_LARGE',
  SERVER_BUSY: 'error.SERVER_BUSY',
  ENTRY_CHANGED: 'error.ENTRY_CHANGED',
  INVALID_STATUS_CHANGE: 'error.INVALID_STATUS_CHANGE',
  ENTRY_ALREADY_EXISTS: 'error.ENTRY_ALREADY_EXISTS',
  CLOCK_IN_REQUIRED: 'error.CLOCK_IN_REQUIRED',
  INVALID_ENTRY_TIME: 'error.INVALID_ENTRY_TIME',
  EMPLOYEE_CODE_TAKEN: 'error.EMPLOYEE_CODE_TAKEN',
  BRANCH_NAME_TAKEN: 'error.BRANCH_NAME_TAKEN',
  BRANCH_REQUIRED: 'error.BRANCH_REQUIRED',
  BRANCH_INACTIVE: 'error.BRANCH_INACTIVE',
  BRANCH_IN_USE: 'error.BRANCH_IN_USE',
  LAST_ADMIN: 'error.LAST_ADMIN',
  SELF_CHANGE_NOT_ALLOWED: 'error.SELF_CHANGE_NOT_ALLOWED',
  DATE_RANGE_TOO_LARGE: 'error.DATE_RANGE_TOO_LARGE',
  REPORT_TOO_LARGE: 'error.REPORT_TOO_LARGE',
  RATE_LIMITED: 'error.RATE_LIMITED',
  RECORD_CHANGED: 'error.RECORD_CHANGED',
  ADMIN_PIN_RESET_NOT_ALLOWED: 'error.ADMIN_PIN_RESET_NOT_ALLOWED',
} as const satisfies Record<string, MessageKey>

export const CLIENT_ERROR_KEYS = [
  'error.network',
  'error.timeout',
  'error.sessionExpired',
  'error.forbidden',
  'error.unknown',
] as const satisfies readonly MessageKey[]

/** Maps a thrown value to the translated message key. Unknown codes fall back to error.unknown. */
export function messageKeyFor(error: unknown): MessageKey {
  if (error instanceof NetworkError) return 'error.network'
  if (error instanceof TimeoutError) return 'error.timeout'
  if (error instanceof ApiProblem) {
    const known = error.code !== undefined && error.code in ERROR_CODE_KEYS
    if (known) return ERROR_CODE_KEYS[error.code as keyof typeof ERROR_CODE_KEYS]
    if (error.status === 401) return 'error.sessionExpired'
    if (error.status === 403) return 'error.forbidden'
    if (error.status === 429) return 'error.TOO_MANY_ATTEMPTS'
  }
  return 'error.unknown'
}

/** The text to show. Any 429 with Retry-After becomes "try again in N min" (rounded up) or in seconds. */
export function describeError(error: unknown, t: Translate): string {
  const key = messageKeyFor(error)
  const limited = key === 'error.TOO_MANY_ATTEMPTS' || key === 'error.RATE_LIMITED'
  if (limited && error instanceof ApiProblem && error.status === 429 && error.retryAfterSeconds !== undefined) {
    const seconds = error.retryAfterSeconds
    return seconds >= 60
      ? t('error.tooManyAttemptsMinutes', { minutes: Math.ceil(seconds / 60) })
      : t('error.tooManyAttemptsSeconds', { seconds: Math.max(1, seconds) })
  }
  return t(key)
}

export function retryAfterOf(error: unknown): number | undefined {
  return error instanceof ApiProblem && error.status === 429 ? error.retryAfterSeconds : undefined
}
