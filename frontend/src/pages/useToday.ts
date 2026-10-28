import { useCallback, useEffect, useRef, useState } from 'react'
import { getToday } from '../api/timeEntries'
import type { TodayResponse } from '../api/types'
import { getSession, requireConsent } from '../auth/session'
import { useSession } from '../auth/useSession'
import type { LastCompleted } from '../storage/db'
import { loadSnapshot, saveSnapshot } from '../storage/snapshot'
import { useOutbox } from '../sync/useOutbox'
import { useServerClock } from '../time/useServerClock'

export type TodayState = {
  today: TodayResponse | null
  /** where `today` came from: the network, or the stored snapshot (offline cold open) */
  source: 'network' | 'snapshot' | null
  lastCompleted: LastCompleted | null
  reload: () => Promise<void>
}

/**
 * `today` drives the button, but pending outbox items are applied on top of it (effectiveState).
 * It is refreshed on open, on visibility, on reconnect and after each delivery; not polled.
 * Also keeps the "last shift" from the CLOCK_OUT response (needed after an overnight shift).
 */
export function useToday(): TodayState {
  const session = useSession()
  const { online, syncVersion, lastResults } = useOutbox()
  const { clock } = useServerClock()
  const employeeId = session.identity?.employeeId ?? null

  const [today, setToday] = useState<TodayResponse | null>(null)
  const [source, setSource] = useState<TodayState['source']>(null)
  const [lastCompleted, setLastCompleted] = useState<LastCompleted | null>(null)
  const todayRef = useRef<TodayResponse | null>(null)
  const lastCompletedRef = useRef<LastCompleted | null>(null)
  const handled = useRef({ out: 0, in: 0 })

  useEffect(() => {
    todayRef.current = today
    lastCompletedRef.current = lastCompleted
  }, [today, lastCompleted])

  const persist = useCallback(
    (snapshotToday: TodayResponse, completed: LastCompleted | null) => {
      // Never write one person's data into a snapshot while someone else (or nobody) is signed in.
      if (employeeId === null || getSession().identity?.employeeId !== employeeId) return
      void saveSnapshot({
        employeeId,
        today: snapshotToday,
        lastCompleted: completed ?? undefined,
        clockOffsetMs: clock.getOffsetMs(),
        savedAt: Date.now(),
      }).catch(() => undefined)
    },
    [employeeId, clock],
  )

  // Offline cold open: show the last good state until the network answers.
  useEffect(() => {
    if (employeeId === null) {
      // Signed out: drop what belonged to the previous person.
      // oxlint-disable-next-line react/set-state-in-effect
      setToday(null)
      setSource(null)
      setLastCompleted(null)
      return
    }
    let cancelled = false
    void loadSnapshot()
      .then((snapshot) => {
        if (cancelled || !snapshot || snapshot.employeeId !== employeeId) return
        setToday((current) => current ?? snapshot.today)
        setSource((current) => current ?? 'snapshot')
        setLastCompleted((current) => current ?? snapshot.lastCompleted ?? null)
      })
      .catch(() => undefined)
    return () => {
      cancelled = true
    }
  }, [employeeId])

  const reload = useCallback(async () => {
    if (employeeId === null || !getSession().token) return
    try {
      const fresh = await getToday()
      if (getSession().identity?.employeeId !== employeeId) return
      setToday(fresh)
      setSource('network')
      if (fresh.consentPending) requireConsent()
      persist(fresh, lastCompletedRef.current)
    } catch {
      // Offline or 401 (the session store opens the PIN dialog): keep what is on screen.
    }
  }, [employeeId, persist])

  useEffect(() => {
    // Fetching is the external system here; the state is set when the response arrives.
    // oxlint-disable-next-line react/set-state-in-effect
    void reload()
  }, [reload, session.token, syncVersion, online])

  useEffect(() => {
    const onVisible = () => {
      if (document.visibilityState === 'visible') void reload()
    }
    document.addEventListener('visibilitychange', onVisible)
    return () => document.removeEventListener('visibilitychange', onVisible)
  }, [reload])

  // Last completed shift: set by a CLOCK_OUT response, cleared by a newer CLOCK_IN response.
  useEffect(() => {
    const out = lastResults.CLOCK_OUT
    const entryIn = lastResults.CLOCK_IN
    if (out && out.id !== handled.current.out) {
      handled.current.out = out.id
      if (entryIn) handled.current.in = entryIn.id
      const clockInAt =
        entryIn && entryIn.workDate === out.workDate ? entryIn.serverTime : (todayRef.current?.clockIn?.serverTime ?? null)
      if (clockInAt) {
        const completed = { workDate: out.workDate, clockInAt, clockOutAt: out.serverTime }
        setLastCompleted(completed)
        if (todayRef.current) persist(todayRef.current, completed)
      }
    } else if (entryIn && entryIn.id !== handled.current.in) {
      handled.current.in = entryIn.id
      setLastCompleted((prev) => (prev && Date.parse(entryIn.serverTime) > Date.parse(prev.clockOutAt) ? null : prev))
    }
  }, [lastResults, persist])

  return { today, source, lastCompleted, reload }
}
