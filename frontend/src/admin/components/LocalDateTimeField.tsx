import { useId, type Ref } from 'react'
import { useAdminI18n } from '../adminI18n'
import { zoneLabel } from '../format'

type Props = {
  label: string
  value: string
  onChange: (value: string) => void
  timeZone: string
  min?: string
  max?: string
  error?: string | null
  readOnly?: boolean
  autoFocus?: boolean
  inputRef?: Ref<HTMLInputElement>
}

/**
 * datetime-local shows no zone, so the company zone is always printed under it: the value is read in that zone
 * by the server, never in the zone of this computer.
 */
export function LocalDateTimeField({ label, value, onChange, timeZone, min, max, error, readOnly, autoFocus, inputRef }: Props) {
  const { t, lang } = useAdminI18n()
  const id = useId()
  const zoneId = `${id}-zone`
  const errorId = `${id}-error`
  return (
    <div className="field">
      <label htmlFor={id}>
        {label}
        <span aria-hidden="true"> *</span>
        <span className="visually-hidden"> ({t('admin.required')})</span>
      </label>
      <input
        id={id}
        ref={inputRef}
        type="datetime-local"
        step={60}
        value={value}
        min={min}
        max={max}
        readOnly={readOnly}
        required
        aria-invalid={error ? true : undefined}
        aria-describedby={error ? `${zoneId} ${errorId}` : zoneId}
        data-autofocus={autoFocus ? '' : undefined}
        onChange={(event) => onChange(event.target.value)}
      />
      <p className="adm-hint" id={zoneId}>
        {t('admin.action.zone', { zone: zoneLabel(timeZone, lang) })}
      </p>
      {error && (
        <p className="field__error" id={errorId} role="alert">
          {error}
        </p>
      )}
    </div>
  )
}
