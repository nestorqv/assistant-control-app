import { useId, useRef, useState, type FormEvent } from 'react'
import { useAnnouncer } from '../../components/announcerContext'
import { Icon } from '../../components/Icon'
import { ModalDialog } from '../../components/ModalDialog'
import { useAdminI18n } from '../adminI18n'
import { resetPin } from '../api/adminApi'
import { PIN_RESET_REASONS, type PinResetReason, type PinResetResult } from '../api/adminTypes'
import { useAdminShell } from '../shell'
import { cleanText, hasDigitRun, hasUnsafeText } from '../text'
import { WriteError } from './WriteError'

export const MAX_NOTE = 200

type Props = {
  target: { id: number; name: string }
  /** the admin resets their own PIN: the session is suspended before sending and ends when the PIN dialog closes */
  self: boolean
  onCancel: () => void
  /** The result carries the new PIN: the owner shows it once. */
  onDone: (result: PinResetResult) => void
}

type Choice = 'NONE' | PinResetReason

/** Confirm + optional reason (preset and a short note). A note is only allowed together with a preset. */
export function PinResetDialog({ target, self, onCancel, onDone }: Props) {
  const { t } = useAdminI18n()
  const { announce } = useAnnouncer()
  const shell = useAdminShell()
  const titleId = useId()
  const bodyId = useId()
  const noteId = useId()
  const hintId = useId()
  const errorId = useId()
  const [choice, setChoice] = useState<Choice>('NONE')
  const [note, setNote] = useState('')
  const [noteError, setNoteError] = useState<string | null>(null)
  const [writeError, setWriteError] = useState<unknown>(null)
  const [attempt, setAttempt] = useState(0)
  const [busy, setBusy] = useState(false)
  const noteRef = useRef<HTMLTextAreaElement>(null)

  function pick(next: Choice) {
    setChoice(next)
    if (next === 'NONE' && note !== '') {
      // The server refuses a note without a reason, and hidden text that is not sent would be surprising.
      setNote('')
      setNoteError(null)
      announce(t('admin.pin.noteCleared'))
    }
  }

  function noteProblem(text: string): string | null {
    if (hasUnsafeText(text)) return t('admin.text.invisibleChars')
    if (hasDigitRun(text)) return t('admin.pin.noteDigits')
    if (cleanText(text).length > MAX_NOTE) return t('admin.pin.noteTooLong')
    return null
  }

  async function submit(event: FormEvent) {
    event.preventDefault()
    if (busy) return
    const problem = choice === 'NONE' ? null : noteProblem(note)
    setNoteError(problem)
    if (problem) {
      noteRef.current?.focus()
      return
    }
    const cleaned = cleanText(note)
    const body: { reasonCode?: PinResetReason; note?: string } = {}
    if (choice !== 'NONE') {
      body.reasonCode = choice
      if (cleaned !== '') body.note = cleaned
    }
    setBusy(true)
    setWriteError(null)
    // Own account: the token is invalid once the reset commits, so the poll and the re-auth dialog stop first.
    if (self) shell.suspendSession(true)
    try {
      onDone(await resetPin(target.id, body))
    } catch (error) {
      if (self) shell.suspendSession(false)
      setBusy(false)
      setAttempt((n) => n + 1)
      setWriteError(error)
    }
  }

  const noteEnabled = choice !== 'NONE'
  return (
    <ModalDialog labelledBy={titleId} describedBy={bodyId} role="alertdialog" onCancel={() => !busy && onCancel()} className="adm-dialog">
      <h2 id={titleId}>{t('admin.confirm.resetTitle', { name: target.name })}</h2>
      <form onSubmit={(event) => void submit(event)} noValidate aria-busy={busy || undefined} className="adm-form-grid">
        <div id={bodyId}>
          <p>{t('admin.confirm.resetBody')}</p>
          {self && (
            <p className="notice notice--warn">
              <Icon name="alert-triangle" />
              <span>{t('admin.confirm.resetSelf')}</span>
            </p>
          )}
        </div>
        <fieldset className="adm-reason-group">
          <legend>{t('admin.pin.reasonLegend')}</legend>
          {(['NONE', ...PIN_RESET_REASONS] as const).map((value) => (
            <label key={value} className="adm-check">
              <input
                type="radio"
                name="pin-reset-reason"
                checked={choice === value}
                disabled={busy}
                onChange={() => pick(value)}
              />
              <span>{t(`admin.pin.reason.${value}`)}</span>
            </label>
          ))}
        </fieldset>
        <div className="field">
          <label htmlFor={noteId}>{t('admin.pin.noteLabel')}</label>
          <textarea
            id={noteId}
            ref={noteRef}
            rows={3}
            maxLength={MAX_NOTE}
            autoComplete="off"
            value={note}
            disabled={!noteEnabled || busy}
            aria-invalid={noteError ? true : undefined}
            aria-describedby={noteError ? `${hintId} ${errorId}` : hintId}
            onChange={(event) => setNote(event.target.value)}
            onBlur={() => setNoteError(noteEnabled ? noteProblem(note) : null)}
          />
          <p className="adm-hint" id={hintId}>
            {t(noteEnabled ? 'admin.pin.noteHint' : 'admin.pin.noteNeedsReason')}
          </p>
          <p className="adm-counter" aria-hidden="true">
            {note.length} / {MAX_NOTE}
          </p>
          {noteError && (
            <p className="field__error" id={errorId} role="alert">
              {noteError}
            </p>
          )}
        </div>
        {writeError !== null && <WriteError error={writeError} attempt={attempt} />}
        <div className="modal__actions">
          <button type="button" className="button button--primary" data-autofocus aria-disabled={busy || undefined} onClick={() => !busy && onCancel()}>
            {t('common.cancel')}
          </button>
          <button type="submit" className="button button--destructive" aria-disabled={busy || undefined} aria-busy={busy || undefined}>
            {busy && <Icon name="spinner" />}
            <span>{busy ? t('admin.confirm.resetting') : t('admin.confirm.reset')}</span>
          </button>
        </div>
      </form>
    </ModalDialog>
  )
}
