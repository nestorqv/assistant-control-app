import { describe, expect, test } from 'vitest'
import { ApiProblem, NetworkError, TimeoutError } from '../api/problem'
import { en, es } from '../i18n/resources'
import { CLIENT_ERROR_KEYS, describeError, ERROR_CODE_KEYS, messageKeyFor, retryAfterOf } from './errorMessages'

const t = (key: keyof typeof es, vars?: Record<string, string | number>) =>
  es[key].replace(/\{(\w+)\}/g, (_, name: string) => String(vars?.[name]))

describe('messageKeyFor', () => {
  test('every backend code maps to a key with non-empty ES and EN texts', () => {
    for (const [code, key] of Object.entries(ERROR_CODE_KEYS)) {
      expect(key).toBe(`error.${code}`)
      expect(es[key].trim(), `es ${key}`).not.toBe('')
      expect(en[key].trim(), `en ${key}`).not.toBe('')
      expect(en[key], `en differs ${key}`).not.toBe(es[key])
      expect(messageKeyFor(new ApiProblem(400, code))).toBe(key)
    }
  })

  test('client kinds', () => {
    expect(messageKeyFor(new NetworkError())).toBe('error.network')
    expect(messageKeyFor(new TimeoutError())).toBe('error.timeout')
    expect(messageKeyFor(new ApiProblem(401))).toBe('error.sessionExpired')
    expect(messageKeyFor(new ApiProblem(403))).toBe('error.forbidden')
    for (const key of CLIENT_ERROR_KEYS) {
      expect(es[key].trim()).not.toBe('')
      expect(en[key]).not.toBe(es[key])
    }
  })

  test('unknown codes and unknown errors fall back to error.unknown', () => {
    expect(messageKeyFor(new ApiProblem(418, 'TEAPOT'))).toBe('error.unknown')
    expect(messageKeyFor(new ApiProblem(500))).toBe('error.unknown')
    expect(messageKeyFor(new Error('x'))).toBe('error.unknown')
    expect(messageKeyFor('x')).toBe('error.unknown')
  })

  test('the server detail is never used: the text comes from the resources', () => {
    const problem = new ApiProblem(401, 'INVALID_CREDENTIALS')
    expect(describeError(problem, t)).toBe(es['error.INVALID_CREDENTIALS'])
  })
})

describe('describeError for rate limits', () => {
  test('Retry-After of 300 s is "5 min"', () => {
    const text = describeError(new ApiProblem(429, 'TOO_MANY_ATTEMPTS', 300), t)
    expect(text).toContain('5 min')
  })

  test('minutes round up and short waits use seconds', () => {
    expect(describeError(new ApiProblem(429, 'TOO_MANY_ATTEMPTS', 61), t)).toContain('2 min')
    expect(describeError(new ApiProblem(429, 'TOO_MANY_ATTEMPTS', 45), t)).toContain('45 s')
  })

  test('without Retry-After the generic text is used', () => {
    expect(describeError(new ApiProblem(429, 'TOO_MANY_ATTEMPTS'), t)).toBe(es['error.TOO_MANY_ATTEMPTS'])
  })

  test('retryAfterOf only reads 429s', () => {
    expect(retryAfterOf(new ApiProblem(429, 'TOO_MANY_ATTEMPTS', 30))).toBe(30)
    expect(retryAfterOf(new ApiProblem(503, 'SERVER_BUSY', 30))).toBeUndefined()
  })
})

describe('Stage 4b codes', () => {
  test('RATE_LIMITED with Retry-After 600 is "10 min" and without it the generic text', () => {
    expect(describeError(new ApiProblem(429, 'RATE_LIMITED', 600), t)).toContain('10 min')
    expect(describeError(new ApiProblem(429, 'RATE_LIMITED'), t)).toBe(es['error.RATE_LIMITED'])
  })

  test('RECORD_CHANGED and ADMIN_PIN_RESET_NOT_ALLOWED use "usted" in Spanish and exist in English', () => {
    for (const key of ['error.RECORD_CHANGED', 'error.ADMIN_PIN_RESET_NOT_ALLOWED'] as const) {
      expect(es[key]).not.toMatch(/\b(puedes|tu|tus)\b/i)
      expect(en[key]).not.toBe(es[key])
    }
    expect(es['error.RECORD_CHANGED']).toContain('Recargue')
    expect(es['error.ADMIN_PIN_RESET_NOT_ALLOWED']).toContain('No se puede restablecer')
    expect(messageKeyFor(new ApiProblem(409, 'RECORD_CHANGED'))).toBe('error.RECORD_CHANGED')
  })
})
