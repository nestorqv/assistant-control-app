import { describe, expect, test } from 'vitest'
import { es, en } from '../i18n/resources'
import type { Translate } from '../i18n/i18nContext'
import {
  firstName,
  formatDuration,
  formatLongDate,
  formatSentenceDate,
  formatShortDate,
  formatShortDateTime,
  formatTime,
  localDateString,
  localDateToMs,
  toSpoken,
} from './format'

const tFor = (table: Record<string, string>): Translate => (key, vars) =>
  table[key].replace(/\{(\w+)\}/g, (_, name: string) => String(vars?.[name]))

// 2026-10-08T05:30:00Z is 23:30 on October 7 in Costa Rica (UTC-6), while the UTC day is the 8th.
const lateNight = Date.parse('2026-10-08T05:30:00Z')

describe('dates in the company zone', () => {
  test('the long date is capitalized and uses the zone, not the UTC day', () => {
    expect(formatLongDate(lateNight, 'America/Costa_Rica', 'es')).toBe('Miércoles, 7 de octubre')
    expect(formatLongDate(lateNight, 'America/Costa_Rica', 'en')).toBe('Wednesday, October 7')
    expect(formatLongDate(lateNight, 'UTC', 'es')).toBe('Jueves, 8 de octubre')
  })

  test('the sentence date is not capitalized', () => {
    expect(formatSentenceDate(Date.parse('2026-10-06T12:00:00Z'), 'UTC', 'es')).toBe('martes 6 de octubre')
  })

  test('a LocalDate never shifts a day', () => {
    expect(formatSentenceDate(localDateToMs('2026-10-06'), 'UTC', 'es')).toContain('6 de octubre')
    expect(localDateString(lateNight, 'America/Costa_Rica')).toBe('2026-10-07')
  })

  test('short dates and date-times', () => {
    expect(formatShortDate(Date.parse('2026-10-06T12:00:00Z'), 'UTC', 'es')).toBe('mar 6 oct')
    expect(formatShortDate(Date.parse('2026-10-06T12:00:00Z'), 'UTC', 'en')).toBe('Tue, Oct 6')
    expect(formatShortDateTime(Date.parse('2026-10-06T13:58:00Z'), 'America/Costa_Rica', 'en')).toBe('Tue, Oct 6, 07:58')
  })
})

describe('times', () => {
  test('HH:mm on a 24 hour clock in both languages', () => {
    const afternoon = Date.parse('2026-10-07T23:03:00Z')
    expect(formatTime(afternoon, 'America/Costa_Rica', 'es')).toBe('17:03')
    expect(formatTime(afternoon, 'America/Costa_Rica', 'en')).toBe('17:03')
    expect(formatTime(Date.parse('2026-10-07T06:00:00Z'), 'America/Costa_Rica', 'es')).toBe('00:00')
  })
})

describe('durations', () => {
  test('545 minutes is 9 h 05 min and short ones are plain minutes', () => {
    expect(formatDuration(545, tFor(es))).toBe('9 h 05 min')
    expect(formatDuration(545, tFor(en))).toBe('9 h 05 min')
    expect(formatDuration(45, tFor(es))).toBe('45 min')
    expect(formatDuration(60, tFor(es))).toBe('1 h 00 min')
    expect(formatDuration(-5, tFor(es))).toBe('0 min')
  })
})

describe('names and speech', () => {
  test('firstName is the first whitespace token', () => {
    expect(firstName('María Pérez Mora')).toBe('María')
    expect(firstName('  Ana  ')).toBe('Ana')
    expect(firstName('')).toBe('')
  })

  test('toSpoken replaces the middle dot separator by a comma', () => {
    expect(toSpoken('Fuera del área · 1112 m · en revisión')).toBe('Fuera del área, 1112 m, en revisión')
  })
})
