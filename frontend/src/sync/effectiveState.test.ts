import { describe, expect, test } from 'vitest'
import type { OutboxItem } from '../storage/db'
import { baselineJpegBytes, entryResponse, entrySummary, today } from '../test/fixtures'
import { effectiveState, freshenToday } from './effectiveState'

const NOW = Date.parse('2026-10-07T20:00:00Z')

function pending(type: 'CLOCK_IN' | 'CLOCK_OUT', deviceTime = '2026-10-07T13:58:00.000Z'): OutboxItem {
  return {
    seq: 1,
    employeeId: 7,
    type,
    clientUuid: `uuid-${type}`,
    deviceTime,
    photo: baselineJpegBytes().buffer as ArrayBuffer,
    photoType: 'image/jpeg',
    createdAt: deviceTime,
    attempts: 0,
    state: 'pending',
  }
}

describe('from today alone', () => {
  test('CLOCK_IN, nothing pending: offer the clock-in', () => {
    const s = effectiveState(today(), [], null, NOW)
    expect(s).toMatchObject({ kind: 'beforeClockIn', action: 'CLOCK_IN', buttonDisabled: false, clockIn: null, clockOut: null })
  })

  test('CLOCK_OUT: in shift with the server entry', () => {
    const s = effectiveState(today({ nextAction: 'CLOCK_OUT', clockIn: entrySummary() }), [], null, NOW)
    expect(s).toMatchObject({ kind: 'inShift', action: 'CLOCK_OUT', buttonDisabled: false })
    expect(s.clockIn).toMatchObject({ pending: false, timeMs: Date.parse('2026-10-07T13:58:00Z') })
    expect(s.clockIn?.outcome?.insideGeofence).toBe(true)
  })

  test('NONE: day complete, button disabled, both entries', () => {
    const s = effectiveState(
      today({ nextAction: 'NONE', clockIn: entrySummary(), clockOut: entrySummary({ id: 2, serverTime: '2026-10-07T23:03:00Z' }) }),
      [],
      null,
      NOW,
    )
    expect(s).toMatchObject({ kind: 'dayComplete', action: 'DONE', buttonDisabled: true })
    expect(s.clockIn).not.toBeNull()
    expect(s.clockOut).not.toBeNull()
  })

  test('NO_BRANCH_ASSIGNED disables the button', () => {
    const s = effectiveState(today({ blockedReason: 'NO_BRANCH_ASSIGNED' }), [], null, NOW)
    expect(s).toMatchObject({ kind: 'noBranch', buttonDisabled: true })
  })

  test('no today and nothing pending: unavailable', () => {
    expect(effectiveState(null, [], null, NOW)).toMatchObject({ kind: 'unavailable', buttonDisabled: true })
  })
})

describe('pending items on top of today', () => {
  test('a pending CLOCK_IN shows the pending time and offers the clock-out', () => {
    const s = effectiveState(today(), [pending('CLOCK_IN')], null, NOW)
    expect(s).toMatchObject({ kind: 'queuedIn', action: 'CLOCK_OUT', buttonDisabled: false })
    expect(s.clockIn).toMatchObject({ pending: true, outcome: null })
  })

  test('a pending CLOCK_OUT disables the button', () => {
    const s = effectiveState(
      today({ nextAction: 'CLOCK_OUT', clockIn: entrySummary() }),
      [pending('CLOCK_OUT', '2026-10-07T23:03:00.000Z')],
      null,
      NOW,
    )
    expect(s).toMatchObject({ kind: 'queuedOut', action: 'DONE', buttonDisabled: true })
    expect(s.clockOut?.pending).toBe(true)
    expect(s.clockIn?.pending).toBe(false)
  })

  test('both pending (offline pair): the clock-out wins', () => {
    const s = effectiveState(today(), [pending('CLOCK_IN'), pending('CLOCK_OUT')], null, NOW)
    expect(s.kind).toBe('queuedOut')
  })

  test('a stale today never offers CLOCK_IN while a CLOCK_IN is pending', () => {
    // today still says CLOCK_IN because the server has not seen the queued entry yet.
    const s = effectiveState(today({ nextAction: 'CLOCK_IN' }), [pending('CLOCK_IN')], null, NOW)
    expect(s.action).not.toBe('CLOCK_IN')
  })

  test('pending items without any today (cold open) still produce a state', () => {
    expect(effectiveState(null, [pending('CLOCK_IN')], null, NOW).kind).toBe('queuedIn')
  })
})

describe('server answers kept in memory', () => {
  test('the POST response fills the result until today has the entry', () => {
    const response = entryResponse({ insideGeofence: false, distanceMeters: 1112, status: 'PENDING_REVIEW', reviewReasons: ['OUTSIDE_GEOFENCE'] })
    const s = effectiveState(today({ nextAction: 'CLOCK_OUT' }), [], null, NOW, { CLOCK_IN: response })
    expect(s.clockIn?.outcome).toMatchObject({ insideGeofence: false, distanceMeters: 1112 })
  })

  test('a response from another work date is ignored', () => {
    const yesterday = entryResponse({ workDate: '2026-10-06' })
    const s = effectiveState(today(), [], null, NOW, { CLOCK_IN: yesterday })
    expect(s.clockIn).toBeNull()
  })

  test('today wins over an older response for the same entry', () => {
    const s = effectiveState(
      today({ nextAction: 'CLOCK_OUT', clockIn: entrySummary({ distanceMeters: 20 }) }),
      [],
      null,
      NOW,
      { CLOCK_IN: entryResponse({ distanceMeters: 99 }) },
    )
    expect(s.clockIn?.outcome?.distanceMeters).toBe(20)
  })
})

describe('overnight last shift', () => {
  const completed = { workDate: '2026-10-06', clockInAt: '2026-10-07T04:01:00Z', clockOutAt: '2026-10-07T12:03:00Z' }

  test('shown when the shift ended less than 12 hours ago', () => {
    const s = effectiveState(today(), [], completed, Date.parse('2026-10-07T20:00:00Z'))
    expect(s.kind).toBe('beforeClockIn')
    expect(s.lastShift).toEqual(completed)
  })

  test('not shown after 12 hours', () => {
    expect(effectiveState(today(), [], completed, Date.parse('2026-10-08T00:04:00Z')).lastShift).toBeNull()
  })

  test('not shown while in shift', () => {
    const s = effectiveState(today({ nextAction: 'CLOCK_OUT', clockIn: entrySummary() }), [], completed, NOW)
    expect(s.lastShift).toBeNull()
  })
})

describe('freshenToday', () => {
  test('a day-complete snapshot from yesterday offers a clock-in again', () => {
    const old = today({ workDate: '2026-10-06', nextAction: 'NONE', clockIn: entrySummary(), clockOut: entrySummary() })
    const fresh = freshenToday(old, '2026-10-07')
    expect(fresh).toMatchObject({ workDate: '2026-10-07', nextAction: 'CLOCK_IN', clockIn: null, clockOut: null })
  })

  test('an ongoing shift (CLOCK_OUT) and a current-day snapshot are kept', () => {
    const open = today({ workDate: '2026-10-06', nextAction: 'CLOCK_OUT', clockIn: entrySummary() })
    expect(freshenToday(open, '2026-10-07')).toBe(open)
    const sameDay = today({ nextAction: 'NONE' })
    expect(freshenToday(sameDay, '2026-10-07')).toBe(sameDay)
  })
})

describe('QA edge cases', () => {
  test('a pending CLOCK_OUT disables the button even when today still offers CLOCK_IN (stale today)', () => {
    const s = effectiveState(today({ nextAction: 'CLOCK_IN' }), [pending('CLOCK_OUT')], null, NOW)
    expect(s).toMatchObject({ kind: 'queuedOut', action: 'DONE', buttonDisabled: true })
  })

  test('a pending CLOCK_IN on top of a day-complete today (replayed lost response) still offers only the clock-out', () => {
    const s = effectiveState(today({ nextAction: 'NONE' }), [pending('CLOCK_IN')], null, NOW)
    expect(s).toMatchObject({ kind: 'queuedIn', action: 'CLOCK_OUT', buttonDisabled: false })
  })

  test('the pending item shows the phone deviceTime, not the server time of the same entry', () => {
    const s = effectiveState(
      today({ nextAction: 'CLOCK_OUT', clockIn: entrySummary({ serverTime: '2026-10-07T14:30:00Z' }) }),
      [pending('CLOCK_IN', '2026-10-07T13:58:00.000Z')],
      null,
      NOW,
    )
    expect(s.clockIn).toMatchObject({ pending: true, timeMs: Date.parse('2026-10-07T13:58:00Z'), outcome: null })
  })

  test('the 12 hour overnight window is exclusive at exactly 12 hours', () => {
    const clockOutAt = '2026-10-07T08:00:00Z'
    const last = { workDate: '2026-10-06', clockInAt: '2026-10-06T22:00:00Z', clockOutAt }
    const justBefore = effectiveState(today(), [], last, Date.parse(clockOutAt) + 12 * 3_600_000 - 1)
    const exactly = effectiveState(today(), [], last, Date.parse(clockOutAt) + 12 * 3_600_000)
    expect(justBefore.lastShift).not.toBeNull()
    expect(exactly.lastShift).toBeNull()
  })

  test('freshenToday keeps a same-day NONE and rolls a past-day NONE to a clean CLOCK_IN', () => {
    const done = today({ workDate: '2026-10-07', nextAction: 'NONE', workedMinutes: 500, clockIn: entrySummary(), clockOut: entrySummary({ id: 2 }) })
    expect(freshenToday(done, '2026-10-07')).toBe(done)
    expect(freshenToday(done, '2026-10-08')).toMatchObject({
      workDate: '2026-10-08',
      nextAction: 'CLOCK_IN',
      clockIn: null,
      clockOut: null,
      workedMinutes: null,
    })
  })
})
