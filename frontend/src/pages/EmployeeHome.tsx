import { lazy, Suspense, useCallback, useEffect, useRef, useState } from 'react'
import { Link } from 'react-router'
import { ApiProblem } from '../api/problem'
import type { EntryOutcome, TimeEntryResponse, TimeEntryType } from '../api/types'
import { requireReauth } from '../auth/session'
import { useSession } from '../auth/useSession'
import { useAnnouncer } from '../components/announcerContext'
import { Announcer } from '../components/Announcer'
import { ClockButton } from '../components/ClockButton'
import { EntryTimes, type EntryColumn } from '../components/EntryTimes'
import { ErrorBox } from '../components/ErrorBox'
import { EvidenceChips } from '../components/EvidenceChips'
import { FailureCard } from '../components/FailureCard'
import { OfflineBanner } from '../components/OfflineBanner'
import { PendingCounter } from '../components/PendingCounter'
import { ReauthController } from '../components/ReauthController'
import { ReviewNotice } from '../components/ReviewNotice'
import { ServerClock } from '../components/ServerClock'
import { StatusLine, type StatusTone } from '../components/StatusLine'
import { UpdateBanner } from '../components/UpdateBanner'
import type { CaptureResult } from '../components/SelfieCapture'
import { haptics } from '../capture/haptics'
import { geofenceResult } from '../capture/locationStatus'
import { messageKeyFor } from '../errors/errorMessages'
import { OUTBOX_EXPIRED_CODE } from '../storage/outbox'
import type { MessageKey } from '../i18n/resources'
import type { Translate } from '../i18n/i18nContext'
import { useI18n } from '../i18n/useI18n'
import { OutboxFullError, StorageFullError } from '../storage/outbox'
import { effectiveState, freshenToday, type EntryView } from '../sync/effectiveState'
import { useOutbox } from '../sync/useOutbox'
import {
  firstName,
  formatDuration,
  formatLongDate,
  formatSentenceDate,
  formatShortDate,
  formatTime,
  localDateString,
  localDateToMs,
} from '../time/format'
import { useServerClock } from '../time/useServerClock'
import { useReadiness } from './useReadiness'
import { useToday } from './useToday'

// Loaded on the first tap: the camera, compression and GPS code stay out of the entry chunk.
const SelfieCapture = lazy(() => import('../components/SelfieCapture'))

export function EmployeeHome() {
  return (
    <Announcer>
      <HomeContent />
    </Announcer>
  )
}

function resultKind(entry: Pick<EntryOutcome, 'insideGeofence' | 'distanceMeters'> | null, pending: boolean) {
  return geofenceResult(entry, pending)
}

/** One sentence for the Announcer: what was recorded, the server result and the review reasons. */
function deliveredMessage(t: Translate, response: TimeEntryResponse, timeText: string, reasons: string[]): string {
  const base = t(response.type === 'CLOCK_IN' ? 'announce.clockedIn' : 'announce.clockedOut', { time: timeText })
  const result = resultKind(response, false)
  const resultText = !result
    ? ''
    : result.kind === 'inside'
      ? t('result.inside', { meters: result.distanceMeters })
      : result.kind === 'outside'
        ? t('result.outside', { meters: result.distanceMeters })
        : t('result.noLocation')
  const review =
    response.status === 'PENDING_REVIEW' && reasons.length > 0 ? t('announce.sentWithReview', { reasons: reasons.join(', ') }) : ''
  return [base, resultText, review].filter(Boolean).join(' · ')
}

function HomeContent() {
  const { lang, t } = useI18n()
  const session = useSession()
  const outbox = useOutbox()
  const clock = useServerClock()
  const { announce } = useAnnouncer()
  const { today, lastCompleted } = useToday()

  const identity = session.identity
  const timeZone = today?.timeZone ?? identity?.timeZone ?? 'America/Costa_Rica'
  const nowMs = clock.ms

  // Also for a network answer: an app left open across local midnight must not keep saying "day complete".
  const shownToday = today ? freshenToday(today, localDateString(nowMs, timeZone)) : today
  const state = effectiveState(shownToday, outbox.pending, lastCompleted, nowMs, outbox.lastResults)
  const offline = !outbox.online || outbox.networkProblem

  const h1 = useRef<HTMLHeadingElement>(null)
  const statusHeading = useRef<HTMLHeadingElement>(null)
  const clockButton = useRef<HTMLButtonElement>(null)

  const [capturing, setCapturing] = useState(false)
  const [saving, setSaving] = useState(false)
  const [refusal, setRefusal] = useState<{ key: MessageKey; n: number } | null>(null)
  const [reauthOpen, setReauthOpen] = useState(false)

  const canClock = !state.buttonDisabled && state.kind !== 'unavailable'
  const readiness = useReadiness(canClock && state.action !== 'DONE')

  useEffect(() => {
    h1.current?.focus()
  }, [])

  // --- PIN dialog: opens when the session needs it, on reconnect, and from the counter button.
  const wasNeedingReauth = useRef(false)
  useEffect(() => {
    if (session.needsReauth && !wasNeedingReauth.current) setReauthOpen(true)
    wasNeedingReauth.current = session.needsReauth
  }, [session.needsReauth])

  // A cold open with an identity but no token: ask for the PIN as soon as we are online.
  useEffect(() => {
    if (session.ready && identity && !session.token && !session.needsReauth && outbox.online) requireReauth()
  }, [session.ready, identity, session.token, session.needsReauth, outbox.online])

  const wasOnline = useRef(outbox.online)
  useEffect(() => {
    if (outbox.online && !wasOnline.current && session.needsReauth) setReauthOpen(true)
    wasOnline.current = outbox.online
  }, [outbox.online, session.needsReauth])

  // --- Announcements (changes only: nothing is said for the state the page opens in).
  const firstRender = useRef(true)
  useEffect(() => {
    if (firstRender.current) {
      firstRender.current = false
      return
    }
    if (offline) announce(t('offline.banner'), { dedupeKey: 'offline' })
    else announce(outbox.pending.length > 0 ? t('offline.restoredSending') : t('offline.restored'), { dedupeKey: 'online' })
    // Only a change of the offline flag is announced; the other values are read at that moment.
    // oxlint-disable-next-line react-hooks/exhaustive-deps
  }, [offline])

  const lastAnnounced = useRef(0)
  useEffect(() => {
    const delivered = outbox.lastDelivered
    if (!delivered || delivered.id === lastAnnounced.current) return
    lastAnnounced.current = delivered.id
    const reasons = delivered.response.reviewReasons.map((reason) => t(`reviewReason.${reason}` as MessageKey))
    announce(
      deliveredMessage(
        t,
        delivered.response,
        formatTime(Date.parse(delivered.response.serverTime), timeZone, lang),
        reasons,
      ),
    )
  }, [outbox.lastDelivered, announce, t, timeZone, lang])

  useEffect(() => {
    if (readiness.camera === 'blocked') announce(t('announce.cameraBlocked'), { dedupeKey: 'camera' })
  }, [readiness.camera, announce, t])

  // --- Clock action.
  const press = useCallback(() => {
    setRefusal(null)
    if (outbox.pending.length >= 8) {
      setRefusal({ key: 'offline.queueFull', n: Date.now() })
      return
    }
    setCapturing(true)
  }, [outbox.pending.length])

  const confirmCapture = useCallback(
    async (result: CaptureResult) => {
      setCapturing(false)
      setSaving(true)
      const type: TimeEntryType = state.action === 'CLOCK_OUT' ? 'CLOCK_OUT' : 'CLOCK_IN'
      try {
        await outbox.enqueueCapture({ type, photo: result.photo.bytes, photoType: result.photo.type, fix: result.fix })
        // Online with a usable session the delivery announcement follows; otherwise say it is saved.
        if (!outbox.online || !session.token) {
          announce(t(type === 'CLOCK_IN' ? 'announce.queuedIn' : 'announce.queuedOut'))
        }
        haptics.success()
        statusHeading.current?.focus()
      } catch (error) {
        const key: MessageKey =
          error instanceof OutboxFullError
            ? 'offline.queueFull'
            : error instanceof StorageFullError
              ? 'offline.storageFull'
              : 'error.unknown'
        setRefusal({ key, n: Date.now() })
        clockButton.current?.focus()
      } finally {
        setSaving(false)
      }
    },
    [outbox, session.token, state.action, announce, t],
  )

  const cancelCapture = useCallback(() => {
    setCapturing(false)
    // The dialog's own cleanup returns focus to the opener; this covers a re-rendered button.
    queueMicrotask(() => clockButton.current?.focus())
  }, [])

  // --- View models.
  const fmtTime = (ms: number) => formatTime(ms, timeZone, lang)
  const reasonLabels = (reasons: string[]) => reasons.map((r) => t(`reviewReason.${r}` as MessageKey))

  function column(view: EntryView | null, type: TimeEntryType): EntryColumn | null {
    if (!view) return null
    if (view.pending) {
      return { timeText: fmtTime(view.timeMs), result: { kind: 'pending' }, status: null, reasonLabels: [] }
    }
    const outcome = view.outcome
    const result = resultKind(outcome, false)
    return {
      timeText: fmtTime(view.timeMs),
      result: result,
      status: outcome?.status ?? null,
      reasonLabels: reasonLabels(outcome?.reviewReasons ?? []),
      lateMinutes: type === 'CLOCK_IN' ? (outcome?.lateMinutes ?? null) : null,
    }
  }

  let statusText = ''
  let secondary: string | undefined
  let tone: StatusTone = 'neutral'
  switch (state.kind) {
    case 'unavailable':
      statusText = outbox.online ? t('home.loading') : t('home.status.needsConnection')
      tone = outbox.online ? 'neutral' : 'warning'
      break
    case 'noBranch':
      statusText = t('home.status.noBranch')
      tone = 'blocked'
      break
    case 'beforeClockIn':
      statusText = t('home.status.notYet')
      if (state.lastShift) {
        const inMs = Date.parse(state.lastShift.clockInAt)
        const outMs = Date.parse(state.lastShift.clockOutAt)
        secondary = t('home.lastShift', {
          date: formatShortDate(outMs, timeZone, lang),
          in: fmtTime(inMs),
          out: fmtTime(outMs),
          duration: formatDuration((outMs - inMs) / 60_000, t),
        })
      }
      break
    case 'inShift': {
      statusText = t('home.status.onShift', { time: state.clockIn ? fmtTime(state.clockIn.timeMs) : t('home.noTime') })
      tone = 'active'
      if (state.clockIn) {
        secondary = t('home.status.workedSoFar', {
          duration: formatDuration((nowMs - state.clockIn.timeMs) / 60_000, t),
        })
      }
      break
    }
    case 'queuedIn':
      statusText = t('home.status.queuedIn')
      tone = 'queued'
      break
    case 'queuedOut':
      statusText = t('home.status.queuedOut')
      tone = 'queued'
      break
    case 'dayComplete': {
      const minutes =
        shownToday?.workedMinutes ??
        (state.clockIn && state.clockOut ? (state.clockOut.timeMs - state.clockIn.timeMs) / 60_000 : 0)
      statusText = t('home.status.dayComplete', { duration: formatDuration(minutes, t) })
      tone = 'done'
      break
    }
  }

  const failedCards = outbox.failed.map((item) => ({
    item,
    key:
      item.failure?.code === OUTBOX_EXPIRED_CODE
        ? ('failure.expired' as const)
        : messageKeyFor(new ApiProblem(item.failure?.status ?? 0, item.failure?.code)),
  }))
  const oldest = outbox.pending[0]
  const missingNotice =
    shownToday?.missingClockOut && shownToday.forgottenWorkDate
      ? t('home.missingClockOut', {
          date: formatSentenceDate(localDateToMs(shownToday.forgottenWorkDate), 'UTC', lang),
        })
      : null
  const showEvidence = state.action !== 'DONE' && state.kind !== 'noBranch' && state.kind !== 'unavailable'
  const dateText = formatLongDate(nowMs, timeZone, lang)
  const statusId = 'home-status'

  return (
    <main className="screen">
      {failedCards.map(({ item, key }) => (
        <FailureCard
          key={item.seq}
          type={item.type}
          timeText={fmtTime(Date.parse(item.deviceTime))}
          messageKey={key}
          onAcknowledge={() => {
            void outbox.acknowledgeFailure(item.seq).then(() => statusHeading.current?.focus())
          }}
        />
      ))}
      <PendingCounter
        count={outbox.pending.length}
        oldestAt={oldest ? new Date(oldest.createdAt) : undefined}
        now={nowMs}
        timeZone={timeZone}
        needsReauth={session.needsReauth}
        onReauth={() => setReauthOpen(true)}
      />
      <OfflineBanner offline={offline} />
      <UpdateBanner busy={capturing || reauthOpen || outbox.flushing} />

      <div className="day-card">
        <p className="day-card__date">{dateText}</p>
        <h1 ref={h1} tabIndex={-1}>
          {t('home.greeting', { name: firstName(identity?.name ?? '') })}
        </h1>
      </div>

      <ServerClock epochMs={nowMs} timeZone={timeZone} approximate={clock.approximate} loading={!clock.known && !shownToday} />

      {missingNotice && <ReviewNotice kind="missingClockOut" text={missingNotice} />}
      <StatusLine id={statusId} tone={tone} text={statusText} secondary={secondary} headingRef={statusHeading} />

      {showEvidence && <EvidenceChips camera={readiness.camera} location={readiness.location} />}
      {refusal && <ErrorBox attempt={refusal.n}>{t(refusal.key)}</ErrorBox>}
      <ClockButton
        buttonRef={clockButton}
        action={state.action}
        disabled={state.buttonDisabled}
        disabledReasonId={statusId}
        busy={capturing || saving}
        onPress={press}
      />
      {state.kind === 'noBranch' && identity?.role === 'ADMIN' && (
        <p>
          <Link to="/admin">{t('employeeHome.toAdmin')}</Link>
        </p>
      )}

      <EntryTimes clockIn={column(state.clockIn, 'CLOCK_IN')} clockOut={column(state.clockOut, 'CLOCK_OUT')} />

      {!offline && <p className="footer-note">{t('offline.banner')}</p>}

      {capturing && state.action !== 'DONE' && (
        <Suspense fallback={<p className="busy-line">{t('camera.opening')}</p>}>
          <SelfieCapture action={state.action} onConfirm={(r) => void confirmCapture(r)} onCancel={cancelCapture} />
        </Suspense>
      )}
      {reauthOpen && session.needsReauth && <ReauthController onClose={() => setReauthOpen(false)} />}
    </main>
  )
}
