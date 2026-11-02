import { useId, useRef, useState, type FormEvent } from 'react'
import type { TimeEntryStatus, TimeEntryType } from '../../api/types'
import { ErrorBox } from '../../components/ErrorBox'
import { Icon } from '../../components/Icon'
import { ModalDialog } from '../../components/ModalDialog'
import { useAdminI18n } from '../adminI18n'
import { approveEntry, correctEntry, rejectEntry } from '../api/adminApi'
import type { AdminEntryDetail } from '../api/adminTypes'
import { adminErrorText, problemCode } from '../errors'
import { localDateSentence, timeOf, toLocalInput } from '../format'
import { EntryStatusChip } from './Chips'
import { LocalDateTimeField } from './LocalDateTimeField'
import { isReasonValid, normalizeReason, PRESETS_BY_ACTION, ReasonField } from './ReasonField'

export type ActionMode = 'approve' | 'reject' | 'correct' | 'undoReject'

/** What the dialogs need to know about the entry; built from a queue item or from the evidence detail. */
export type ReviewTarget = {
  id: number
  employeeName: string
  employeeCode: string
  type: TimeEntryType
  workDate: string
  effectiveTime: string
  serverTime: string
  status: TimeEntryStatus
  deviceTime: string | null
  /** effective time of the clock-in of the same day (needed to correct a clock-out) */
  clockInTime: string | null
  syncedLateOrAhead: boolean
}

type Props = {
  mode: ActionMode
  target: ReviewTarget
  ownEntry: boolean
  timeZone: string
  onDone: (detail: AdminEntryDetail, mode: ActionMode) => void
  /** 409 ENTRY_CHANGED: the caller reloads the entry and explains */
  onEntryChanged: () => void
  onCancel: () => void
}

const TITLES = {
  approve: 'admin.action.titleApprove',
  reject: 'admin.action.titleReject',
  correct: 'admin.action.titleCorrect',
  undoReject: 'admin.action.titleUndo',
} as const

const SUBMIT = {
  approve: 'admin.action.submitApprove',
  reject: 'admin.action.submitReject',
  correct: 'admin.action.submitCorrect',
  undoReject: 'admin.action.submitUndo',
} as const

/** Approve, reject, correct the time, or undo a rejection. Every action needs a reason (5 to 500 characters). */
export function ReviewActionDialog({ mode, target, ownEntry, timeZone, onDone, onEntryChanged, onCancel }: Props) {
  const { t, lang } = useAdminI18n()
  const titleId = useId()
  const [reason, setReason] = useState('')
  const [reasonError, setReasonError] = useState<string | null>(null)
  const [localTime, setLocalTime] = useState(() => toLocalInput(target.effectiveTime, timeZone))
  const [timeError, setTimeError] = useState<string | null>(null)
  const [boxError, setBoxError] = useState<string | null>(null)
  const [attempt, setAttempt] = useState(0)
  const [busy, setBusy] = useState(false)
  const [note, setNote] = useState('')
  const reasonRef = useRef<HTMLTextAreaElement>(null)
  const timeRef = useRef<HTMLInputElement>(null)

  const correct = mode === 'correct'
  const presets = PRESETS_BY_ACTION[mode === 'undoReject' ? 'approve' : mode]
  const typeLabel = target.type === 'CLOCK_IN' ? t('admin.table.clockIn') : t('admin.table.clockOut')
  const currentLocal = toLocalInput(target.effectiveTime, timeZone)

  function fail(message: string, field: 'reason' | 'time' | 'box') {
    setAttempt((n) => n + 1)
    if (field === 'reason') {
      setReasonError(message)
      reasonRef.current?.focus()
    } else if (field === 'time') {
      setTimeError(message)
      timeRef.current?.focus()
    } else {
      setBoxError(message)
    }
  }

  async function submit(event: FormEvent) {
    event.preventDefault()
    if (busy) return
    setReasonError(null)
    setTimeError(null)
    setBoxError(null)
    if (correct && !localTime) return fail(t('admin.action.errorTimeRequired'), 'time')
    if (correct && localTime === currentLocal) return fail(t('admin.action.errorTimeSame'), 'time')
    if (!isReasonValid(reason)) return fail(t('admin.reason.error'), 'reason')
    const text = normalizeReason(reason)
    setBusy(true)
    try {
      let detail: AdminEntryDetail
      if (mode === 'reject') detail = await rejectEntry(target.id, text, target.status)
      else if (correct) {
        detail = await correctEntry(target.id, {
          reason: text,
          expectedStatus: target.status,
          expectedEffectiveTime: target.effectiveTime,
          localTime,
        })
      } else detail = await approveEntry(target.id, text, target.status)
      onDone(detail, mode)
    } catch (error) {
      setBusy(false)
      const code = problemCode(error)
      if (code === 'ENTRY_CHANGED') return onEntryChanged()
      if (code === 'INVALID_ENTRY_TIME') return fail(adminErrorText(error, t), 'time')
      fail(adminErrorText(error, t), 'box')
    }
  }

  return (
    <ModalDialog labelledBy={titleId} onCancel={() => !busy && onCancel()} className="adm-dialog">
      <h2 id={titleId}>{t(TITLES[mode])}</h2>
      <dl className="adm-summary">
        <div>
          <dt>{t('admin.table.employee')}</dt>
          <dd>
            {target.employeeName} · {target.employeeCode}
          </dd>
        </div>
        <div>
          <dt>{typeLabel}</dt>
          <dd>{localDateSentence(target.workDate, lang)}</dd>
        </div>
        <div>
          <dt>{t('admin.action.currentTime')}</dt>
          <dd>{timeOf(target.effectiveTime, timeZone, lang)}</dd>
        </div>
        <div>
          <dt>{t('admin.action.currentStatus')}</dt>
          <dd>
            <EntryStatusChip status={target.status} />
          </dd>
        </div>
      </dl>

      <form onSubmit={(event) => void submit(event)} noValidate aria-busy={busy || undefined}>
        {ownEntry && (
          <p className="notice notice--warn">
            <Icon name="alert-triangle" />
            <span>{t('admin.action.ownWarning')}</span>
          </p>
        )}
        {mode === 'approve' && target.syncedLateOrAhead && (
          <p className="notice notice--info">
            <Icon name="info" />
            <span>{t('admin.action.approveHint')}</span>
          </p>
        )}
        {mode === 'undoReject' && (
          <p className="notice notice--info">
            <Icon name="info" />
            <span>{t('admin.action.undoHint')}</span>
          </p>
        )}
        {mode === 'reject' && (
          <p className="notice notice--warn">
            <Icon name="alert-triangle" />
            <span>
              {target.status === 'VALID' ? `${t('admin.action.rejectValidWarning')} ` : ''}
              {t('admin.action.rejectKeepsHistory')}
            </span>
          </p>
        )}
        {correct && (
          <>
            <LocalDateTimeField
              label={t('admin.action.newTime')}
              value={localTime}
              onChange={setLocalTime}
              timeZone={timeZone}
              error={timeError}
              readOnly={busy}
              autoFocus
              inputRef={timeRef}
            />
            {target.deviceTime && (
              <button
                type="button"
                className="button button--secondary"
                onClick={() => {
                  setLocalTime(toLocalInput(target.deviceTime ?? target.effectiveTime, timeZone))
                  setNote(t('admin.action.deviceTimeApplied'))
                }}
              >
                {t('admin.action.useDeviceTime', { time: timeOf(target.deviceTime, timeZone, lang) })}
              </button>
            )}
            <p className="notice notice--info">
              <Icon name="info" />
              <span>
                {t('admin.action.keepsReceived', { time: timeOf(target.serverTime, timeZone, lang) })}{' '}
                {target.type === 'CLOCK_IN'
                  ? t('admin.action.recalcLate')
                  : target.clockInTime
                    ? t('admin.action.afterIn', { time: timeOf(target.clockInTime, timeZone, lang) })
                    : ''}
                {target.status !== 'VALID' ? ` ${t('admin.action.becomesValid')}` : ''}
              </span>
            </p>
          </>
        )}
        <ReasonField
          value={reason}
          onChange={setReason}
          presets={presets}
          error={reasonError}
          readOnly={busy}
          autoFocus={!correct}
          textareaRef={reasonRef}
        />
        {boxError && <ErrorBox attempt={attempt}>{boxError}</ErrorBox>}
        <span className="visually-hidden" aria-live="polite">
          {note}
        </span>
        <div className="modal__actions">
          <button
            type="submit"
            className={`button ${mode === 'reject' ? 'button--destructive' : 'button--primary'}`}
            aria-disabled={busy}
            aria-busy={busy || undefined}
          >
            {busy && <Icon name="spinner" />}
            <span>{busy ? t('admin.action.saving') : t(SUBMIT[mode])}</span>
          </button>
          <button type="button" className="button button--secondary" aria-disabled={busy} onClick={() => !busy && onCancel()}>
            {t('common.cancel')}
          </button>
        </div>
      </form>
    </ModalDialog>
  )
}
