import type { Ref } from 'react'
import { useI18n } from '../i18n/useI18n'
import { Icon } from './Icon'

type Props = {
  action: 'CLOCK_IN' | 'CLOCK_OUT' | 'DONE'
  /** rendered as aria-disabled so keyboard and screen-reader users can still land on it */
  disabled?: boolean
  disabledReasonId?: string
  /** capture or enqueue in progress; presses are ignored (double-tap guard) */
  busy?: boolean
  onPress: () => void
  buttonRef?: Ref<HTMLButtonElement>
}

const LABEL = { CLOCK_IN: 'button.clockIn', CLOCK_OUT: 'button.clockOut', DONE: 'button.done' } as const
const ICON = { CLOCK_IN: 'log-in', CLOCK_OUT: 'log-out', DONE: 'check' } as const

export function ClockButton({ action, disabled = false, disabledReasonId, busy = false, onPress, buttonRef }: Props) {
  const { t } = useI18n()
  const inactive = disabled || busy || action === 'DONE'
  return (
    <button
      ref={buttonRef}
      type="button"
      className={`clock-button${inactive ? ' is-disabled' : ''}`}
      aria-disabled={inactive}
      aria-busy={busy || undefined}
      aria-describedby={inactive ? disabledReasonId : undefined}
      onClick={() => {
        if (!inactive) onPress()
      }}
    >
      <Icon name={busy ? 'spinner' : ICON[action]} />
      <span>{busy ? t('button.saving') : t(LABEL[action])}</span>
    </button>
  )
}
