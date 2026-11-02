import { Link } from 'react-router'
import type { ReviewReason } from '../../api/types'
import { AdminIcon } from '../AdminIcon'
import { useAdminI18n } from '../adminI18n'
import type { Kpis } from '../api/adminTypes'

type Props = { kpis: Kpis | null; date: string; loading: boolean }

const DASH = '…'

/** The most frequent review reason with its count, e.g. "1 outside the area". */
function topReason(byReason: Kpis['toReviewByReason']): { reason: ReviewReason; count: number } | null {
  const entries = Object.entries(byReason) as [ReviewReason, number][]
  entries.sort((a, b) => b[1] - a[1])
  return entries[0] ? { reason: entries[0][0], count: entries[0][1] } : null
}

/** Four cards in a list: each label and value is read together ("Present: 9 of 12"). */
export function KpiCards({ kpis, date, loading }: Props) {
  const { t } = useAdminI18n()
  const top = kpis ? topReason(kpis.toReviewByReason) : null
  return (
    <ul className="adm-kpis" aria-busy={loading || undefined}>
      <li className="adm-kpi adm-kpi--teal">
        <span className="adm-kpi__label">
          <AdminIcon name="check-circle" />
          {t('admin.kpi.present')}
        </span>
        <span className="adm-kpi__value">
          {kpis ? kpis.present : DASH}
          {kpis && <span className="adm-kpi__of"> {t('admin.kpi.ofTotal', { n: kpis.expected })}</span>}
        </span>
      </li>
      <li className="adm-kpi adm-kpi--amber">
        <span className="adm-kpi__label">
          <AdminIcon name="clock" />
          {t('admin.kpi.late')}
        </span>
        <span className="adm-kpi__value">{kpis ? kpis.late : DASH}</span>
      </li>
      <li className="adm-kpi adm-kpi--redbrown">
        <span className="adm-kpi__label">
          <AdminIcon name="minus-circle" />
          {t('admin.kpi.notClocked')}
        </span>
        <span className="adm-kpi__value">{kpis ? kpis.notClocked : DASH}</span>
        {kpis && kpis.notYetDue > 0 && <span className="adm-kpi__sub">{t('admin.kpi.notYetDue', { n: kpis.notYetDue })}</span>}
      </li>
      <li className="adm-kpi adm-kpi--violet">
        <Link className="adm-kpi__link" to={`/admin/review?from=${date}&to=${date}`}>
          <span className="adm-kpi__label">
            <AdminIcon name="eye" />
            {t('admin.kpi.toReview')}
          </span>
          <span className="adm-kpi__value">{kpis ? kpis.toReview : DASH}</span>
          {top && (
            <span className="adm-kpi__sub">
              {t('admin.kpi.toReviewReason', { n: top.count, reason: t(`reviewReason.${top.reason}`).toLocaleLowerCase() })}
            </span>
          )}
          <span className="adm-kpi__sub">{t('admin.kpi.toReviewHelp')}</span>
          <span className="visually-hidden">{t('admin.kpi.openReview')}</span>
        </Link>
      </li>
    </ul>
  )
}
