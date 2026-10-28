import { useEffect, useId } from 'react'
import type { TimeEntryType } from '../api/types'
import { haptics } from '../capture/haptics'
import type { MessageKey } from '../i18n/resources'
import { useI18n } from '../i18n/useI18n'
import { Icon } from './Icon'

type Props = {
  type: TimeEntryType
  timeText: string
  messageKey: MessageKey
  onAcknowledge: () => void
}

/** A queued entry that can never succeed. role="alert" announces it once on insertion. */
export function FailureCard({ type, timeText, messageKey, onAcknowledge }: Props) {
  const { t } = useI18n()
  const titleId = useId()
  useEffect(() => haptics.error(), [])
  return (
    <section className="failure-card" role="alert" aria-labelledby={titleId}>
      <h3 id={titleId}>
        <Icon name="x-circle" />
        <span>{t(type === 'CLOCK_IN' ? 'failure.title.CLOCK_IN' : 'failure.title.CLOCK_OUT')}</span>
      </h3>
      <p>{t('failure.markTime', { time: timeText })}</p>
      <p>{t(messageKey)}</p>
      <p>{t('failure.contactAdmin')}</p>
      <button type="button" className="button button--secondary" onClick={onAcknowledge}>
        {t('failure.acknowledge')}
      </button>
    </section>
  )
}
