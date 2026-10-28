import type { ReactNode } from 'react'
import { useI18n } from '../i18n/useI18n'
import { Icon } from './Icon'

type Props =
  | { status: 'PENDING_REVIEW'; reasons: string[] }
  | { status: 'REJECTED'; reasons?: string[] }
  | { kind: 'missingClockOut'; text: ReactNode }

/** A warning box (error box for REJECTED). The missing clock-out notice reuses the same box. */
export function ReviewNotice(props: Props) {
  const { t } = useI18n()
  if ('kind' in props) {
    return (
      <p className="notice notice--warn">
        <Icon name="alert-triangle" />
        <span>{props.text}</span>
      </p>
    )
  }
  if (props.status === 'REJECTED') {
    return (
      <p className="notice notice--error">
        <Icon name="x-circle" />
        <span>{t('home.review.rejected')}</span>
      </p>
    )
  }
  return (
    <p className="notice notice--warn">
      <Icon name="alert-triangle" />
      <span>{t('home.review.badge', { reasons: props.reasons.join(', ') })}</span>
    </p>
  )
}
