import type { IsoDay, ScheduleDay } from '../api/adminTypes'

/** One row of the editor. The inputs keep their values while the day is unchecked, so re-checking restores them. */
export type DayRow = { checked: boolean; start: string; end: string; grace: string }

export const DEFAULT_ROW: DayRow = { checked: false, start: '08:00', end: '17:00', grace: '5' }
export const DAYS: readonly IsoDay[] = [1, 2, 3, 4, 5, 6, 7]

const hhmm = (time: string) => time.slice(0, 5)

export function gridFromWeek(week: ScheduleDay[]): DayRow[] {
  return DAYS.map((day) => {
    const found = week.find((d) => d.dayOfWeek === day)
    return found
      ? { checked: true, start: hhmm(found.startTime), end: hhmm(found.endTime), grace: String(found.graceMinutes) }
      : { ...DEFAULT_ROW }
  })
}

/** The worked days only, in ISO order, with times as HH:mm. Rows that are not valid numbers are skipped. */
export function weekFromGrid(grid: DayRow[]): ScheduleDay[] {
  const week: ScheduleDay[] = []
  grid.forEach((row, index) => {
    if (row.checked) {
      week.push({ dayOfWeek: DAYS[index], startTime: row.start, endTime: row.end, graceMinutes: Number(row.grace) })
    }
  })
  return week
}

export function normalizeWeek(week: ScheduleDay[]): string {
  return JSON.stringify(
    [...week]
      .sort((a, b) => a.dayOfWeek - b.dayOfWeek)
      .map((d) => [d.dayOfWeek, hhmm(d.startTime), hhmm(d.endTime), d.graceMinutes]),
  )
}

export type RowProblem = 'time' | 'same' | 'grace'

/** The checks done on save; nothing is blocked while typing. */
export function rowProblem(row: DayRow): RowProblem | null {
  if (!row.checked) return null
  if (row.start === '' || row.end === '') return 'time'
  if (row.start === row.end) return 'same'
  if (!/^\d+$/.test(row.grace.trim()) || Number(row.grace) > 240) return 'grace'
  return null
}

/** An end earlier than the start means the shift crosses midnight. */
export function isOvernight(row: DayRow): boolean {
  return row.checked && row.start !== '' && row.end !== '' && row.end < row.start
}
