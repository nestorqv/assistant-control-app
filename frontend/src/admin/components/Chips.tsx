import type { TimeEntryStatus } from '../../api/types'
import { AdminIcon, type AdminIconName } from '../AdminIcon'
import { useAdminI18n } from '../adminI18n'
import type { DayStatus } from '../api/adminTypes'

type Family = 'teal' | 'amber' | 'violet' | 'redbrown' | 'grey'

// Colour is never the only signal: every status has its own icon and its text (design 1.3).
const DAY_STATUS: Record<DayStatus, { family: Family; icon: AdminIconName }> = {
  COMPLETE: { family: 'teal', icon: 'check-circle' },
  ON_TIME: { family: 'teal', icon: 'dot-circle' },
  LATE: { family: 'amber', icon: 'clock' },
  OUTSIDE_AREA: { family: 'violet', icon: 'map-pin-off' },
  NEEDS_REVIEW: { family: 'violet', icon: 'eye' },
  NOT_CLOCKED: { family: 'redbrown', icon: 'minus-circle' },
  MISSING_CLOCK_OUT: { family: 'redbrown', icon: 'clock-alert' },
  REJECTED: { family: 'redbrown', icon: 'x-circle' },
  UNSCHEDULED: { family: 'grey', icon: 'help-circle' },
  NOT_YET_DUE: { family: 'grey', icon: 'hourglass' },
  DAY_OFF: { family: 'grey', icon: 'calendar-off' },
}

const ENTRY_STATUS: Record<TimeEntryStatus, { family: Family; icon: AdminIconName }> = {
  VALID: { family: 'teal', icon: 'check-circle' },
  PENDING_REVIEW: { family: 'violet', icon: 'eye' },
  REJECTED: { family: 'redbrown', icon: 'x-circle' },
}

export function Chip({ family, icon, children }: { family: Family; icon: AdminIconName; children: string }) {
  return (
    <span className={`adm-chip adm-chip--${family}`}>
      <AdminIcon name={icon} />
      <span>{children}</span>
    </span>
  )
}

export function DayStatusChip({ status, minutes }: { status: DayStatus; minutes?: number | null }) {
  const { t } = useAdminI18n()
  const { family, icon } = DAY_STATUS[status]
  return (
    <Chip family={family} icon={icon}>
      {t(`admin.dayStatus.${status}`, { minutes: minutes ?? 0 })}
    </Chip>
  )
}

export function EntryStatusChip({ status }: { status: TimeEntryStatus }) {
  const { t } = useAdminI18n()
  const { family, icon } = ENTRY_STATUS[status]
  return (
    <Chip family={family} icon={icon}>
      {t(`status.${status}`)}
    </Chip>
  )
}

export function SelfApprovedChip() {
  const { t } = useAdminI18n()
  return (
    <Chip family="amber" icon="alert-triangle">
      {t('admin.history.selfApproved')}
    </Chip>
  )
}
