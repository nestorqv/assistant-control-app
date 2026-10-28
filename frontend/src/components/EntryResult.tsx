import { useI18n } from '../i18n/useI18n'
import { Icon, type IconName } from './Icon'

export type EntryResultProps =
  | { kind: 'pending' }
  | { kind: 'sending' }
  | { kind: 'inside'; distanceMeters: number }
  | { kind: 'outside'; distanceMeters: number }
  | { kind: 'noLocation' }

const ICON: Record<EntryResultProps['kind'], IconName> = {
  pending: 'hourglass',
  sending: 'spinner',
  inside: 'check-circle',
  outside: 'alert-triangle',
  noLocation: 'map-pin-off',
}

/** The server's verdict for one entry, or "pending send". Distance is an integer, no separators. */
export function EntryResult(props: EntryResultProps) {
  const { t } = useI18n()
  const meters = 'distanceMeters' in props ? props.distanceMeters : 0
  const text = {
    pending: t('result.pending'),
    sending: t('result.sending'),
    inside: t('result.inside', { meters }),
    outside: t('result.outside', { meters }),
    noLocation: t('result.noLocation'),
  }[props.kind]
  return (
    <p className={`result result--${props.kind}`}>
      <Icon name={ICON[props.kind]} />
      <span>{text}</span>
    </p>
  )
}
