import type { EntryOutcome, TimeEntryResponse, TodayResponse } from '../api/types'
import type { LastCompleted, OutboxItem } from '../storage/db'

export const OVERNIGHT_LAST_SHIFT_HOURS = 12

export type EntryView = {
  /** epoch ms: the phone's deviceTime while pending, else the server time */
  timeMs: number
  pending: boolean
  /** the server's answer; null while pending */
  outcome: EntryOutcome | null
}

export type EffectiveKind =
  | 'unavailable'
  | 'noBranch'
  | 'beforeClockIn'
  | 'inShift'
  | 'queuedIn'
  | 'queuedOut'
  | 'dayComplete'

export type EffectiveState = {
  kind: EffectiveKind
  action: 'CLOCK_IN' | 'CLOCK_OUT' | 'DONE'
  buttonDisabled: boolean
  clockIn: EntryView | null
  clockOut: EntryView | null
  /** previous overnight shift to mention, when it ended less than 12 h ago */
  lastShift: LastCompleted | null
}

type LastResults = Partial<Record<'CLOCK_IN' | 'CLOCK_OUT', TimeEntryResponse>>

/**
 * A snapshot from an earlier day cannot say "day complete" for today: offer a clock-in again.
 * An ongoing overnight shift (CLOCK_OUT) is kept.
 */
export function freshenToday(today: TodayResponse, todayLocalDate: string): TodayResponse {
  if (today.workDate === todayLocalDate || today.nextAction !== 'NONE') return today
  return { ...today, workDate: todayLocalDate, nextAction: 'CLOCK_IN', clockIn: null, clockOut: null, workedMinutes: null }
}

function toView(
  pending: OutboxItem | undefined,
  fromToday: EntryOutcome | null,
  fromResponse: TimeEntryResponse | undefined,
): EntryView | null {
  if (pending) return { timeMs: Date.parse(pending.deviceTime), pending: true, outcome: null }
  const outcome = fromToday ?? fromResponse ?? null
  return outcome ? { timeMs: Date.parse(outcome.serverTime), pending: false, outcome } : null
}

/**
 * Pending items are applied on top of `today`: a queued clock-in can never be offered again because
 * the server has not seen it yet, and a late `today` never rolls the button back.
 */
export function effectiveState(
  today: TodayResponse | null,
  pendingItems: OutboxItem[],
  lastCompleted: LastCompleted | null,
  nowMs: number,
  lastResults: LastResults = {},
): EffectiveState {
  // A POST response from another work date (e.g. last night's shift) is not today's entry.
  const sameDay = (r?: TimeEntryResponse) => (r && (!today || r.workDate === today.workDate) ? r : undefined)
  const pendingIn = pendingItems.find((i) => i.type === 'CLOCK_IN')
  const pendingOut = pendingItems.find((i) => i.type === 'CLOCK_OUT')
  const base = {
    clockIn: toView(pendingIn, today?.clockIn ?? null, sameDay(lastResults.CLOCK_IN)),
    clockOut: toView(pendingOut, today?.clockOut ?? null, sameDay(lastResults.CLOCK_OUT)),
    lastShift: null as LastCompleted | null,
  }

  if (!today && !pendingIn && !pendingOut) {
    return { kind: 'unavailable', action: 'CLOCK_IN', buttonDisabled: true, ...base }
  }
  if (pendingOut) return { kind: 'queuedOut', action: 'DONE', buttonDisabled: true, ...base }
  if (pendingIn) return { kind: 'queuedIn', action: 'CLOCK_OUT', buttonDisabled: false, ...base }
  if (!today) return { kind: 'unavailable', action: 'CLOCK_IN', buttonDisabled: true, ...base }

  if (today.blockedReason === 'NO_BRANCH_ASSIGNED') {
    return { kind: 'noBranch', action: 'CLOCK_IN', buttonDisabled: true, ...base }
  }
  if (today.nextAction === 'CLOCK_OUT') return { kind: 'inShift', action: 'CLOCK_OUT', buttonDisabled: false, ...base }
  if (today.nextAction === 'NONE') return { kind: 'dayComplete', action: 'DONE', buttonDisabled: true, ...base }

  const recent =
    lastCompleted && nowMs - Date.parse(lastCompleted.clockOutAt) < OVERNIGHT_LAST_SHIFT_HOURS * 3_600_000
  return {
    kind: 'beforeClockIn',
    action: 'CLOCK_IN',
    buttonDisabled: false,
    ...base,
    lastShift: recent ? lastCompleted : null,
  }
}
