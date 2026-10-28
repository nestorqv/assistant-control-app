import { existsSync, readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, test } from 'vitest'
import { CLIENT_ERROR_KEYS, ERROR_CODE_KEYS } from '../errors/errorMessages'
import { en, es } from '../i18n/resources'
import { entryResponse, entrySummary, loginResponse, today } from '../test/fixtures'
import { parseInstant, parseLocalTime } from './parse'
import type { BlockedReason, NextAction, ReviewReason, Role, TimeEntryStatus } from './types'

const backend = resolve(__dirname, '../../../backend/src/main/java/cr/attendance')

/** Constants of a plain Java enum: the names before the first ; or } of the body. */
function javaEnum(file: string): string[] {
  const source = readFileSync(resolve(backend, file), 'utf8').replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/.*$/gm, '')
  const body = source.slice(source.indexOf('{') + 1)
  const end = body.search(/[;}]/)
  return body
    .slice(0, end)
    .split(',')
    .map((name) => name.trim())
    .filter(Boolean)
}

// Compile-time lists: if a union gains a member, the satisfies check below stops compiling.
const reviewReasons = ['NO_GPS', 'OUTSIDE_GEOFENCE', 'LOW_GPS_ACCURACY', 'SYNCED_LATE', 'DEVICE_CLOCK_AHEAD', 'SHIFT_TOO_LONG'] as const satisfies readonly ReviewReason[]
const nextActions = ['CLOCK_IN', 'CLOCK_OUT', 'NONE'] as const satisfies readonly NextAction[]
const statuses = ['VALID', 'PENDING_REVIEW', 'REJECTED'] as const satisfies readonly TimeEntryStatus[]
const roles = ['EMPLOYEE', 'ADMIN'] as const satisfies readonly Role[]
export const blocked = ['CONSENT_REQUIRED', 'NO_BRANCH_ASSIGNED'] as const satisfies readonly BlockedReason[]

describe.skipIf(!existsSync(backend))('drift guard against the Java enums', () => {
  test('ErrorCode.java is covered by the error map and by ES and EN texts', () => {
    const codes = javaEnum('common/ErrorCode.java')
    expect(codes.length).toBeGreaterThan(10)
    expect(Object.keys(ERROR_CODE_KEYS).sort()).toEqual([...codes].sort())
    for (const code of codes) {
      const key = ERROR_CODE_KEYS[code as keyof typeof ERROR_CODE_KEYS]
      expect(es[key], key).toBeTruthy()
      expect(en[key], key).toBeTruthy()
    }
  })

  test('ReviewReason.java matches the union and has a translation for each', () => {
    const names = javaEnum('timeentry/ReviewReason.java')
    expect([...reviewReasons]).toEqual(names)
    for (const name of names) {
      expect(es[`reviewReason.${name}` as keyof typeof es], name).toBeTruthy()
      expect(en[`reviewReason.${name}` as keyof typeof en], name).toBeTruthy()
    }
  })

  test('NextAction, TimeEntryStatus and Role match their unions', () => {
    expect([...nextActions]).toEqual(javaEnum('timeentry/NextAction.java'))
    expect([...statuses]).toEqual(javaEnum('timeentry/TimeEntryStatus.java'))
    expect([...roles]).toEqual(javaEnum('common/Role.java'))
    for (const status of statuses) expect(es[`status.${status}` as keyof typeof es]).toBeTruthy()
  })

  test('client error kinds exist in both languages', () => {
    for (const key of CLIENT_ERROR_KEYS) {
      expect(es[key]).toBeTruthy()
      expect(en[key]).toBeTruthy()
    }
  })
})

describe('fixtures follow the Java JSON', () => {
  test('they round-trip through JSON with the documented shapes', () => {
    const response = JSON.parse(JSON.stringify(entryResponse()))
    expect(Object.keys(response).sort()).toEqual(
      ['clientUuid', 'distanceMeters', 'hasPhoto', 'id', 'insideGeofence', 'lateMinutes', 'radiusMeters', 'replayed', 'reviewReasons', 'serverTime', 'skewSeconds', 'status', 'syncedLate', 'type', 'workDate'],
    )
    expect(JSON.parse(JSON.stringify(entrySummary())).hasPhoto).toBe(true)
    expect(JSON.parse(JSON.stringify(loginResponse())).tokenType).toBe('Bearer')
    // Branch coordinates are never part of today.
    expect(JSON.stringify(today())).not.toMatch(/latitude|longitude/)
  })
})

describe('parsing', () => {
  test('parseInstant trims microsecond and nanosecond fractions', () => {
    const base = Date.parse('2026-10-07T13:58:00.123Z')
    expect(parseInstant('2026-10-07T13:58:00.123456Z').getTime()).toBe(base)
    expect(parseInstant('2026-10-07T13:58:00.123456789Z').getTime()).toBe(base)
    expect(parseInstant('2026-10-07T13:58:00Z').getTime()).toBe(Date.parse('2026-10-07T13:58:00Z'))
  })

  test('parseLocalTime accepts HH:mm:ss and HH:mm', () => {
    expect(parseLocalTime('08:00:00')).toBe('08:00')
    expect(parseLocalTime('08:00')).toBe('08:00')
    expect(() => parseLocalTime('8am')).toThrow()
  })
})
