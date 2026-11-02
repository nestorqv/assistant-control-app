import { useId, useState, type Ref } from 'react'
import { useAdminI18n } from '../adminI18n'

export type ReasonPreset =
  | 'verifiedPhoto'
  | 'authorizedBySupervisor'
  | 'deviceFailure'
  | 'forgotClockOut'
  | 'phoneClockWrong'
  | 'mistakenEntry'
  | 'photoMismatch'

export const MIN_REASON = 5
export const MAX_REASON = 500

/** Presets per action (design 5.3). The stored reason is whatever text is submitted, never a key. */
export const PRESETS_BY_ACTION: Record<'approve' | 'reject' | 'correct' | 'create', ReasonPreset[]> = {
  approve: ['verifiedPhoto', 'authorizedBySupervisor', 'deviceFailure'],
  reject: ['mistakenEntry', 'photoMismatch'],
  correct: ['phoneClockWrong', 'forgotClockOut', 'authorizedBySupervisor'],
  create: ['forgotClockOut', 'deviceFailure', 'authorizedBySupervisor'],
}

/** The server rejects control characters (line breaks and tabs too), so they become single spaces. */
export function normalizeReason(text: string): string {
  return text.replace(/\s+/g, ' ').trim()
}

export function isReasonValid(text: string): boolean {
  const length = normalizeReason(text).length
  return length >= MIN_REASON && length <= MAX_REASON
}

type Props = {
  value: string
  onChange: (value: string) => void
  presets: ReasonPreset[]
  error?: string | null
  readOnly?: boolean
  autoFocus?: boolean
  textareaRef?: Ref<HTMLTextAreaElement>
}

/** Required reason: visible label, hint, preset buttons that fill the text, counter, inline error. */
export function ReasonField({ value, onChange, presets, error, readOnly, autoFocus, textareaRef }: Props) {
  const { t } = useAdminI18n()
  const id = useId()
  const hintId = `${id}-hint`
  const errorId = `${id}-error`
  const [filled, setFilled] = useState('')
  return (
    <div className="field adm-reason">
      <label htmlFor={id}>
        {t('admin.reason.label')}
        <span aria-hidden="true"> *</span>
        <span className="visually-hidden"> ({t('admin.required')})</span>
      </label>
      <p className="adm-hint" id={hintId}>
        {t('admin.reason.hint')}
      </p>
      <div role="group" aria-label={t('admin.reason.presets')} className="adm-presets">
        {presets.map((preset) => (
          <button
            key={preset}
            type="button"
            className="adm-preset"
            aria-disabled={readOnly || undefined}
            onClick={() => {
              if (readOnly) return
              onChange(t(`admin.reasonPreset.${preset}`))
              setFilled(t('admin.reason.filled'))
            }}
          >
            {t(`admin.reasonPreset.${preset}`)}
          </button>
        ))}
      </div>
      <textarea
        id={id}
        ref={textareaRef}
        rows={3}
        maxLength={MAX_REASON}
        autoComplete="off"
        value={value}
        readOnly={readOnly}
        aria-required="true"
        aria-invalid={error ? true : undefined}
        aria-describedby={error ? `${hintId} ${errorId}` : hintId}
        data-autofocus={autoFocus ? '' : undefined}
        onChange={(event) => onChange(event.target.value)}
      />
      <p className="adm-counter" aria-hidden="true">
        {value.length} / {MAX_REASON}
      </p>
      {error && (
        <p className="field__error" id={errorId} role="alert">
          {error}
        </p>
      )}
      <span className="visually-hidden" aria-live="polite">
        {filled}
      </span>
    </div>
  )
}
