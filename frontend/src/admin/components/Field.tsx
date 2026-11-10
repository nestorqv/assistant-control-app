import { useId, type ReactNode } from 'react'
import { useAdminI18n } from '../adminI18n'

export type FieldControl = {
  id: string
  'aria-describedby': string | undefined
  'aria-invalid': true | undefined
  'aria-required': true | undefined
}

type Props = {
  label: string
  required?: boolean
  hint?: string | null
  error?: string | null
  /** Receives the props that tie the control to its label, hint and error. */
  children: (control: FieldControl) => ReactNode
}

/** A visible label, a control, and a hint / inline error tied to it with aria-describedby (WCAG 1.3.1, 3.3.1). */
export function Field({ label, required = false, hint, error, children }: Props) {
  const { t } = useAdminI18n()
  const id = useId()
  const hintId = `${id}-hint`
  const errorId = `${id}-error`
  const describedBy = [hint ? hintId : null, error ? errorId : null].filter(Boolean).join(' ') || undefined
  return (
    <div className="field">
      <label htmlFor={id}>
        {label}
        {required && (
          <>
            <span aria-hidden="true"> *</span>
            <span className="visually-hidden"> ({t('admin.required')})</span>
          </>
        )}
      </label>
      {children({
        id,
        'aria-describedby': describedBy,
        'aria-invalid': error ? true : undefined,
        'aria-required': required ? true : undefined,
      })}
      {hint && (
        <p className="adm-hint" id={hintId}>
          {hint}
        </p>
      )}
      {error && (
        <p className="field__error" id={errorId} role="alert">
          {error}
        </p>
      )}
    </div>
  )
}
