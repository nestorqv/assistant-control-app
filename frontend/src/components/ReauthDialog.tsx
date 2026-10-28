import { useId, useState } from 'react'
import { useI18n } from '../i18n/useI18n'
import { ErrorBox } from './ErrorBox'
import { Icon } from './Icon'
import { ModalDialog } from './ModalDialog'
import { PinDots } from './PinDots'
import { PinKeypad } from './PinKeypad'
import { formatCountdown, useCountdown } from './useCountdown'

type Props = {
  identity: { name: string; companyCode: string; employeeCode: string }
  busy: boolean
  errorText?: string | null
  /** increments on every failed attempt: clears the PIN and refocuses the error */
  attempt: number
  /** performance.now() value until which the keypad stays disabled (HTTP 429) */
  retryAt?: number | null
  onSubmit: (pin: string) => void
  onLater: () => void
  onNotYou: () => void
}

/** Presentational: the codes come from the stored identity and are read-only (nothing to retype). */
export function ReauthDialog({ identity, busy, errorText, attempt, retryAt = null, onSubmit, onLater, onNotYou }: Props) {
  const { t } = useI18n()
  const titleId = useId()
  // The PIN is cleared by tying it to the attempt it was typed in (no state reset inside an effect).
  const [entry, setEntry] = useState({ pin: '', attempt })
  const pin = entry.attempt === attempt ? entry.pin : ''
  const setPin = (next: string) => setEntry({ pin: next, attempt })
  const seconds = useCountdown(retryAt)
  const locked = seconds > 0
  const inactive = busy || locked

  return (
    <ModalDialog labelledBy={titleId} onCancel={onLater} className="modal--reauth">
      <h2 id={titleId} tabIndex={-1} data-autofocus>
        {t('reauth.title')}
      </h2>
      <p>{t('reauth.body')}</p>
      <p className="identity__name">{identity.name}</p>
      <dl className="identity">
        <div>
          <dt>{t('reauth.company')}</dt>
          <dd>{identity.companyCode}</dd>
        </div>
        <div>
          <dt>{t('reauth.employee')}</dt>
          <dd>{identity.employeeCode}</dd>
        </div>
      </dl>
      <PinDots length={pin.length} />
      <PinKeypad value={pin} onChange={setPin} disabled={inactive} />
      {errorText && <ErrorBox attempt={attempt}>{errorText}</ErrorBox>}
      {locked && (
        <p className="countdown" aria-hidden="true">
          {formatCountdown(seconds)}
        </p>
      )}
      <div className="modal__actions">
        <button
          type="button"
          className="button button--primary"
          aria-disabled={pin.length < 8 || inactive}
          aria-busy={busy || undefined}
          onClick={() => {
            if (pin.length === 8 && !inactive) onSubmit(pin)
          }}
        >
          {busy && <Icon name="spinner" />}
          <span>{busy ? t('login.submitting') : t('login.submit')}</span>
        </button>
        <button type="button" className="button button--secondary" onClick={onLater}>
          {t('reauth.later')}
        </button>
        <button type="button" className="button button--link" onClick={onNotYou}>
          {t('reauth.notYou')}
        </button>
      </div>
    </ModalDialog>
  )
}
