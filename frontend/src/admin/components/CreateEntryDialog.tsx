import { useId, useRef, useState, type FormEvent } from 'react'
import type { TimeEntryType } from '../../api/types'
import { ErrorBox } from '../../components/ErrorBox'
import { Icon } from '../../components/Icon'
import { ModalDialog } from '../../components/ModalDialog'
import { useAdminI18n } from '../adminI18n'
import { createEntry } from '../api/adminApi'
import type { AdminEntryDetail } from '../api/adminTypes'
import { adminErrorText, problemCode } from '../errors'
import { localDateSentence, timeOf } from '../format'
import { LocalDateTimeField } from './LocalDateTimeField'
import { isReasonValid, normalizeReason, PRESETS_BY_ACTION, ReasonField } from './ReasonField'

export type CreateTarget = {
  employee: { id: number; name: string; code: string; active: boolean }
  hasBranch: boolean
  type: TimeEntryType
  workDate: string
  isToday: boolean
  /** effective time of the clock-in already recorded that day (when creating a clock-out) */
  existingIn: string | null
  /** scheduled start (clock-in) or end (clock-out) as HH:mm, or null without a schedule */
  scheduled?: string | null
}

type Props = {
  target: CreateTarget
  timeZone: string
  onDone: (detail: AdminEntryDetail) => void
  /** ENTRY_ALREADY_EXISTS: the table is stale; the caller closes this dialog and reloads */
  onRefresh: () => void
  onCancel: () => void
}

const FALLBACK_TIME = { CLOCK_IN: '08:00', CLOCK_OUT: '17:00' } as const

/** Records a missing clock-in or clock-out as a manual entry (no photo, no location). */
export function CreateEntryDialog({ target, timeZone, onDone, onRefresh, onCancel }: Props) {
  const { t, lang } = useAdminI18n()
  const titleId = useId()
  const [localTime, setLocalTime] = useState(`${target.workDate}T${target.scheduled ?? FALLBACK_TIME[target.type]}`)
  const [reason, setReason] = useState('')
  const [timeError, setTimeError] = useState<string | null>(null)
  const [reasonError, setReasonError] = useState<string | null>(null)
  const [boxError, setBoxError] = useState<string | null>(null)
  const [attempt, setAttempt] = useState(0)
  const [busy, setBusy] = useState(false)
  const [stale, setStale] = useState(false)
  const timeRef = useRef<HTMLInputElement>(null)
  const reasonRef = useRef<HTMLTextAreaElement>(null)

  const clockOut = target.type === 'CLOCK_OUT'
  const title = t(clockOut ? 'admin.create.titleOut' : 'admin.create.titleIn')

  async function submit(event: FormEvent) {
    event.preventDefault()
    if (busy || !target.hasBranch) return
    setTimeError(null)
    setReasonError(null)
    setBoxError(null)
    if (!localTime) {
      setAttempt((n) => n + 1)
      setTimeError(t('admin.action.errorTimeRequired'))
      timeRef.current?.focus()
      return
    }
    if (!isReasonValid(reason)) {
      setAttempt((n) => n + 1)
      setReasonError(t('admin.reason.error'))
      reasonRef.current?.focus()
      return
    }
    setBusy(true)
    try {
      const detail = await createEntry({
        employeeId: target.employee.id,
        type: target.type,
        workDate: target.workDate,
        localTime,
        reason: normalizeReason(reason),
      })
      onDone(detail)
    } catch (error) {
      setBusy(false)
      setAttempt((n) => n + 1)
      const code = problemCode(error)
      if (code === 'INVALID_ENTRY_TIME') {
        setTimeError(adminErrorText(error, t))
        timeRef.current?.focus()
      } else {
        setStale(code === 'ENTRY_ALREADY_EXISTS')
        setBoxError(adminErrorText(error, t))
      }
    }
  }

  return (
    <ModalDialog labelledBy={titleId} onCancel={() => !busy && onCancel()} className="adm-dialog">
      <h2 id={titleId}>{title}</h2>
      <p className="adm-dialog__sub">
        {target.employee.name} · {target.employee.code}
      </p>
      <p className="adm-dialog__sub">{t('admin.create.workDay', { date: localDateSentence(target.workDate, lang) })}</p>
      {clockOut && target.existingIn && (
        <p className="adm-dialog__sub">{t('admin.create.existingIn', { time: timeOf(target.existingIn, timeZone, lang) })}</p>
      )}
      <form onSubmit={(event) => void submit(event)} noValidate aria-busy={busy || undefined}>
        {!target.employee.active && (
          <p className="notice notice--info">
            <Icon name="info" />
            <span>{t('admin.create.inactive')}</span>
          </p>
        )}
        {!target.hasBranch && <ErrorBox>{t('error.NO_BRANCH_ASSIGNED')}</ErrorBox>}
        <LocalDateTimeField
          label={t(clockOut ? 'admin.create.timeOut' : 'admin.create.timeIn')}
          value={localTime}
          onChange={setLocalTime}
          timeZone={timeZone}
          error={timeError}
          readOnly={busy}
          autoFocus
          inputRef={timeRef}
        />
        {target.scheduled === null && <p className="adm-hint">{t('admin.create.noSchedule')}</p>}
        <p className="notice notice--info">
          <Icon name="info" />
          <span>{t('admin.create.manualNotice')}</span>
        </p>
        {target.isToday && !clockOut && <p className="adm-hint">{t('admin.create.employeeCanClockOut')}</p>}
        <ReasonField
          value={reason}
          onChange={setReason}
          presets={PRESETS_BY_ACTION.create}
          error={reasonError}
          readOnly={busy}
          textareaRef={reasonRef}
        />
        {boxError && <ErrorBox attempt={attempt}>{boxError}</ErrorBox>}
        {stale && (
          <button type="button" className="button button--secondary" onClick={onRefresh}>
            {t('admin.create.refresh')}
          </button>
        )}
        <div className="modal__actions">
          <button
            type="submit"
            className="button button--primary"
            aria-disabled={busy || !target.hasBranch}
            aria-busy={busy || undefined}
          >
            {busy && <Icon name="spinner" />}
            <span>{busy ? t('admin.action.saving') : t(clockOut ? 'admin.create.submitOut' : 'admin.create.submitIn')}</span>
          </button>
          <button type="button" className="button button--secondary" aria-disabled={busy} onClick={() => !busy && onCancel()}>
            {t('common.cancel')}
          </button>
        </div>
      </form>
    </ModalDialog>
  )
}
