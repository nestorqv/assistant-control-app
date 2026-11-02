import { expect, test } from 'vitest'
import { dashboard, row } from '../test/adminFixtures'
import { createAction } from './rows'

test('the missing clock-out shortcut is offered only when the shift is not in progress', () => {
  const missing = row('A', { dayStatus: 'MISSING_CLOCK_OUT', inProgress: false })
  const open = row('B', { dayStatus: 'MISSING_CLOCK_OUT', inProgress: true })
  expect(createAction(missing)).toBe('CLOCK_OUT')
  expect(createAction(open)).toBeNull()
  // An overnight shift still open the next morning is "on shift": no shortcut.
  expect(createAction(row('C', { dayStatus: 'ON_TIME', inProgress: true }))).toBeNull()
  expect(createAction(row('D', { dayStatus: 'NOT_CLOCKED' }))).toBe('CLOCK_IN')
  expect(dashboard().rows.length).toBeGreaterThan(0)
})
