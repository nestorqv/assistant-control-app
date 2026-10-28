import { useI18n } from '../i18n/useI18n'
import { formatShortDateTime } from '../time/format'
import { Icon } from './Icon'

const DAY_MS = 24 * 3_600_000

type Props = {
  count: number
  oldestAt?: Date
  now: number
  timeZone: string
  needsReauth: boolean
  onReauth: () => void
}

/** "N pending" chip. With an expired session it becomes the way back into the PIN dialog. */
export function PendingCounter({ count, oldestAt, now, timeZone, needsReauth, onReauth }: Props) {
  const { lang, t } = useI18n()
  if (count === 0 && !needsReauth) return null
  const old = oldestAt !== undefined && now - oldestAt.getTime() > DAY_MS
  return (
    <div className="pending-counter">
      {needsReauth ? (
        <button type="button" className="chip chip--warn chip--button" onClick={onReauth}>
          <Icon name="hourglass" />
          <span>{t('reauth.enter')}</span>
        </button>
      ) : (
        <span className="chip chip--neutral">
          <Icon name="hourglass" />
          <span>{count === 1 ? t('home.pendingCount.one') : t('home.pendingCount.other', { n: count })}</span>
        </span>
      )}
      {old && oldestAt && count > 0 && (
        <p className="pending-since">
          {t('home.pendingSince', { date: formatShortDateTime(oldestAt.getTime(), timeZone, lang) })}
        </p>
      )}
    </div>
  )
}
