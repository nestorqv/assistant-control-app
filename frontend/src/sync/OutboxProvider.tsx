import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { postTimeEntry } from '../api/timeEntries'
import type { TimeEntryResponse, TimeEntryType } from '../api/types'
import { getSession, isTokenUsable, requireConsent, requireReauth } from '../auth/session'
import { useSession } from '../auth/useSession'
import { newClientUuid } from '../capture/uuid'
import type { OutboxItem } from '../storage/db'
import * as outbox from '../storage/outbox'
import { deviceNow } from '../time/deviceClock'
import { useServerClock } from '../time/useServerClock'
import { flushOnce } from './flush'
import { OutboxContext, type Capture, type DeliveredEntry, type OutboxValue } from './outboxContext'

function currentEmployeeId(): number | null {
  return getSession().identity?.employeeId ?? null
}

/**
 * Owns the IndexedDB queue and the triggers that drain it: after an enqueue, on `online`, on app open,
 * on visibilitychange, after a re-auth and on the backoff timer. Delivery rules live in flush.ts.
 */
export function OutboxProvider({ children }: { children: ReactNode }) {
  const session = useSession()
  const { clock } = useServerClock()
  const employeeId = session.identity?.employeeId ?? null

  const [items, setItems] = useState<OutboxItem[]>([])
  const [flushing, setFlushing] = useState(false)
  const [online, setOnline] = useState(() => navigator.onLine)
  const [networkProblem, setNetworkProblem] = useState(false)
  const [lastResults, setLastResults] = useState<Partial<Record<TimeEntryType, TimeEntryResponse>>>({})
  const [syncVersion, setSyncVersion] = useState(0)
  const [lastDelivered, setLastDelivered] = useState<DeliveredEntry | null>(null)
  const timer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined)
  const deliveredCount = useRef(0)
  const mounted = useRef(true)

  // Per-session state: everything the previous person produced is dropped the moment the identity changes
  // (logout, "not you", sign-in as someone else). Adjusting state during render is React's documented pattern.
  const [owner, setOwner] = useState(employeeId)
  if (owner !== employeeId) {
    setOwner(employeeId)
    setItems([])
    setFlushing(false)
    setNetworkProblem(false)
    setLastResults({})
    setLastDelivered(null)
    setSyncVersion((n) => n + 1)
  }

  // A backoff timer armed for the previous session must never fire for the next one.
  useEffect(() => {
    return () => clearTimeout(timer.current)
  }, [employeeId])

  const refresh = useCallback(async () => {
    const next = employeeId === null ? [] : await outbox.listItems(employeeId)
    // An answer for a previous employee must not land on the next one's screen.
    if (mounted.current && currentEmployeeId() === employeeId) setItems(next)
  }, [employeeId])

  const runFlush = useCallback(async () => {
    if (employeeId === null) return
    if (currentEmployeeId() !== employeeId) return
    setFlushing(true)
    try {
      const result = await flushOnce({
        employeeId,
        currentIdentity: () => {
          const identity = getSession().identity
          return identity ? { employeeId: identity.employeeId, companyCode: identity.companyCode } : null
        },
        outbox,
        post: postTimeEntry,
        online: () => navigator.onLine,
        tokenUsable: () => !getSession().needsReauth && isTokenUsable(clock.getEstimate().ms),
        requireReauth,
        sentAt: () => deviceNow(clock.getEstimate().ms),
        onSent: (item, response, status) => {
          // A POST still in flight when the person signed out finishes afterwards: its result is not shown to anyone else.
          if (currentEmployeeId() !== item.employeeId) return
          deliveredCount.current += 1
          setLastResults((prev) => ({ ...prev, [response.type]: response }))
          setLastDelivered({ id: deliveredCount.current, response, replayed: status === 200 })
        },
      })
      if (!mounted.current) return
      await refresh()
      if (result.sent > 0 || result.outcome === 'failed') setSyncVersion((n) => n + 1)
      if (result.sent > 0) setNetworkProblem(false)
      if (result.outcome === 'consent') requireConsent()
      if (result.outcome === 'backoff') setNetworkProblem(result.networkError === true)
      clearTimeout(timer.current)
      if (result.retryInSeconds !== undefined) {
        timer.current = setTimeout(() => void runFlush(), result.retryInSeconds * 1000)
      }
    } finally {
      if (mounted.current) setFlushing(false)
    }
  }, [employeeId, clock, refresh])

  useEffect(() => {
    mounted.current = true
    return () => {
      mounted.current = false
      clearTimeout(timer.current)
    }
  }, [])

  // App open, identity change, and a fresh token after a re-auth.
  useEffect(() => {
    void refresh().then(() => runFlush())
  }, [refresh, runFlush, session.token])

  useEffect(() => {
    const goOnline = () => {
      setOnline(true)
      void runFlush()
    }
    const goOffline = () => setOnline(false)
    const onVisibility = () => {
      if (document.visibilityState === 'visible') void runFlush()
    }
    window.addEventListener('online', goOnline)
    window.addEventListener('offline', goOffline)
    document.addEventListener('visibilitychange', onVisibility)
    return () => {
      window.removeEventListener('online', goOnline)
      window.removeEventListener('offline', goOffline)
      document.removeEventListener('visibilitychange', onVisibility)
    }
  }, [runFlush])

  const enqueueCapture = useCallback(
    async (capture: Capture) => {
      const identity = getSession().identity
      if (employeeId === null || !identity || identity.employeeId !== employeeId) throw new Error('No identity')
      const now = deviceNow(clock.getEstimate().ms)
      const seq = await outbox.enqueue({
        employeeId,
        companyCode: identity.companyCode,
        type: capture.type,
        clientUuid: newClientUuid(),
        deviceTime: now.toISOString(),
        lat: capture.fix?.lat,
        lng: capture.fix?.lng,
        accuracy: capture.fix?.accuracy,
        photo: capture.photo,
        photoType: capture.photoType,
        createdAt: now.toISOString(),
      })
      await refresh()
      void runFlush()
      const stored = (await outbox.listItems(employeeId)).find((i) => i.seq === seq)
      return stored as OutboxItem
    },
    [employeeId, clock, refresh, runFlush],
  )

  const acknowledgeFailure = useCallback(
    async (seq: number) => {
      await outbox.remove(seq)
      await refresh()
      setSyncVersion((n) => n + 1)
      void runFlush()
    },
    [refresh, runFlush],
  )

  const value = useMemo<OutboxValue>(
    () => ({
      pending: items.filter((i) => i.state === 'pending'),
      failed: items.filter((i) => i.state === 'failed'),
      flushing,
      online,
      networkProblem,
      lastResults,
      syncVersion,
      lastDelivered,
      enqueueCapture,
      acknowledgeFailure,
      flushNow: () => void runFlush(),
    }),
    [items, flushing, online, networkProblem, lastResults, syncVersion, lastDelivered, enqueueCapture, acknowledgeFailure, runFlush],
  )

  return <OutboxContext value={value}>{children}</OutboxContext>
}
