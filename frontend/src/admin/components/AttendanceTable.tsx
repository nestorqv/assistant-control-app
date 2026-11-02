import { AdminIcon, type AdminIconName } from '../AdminIcon'
import { useAdminI18n } from '../adminI18n'
import type { AdminEntrySummary, DashboardRow } from '../api/adminTypes'
import { hoursMinutes, initials, timeOf } from '../format'
import { createAction, evidenceKind, isEdited, targetEntry, type EvidenceKind } from '../rows'
import { DayStatusChip } from './Chips'

export type RowHandlers = {
  onEvidence: (entryId: number, employeeName: string) => void
  onCreate: (row: DashboardRow, type: 'CLOCK_IN' | 'CLOCK_OUT') => void
}

type Props = RowHandlers & { rows: DashboardRow[]; caption: string; timeZone: string }

const EVIDENCE_ICON: Record<EvidenceKind, AdminIconName> = {
  photoGps: 'camera',
  gpsAt: 'map-pin-off',
  photoOnly: 'camera',
  manual: 'pencil',
  tablet: 'info',
  none: 'circle',
}

function Avatar({ name }: { name: string }) {
  return (
    <span className="adm-avatar" aria-hidden="true">
      {initials(name)}
    </span>
  )
}

function TimeCell({ entry, timeZone }: { entry: AdminEntrySummary | null; timeZone: string }) {
  const { t, lang } = useAdminI18n()
  if (!entry) {
    return (
      <>
        <span aria-hidden="true">—</span>
        <span className="visually-hidden">{t('home.notRecorded')}</span>
      </>
    )
  }
  return (
    <>
      <time dateTime={entry.effectiveTime}>{timeOf(entry.effectiveTime, timeZone, lang)}</time>
      {isEdited(entry) && (
        <span className="adm-edited">
          <AdminIcon name="pencil" />
          <span aria-hidden="true">{t('admin.table.edited')}</span>
          <span className="visually-hidden">{t('admin.table.editedSr')}</span>
        </span>
      )}
    </>
  )
}

function HoursCell({ row }: { row: DashboardRow }) {
  const { t } = useAdminI18n()
  if (row.workedMinutes !== null) return <>{hoursMinutes(row.workedMinutes, t)}</>
  if (row.inProgress) {
    return (
      <span className="adm-inprogress">
        <AdminIcon name="dot-circle" />
        {t('admin.table.inProgress')}
      </span>
    )
  }
  return (
    <>
      <span aria-hidden="true">—</span>
      <span className="visually-hidden">{t('admin.table.noHours')}</span>
    </>
  )
}

function EvidenceCell({ row, timeZone, onEvidence }: { row: DashboardRow; timeZone: string; onEvidence: RowHandlers['onEvidence'] }) {
  const { t, lang } = useAdminI18n()
  const entry = targetEntry(row)
  const kind = entry ? evidenceKind(entry) : 'none'
  if (!entry || kind === 'none') {
    return (
      <>
        <span aria-hidden="true">—</span>
        <span className="visually-hidden">{t('admin.evidence.none')}</span>
      </>
    )
  }
  const label =
    kind === 'gpsAt'
      ? t('admin.evidence.gpsAt', { meters: entry.distanceMeters ?? 0 })
      : t(kind === 'photoGps' ? 'admin.evidence.photoAndGps' : `admin.evidence.${kind}`)
  const type = entry.type === 'CLOCK_IN' ? t('admin.table.clockIn') : t('admin.table.clockOut')
  return (
    <button
      type="button"
      className="adm-link-button"
      // The accessible name starts with the visible words (WCAG 2.5.3).
      aria-label={t('admin.evidence.viewAria', {
        label,
        name: row.employee.name,
        type: type.toLocaleLowerCase(),
        time: timeOf(entry.effectiveTime, timeZone, lang),
      })}
      onClick={() => onEvidence(entry.id, row.employee.name)}
    >
      <AdminIcon name={EVIDENCE_ICON[kind]} />
      <span>{label}</span>
    </button>
  )
}

function StatusCell({ row, onCreate }: { row: DashboardRow; onCreate: RowHandlers['onCreate'] }) {
  const { t } = useAdminI18n()
  const action = createAction(row)
  const showLateLine = row.dayStatus === 'COMPLETE' && row.lateMinutes !== null && row.lateMinutes > 0
  return (
    <div className="adm-status-cell">
      <DayStatusChip status={row.dayStatus} minutes={row.lateMinutes} />
      {showLateLine && <span className="adm-lateline">{t('admin.table.lateLine', { minutes: row.lateMinutes ?? 0 })}</span>}
      {action && (
        <button type="button" className="adm-link-button" aria-label={t(action === 'CLOCK_IN' ? 'admin.table.createInFor' : 'admin.table.createOutFor', { name: row.employee.name })} onClick={() => onCreate(row, action)}>
          <span>{t(action === 'CLOCK_IN' ? 'admin.table.createIn' : 'admin.table.createOut')}</span>
        </button>
      )}
    </div>
  )
}

function EmployeeCell({ row }: { row: DashboardRow }) {
  const { t } = useAdminI18n()
  return (
    <div className="adm-employee">
      <Avatar name={row.employee.name} />
      <span>
        <span className="adm-employee__name">{row.employee.name}</span>
        {!row.employee.active && <span className="adm-inactive">{t('admin.table.inactive')}</span>}
        {row.employee.jobTitle && <span className="adm-employee__title">{row.employee.jobTitle}</span>}
      </span>
    </div>
  )
}

/** A real table (header cells, row headers, hidden caption) for tablets and desktops. */
export function AttendanceTable({ rows, caption, timeZone, onEvidence, onCreate }: Props) {
  const { t } = useAdminI18n()
  return (
    <div className="adm-table-wrap">
      <table className="adm-table">
        <caption className="visually-hidden">{caption}</caption>
        <thead>
          <tr>
            <th scope="col">{t('admin.table.employee')}</th>
            <th scope="col">{t('admin.table.clockIn')}</th>
            <th scope="col">{t('admin.table.clockOut')}</th>
            <th scope="col">{t('admin.table.hours')}</th>
            <th scope="col">{t('admin.table.evidence')}</th>
            <th scope="col">{t('admin.table.status')}</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => (
            <tr key={row.employee.id}>
              <th scope="row">
                <EmployeeCell row={row} />
              </th>
              <td>
                <TimeCell entry={row.clockIn} timeZone={timeZone} />
              </td>
              <td>
                <TimeCell entry={row.clockOut} timeZone={timeZone} />
              </td>
              <td>
                <HoursCell row={row} />
              </td>
              <td>
                <EvidenceCell row={row} timeZone={timeZone} onEvidence={onEvidence} />
              </td>
              <td>
                <StatusCell row={row} onCreate={onCreate} />
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

/** Below 768 px: a list of cards. Only one of table / list is in the DOM (a reflowed table loses its semantics). */
export function AttendanceList({ rows, caption, timeZone, onEvidence, onCreate }: Props) {
  const { t } = useAdminI18n()
  return (
    <ul className="adm-cards" aria-label={caption}>
      {rows.map((row) => (
        <li key={row.employee.id}>
          <article className="adm-card">
            <EmployeeCell row={row} />
            <StatusCell row={row} onCreate={onCreate} />
            <dl className="adm-card__times">
              <div>
                <dt>{t('admin.table.clockIn')}</dt>
                <dd>
                  <TimeCell entry={row.clockIn} timeZone={timeZone} />
                </dd>
              </div>
              <div>
                <dt>{t('admin.table.clockOut')}</dt>
                <dd>
                  <TimeCell entry={row.clockOut} timeZone={timeZone} />
                </dd>
              </div>
              <div>
                <dt>{t('admin.table.hours')}</dt>
                <dd>
                  <HoursCell row={row} />
                </dd>
              </div>
            </dl>
            <EvidenceCell row={row} timeZone={timeZone} onEvidence={onEvidence} />
          </article>
        </li>
      ))}
    </ul>
  )
}
