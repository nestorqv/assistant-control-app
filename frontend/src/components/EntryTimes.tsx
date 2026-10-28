import type { TimeEntryStatus } from '../api/types'
import { useI18n } from '../i18n/useI18n'
import { EntryResult, type EntryResultProps } from './EntryResult'
import { Icon } from './Icon'
import { ReviewNotice } from './ReviewNotice'

export type EntryColumn = {
  timeText: string
  result: EntryResultProps | null
  status: TimeEntryStatus | null
  reasonLabels: string[]
  lateMinutes?: number | null
}

type Props = { clockIn: EntryColumn | null; clockOut: EntryColumn | null }

function Column({ label, entry, showLate }: { label: string; entry: EntryColumn | null; showLate: boolean }) {
  const { t } = useI18n()
  return (
    <div className="entry-times__item">
      <dt>{label}</dt>
      <dd>
        {entry ? (
          <>
            <time className="entry-time">{entry.timeText}</time>
            {entry.result && <EntryResult {...entry.result} />}
            {entry.status === 'PENDING_REVIEW' && <ReviewNotice status="PENDING_REVIEW" reasons={entry.reasonLabels} />}
            {entry.status === 'REJECTED' && <ReviewNotice status="REJECTED" />}
            {showLate && entry.lateMinutes != null && entry.lateMinutes > 0 && (
              <p className="late">
                <Icon name="hourglass" />
                <span>{t('home.late', { minutes: entry.lateMinutes })}</span>
              </p>
            )}
          </>
        ) : (
          <>
            <span className="entry-time entry-time--empty" aria-hidden="true">
              {t('home.noTime')}
            </span>
            <span className="visually-hidden">{t('home.notRecorded')}</span>
          </>
        )}
      </dd>
    </div>
  )
}

/** A description list so a screen reader reads label, time and result in pairs. */
export function EntryTimes({ clockIn, clockOut }: Props) {
  const { t } = useI18n()
  return (
    <section aria-labelledby="entries-heading">
      <h2 id="entries-heading" className="visually-hidden">
        {t('home.todayEntries')}
      </h2>
      <dl className="entry-times">
        <Column label={t('home.entry')} entry={clockIn} showLate />
        <Column label={t('home.exit')} entry={clockOut} showLate={false} />
      </dl>
    </section>
  )
}
