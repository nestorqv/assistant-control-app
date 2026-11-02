import { useEffect, useId, useRef, useState } from 'react'
import { useSearchParams } from 'react-router'
import { useSession } from '../../auth/useSession'
import { useAnnouncer } from '../../components/announcerContext'
import { ErrorBox } from '../../components/ErrorBox'
import { Icon } from '../../components/Icon'
import { AdminIcon } from '../AdminIcon'
import { useAdminI18n, type AdminTranslate } from '../adminI18n'
import { fetchEmployees, fetchReviewQueue, REVIEW_PAGE_SIZE, type EmployeeOption, type ReviewQuery } from '../api/adminApi'
import type { ReviewItem } from '../api/adminTypes'
import { Chip, EntryStatusChip } from '../components/Chips'
import { CreateEntryDialog, type CreateTarget } from '../components/CreateEntryDialog'
import { EvidenceDialog } from '../components/EvidenceDialog'
import { PageHeading, Pagination, QueryError } from '../components/PageParts'
import { ReviewActionDialog, type ActionMode, type ReviewTarget } from '../components/ReviewActionDialog'
import { localDateSentence, minutesApart, timeOf } from '../format'
import { useAdminQuery } from '../hooks/useAdminQuery'
import { isEdited } from '../rows'
import { useAdminShell } from '../shell'

const ISO_DATE = /^\d{4}-\d{2}-\d{2}$/

function toTarget(item: ReviewItem): ReviewTarget {
  const e = item.entry
  return {
    id: e.id,
    employeeName: item.employee.name,
    employeeCode: item.employee.code,
    type: e.type,
    workDate: item.workDate,
    effectiveTime: e.effectiveTime,
    serverTime: e.serverTime,
    status: e.status,
    deviceTime: e.deviceTime,
    clockInTime: e.type === 'CLOCK_OUT' ? (item.pair?.effectiveTime ?? null) : null,
    syncedLateOrAhead: e.reviewReasons.includes('SYNCED_LATE') || e.reviewReasons.includes('DEVICE_CLOCK_AHEAD'),
  }
}

function actionAria(t: AdminTranslate, action: string, item: ReviewItem, timeZone: string, lang: 'es' | 'en') {
  const type = item.entry.type === 'CLOCK_IN' ? t('admin.table.clockIn') : t('admin.table.clockOut')
  return t('admin.review.actionAria', {
    action,
    name: item.employee.name,
    type: type.toLocaleLowerCase(),
    time: timeOf(item.entry.effectiveTime, timeZone, lang),
    date: localDateSentence(item.workDate, lang),
  })
}

/** today minus five years, the oldest date the server accepts (it answers 400 beyond that). */
export function oldestAllowed(today: string): string {
  const date = new Date(`${today}T12:00:00Z`)
  date.setUTCFullYear(date.getUTCFullYear() - 5)
  return date.toISOString().slice(0, 10)
}

/**
 * A date filter that only reaches the URL (and so the server) while the value is complete and inside
 * [today - 5 years, today]. While the user is typing (or the browser reports a partial year such as 0002) the
 * last valid list stays on screen. Emptying the field and leaving it clears the filter.
 */
function DateFilter({
  id,
  applied,
  today,
  onCommit,
}: {
  id: string
  applied: string | null
  today: string | null
  onCommit: (value: string | null) => void
}) {
  const [draft, setDraft] = useState(applied ?? '')
  const min = today ? oldestAllowed(today) : undefined
  const valid = (value: string) => ISO_DATE.test(value) && (!today || (value >= (min ?? '') && value <= today))
  return (
    <input
      id={id}
      type="date"
      value={draft}
      min={min}
      max={today ?? undefined}
      onChange={(event) => {
        setDraft(event.target.value)
        if (valid(event.target.value)) onCommit(event.target.value)
      }}
      onBlur={() => {
        if (draft === '') onCommit(null)
        else if (!valid(draft)) setDraft(applied ?? '')
      }}
    />
  )
}

type Overlay =
  | { kind: 'evidence'; item: ReviewItem }
  | { kind: 'action'; item: ReviewItem; mode: ActionMode }
  | { kind: 'create'; target: CreateTarget }
  | null

function ReviewCard({
  item,
  index,
  timeZone,
  onOverlay,
}: {
  item: ReviewItem
  index: number
  timeZone: string
  onOverlay: (overlay: Overlay) => void
}) {
  const { t, lang } = useAdminI18n()
  const { entry, pair } = item
  const typeLabel = entry.type === 'CLOCK_IN' ? t('admin.table.clockIn') : t('admin.table.clockOut')
  const rejected = entry.status === 'REJECTED'
  const loneClockIn = entry.type === 'CLOCK_IN' && pair === null
  const outside = entry.reviewReasons.includes('OUTSIDE_GEOFENCE') && entry.distanceMeters !== null
  const titleId = useId()
  return (
    <article className="adm-review-card" aria-labelledby={titleId}>
      <div className="adm-review-card__head">
        <h3 id={titleId} tabIndex={-1} data-card-index={index}>
          {item.employee.name}
        </h3>
        <span className="adm-hint">
          {item.employee.code}
          {item.employee.jobTitle ? ` · ${item.employee.jobTitle}` : ''}
        </span>
        <span className="adm-review-card__date">{localDateSentence(item.workDate, lang)}</span>
      </div>
      <p>
        <strong>{typeLabel}</strong> <time dateTime={entry.effectiveTime}>{timeOf(entry.effectiveTime, timeZone, lang)}</time>
        {isEdited(entry) && (
          <span className="adm-edited">
            <AdminIcon name="pencil" />
            {t('admin.table.edited')}
          </span>
        )}{' '}
        <span className="adm-hint">
          {pair
            ? t(pair.type === 'CLOCK_IN' ? 'admin.review.pairIn' : 'admin.review.pairOut', {
                time: timeOf(pair.effectiveTime, timeZone, lang),
              })
            : `${entry.type === 'CLOCK_IN' ? t('admin.table.clockOut') : t('admin.table.clockIn')}: ${t('admin.review.pairNone')}`}
        </span>
        {entry.lateMinutes !== null && entry.lateMinutes > 0 && (
          <span className="adm-lateline"> {t('admin.table.lateLine', { minutes: entry.lateMinutes })}</span>
        )}
      </p>
      <div className="adm-review-card__chips">
        <EntryStatusChip status={entry.status} />
        {entry.reviewReasons.map((reason) => (
          <span key={reason} className="adm-reason-chip">
            {reason === 'OUTSIDE_GEOFENCE' && outside
              ? `${t('reviewReason.OUTSIDE_GEOFENCE')} · ${t('admin.review.distance', { meters: entry.distanceMeters ?? 0 })}`
              : t(`reviewReason.${reason}`)}
          </span>
        ))}
        {item.ownEntry && (
          <Chip family="amber" icon="alert-triangle">
            {t('admin.review.ownEntry')}
          </Chip>
        )}
      </div>
      {entry.deviceTime && (entry.syncedLate || entry.reviewReasons.includes('DEVICE_CLOCK_AHEAD')) && (
        <p className="adm-hint">
          {t('admin.review.deviceLine', {
            time: timeOf(entry.deviceTime, timeZone, lang),
            minutes: minutesApart(entry.deviceTime, entry.serverTime),
          })}
        </p>
      )}
      <div className="adm-review-card__actions">
        <button
          type="button"
          className="adm-link-button"
          aria-label={actionAria(t, t('admin.review.evidence'), item, timeZone, lang)}
          onClick={() => onOverlay({ kind: 'evidence', item })}
        >
          <AdminIcon name="eye" />
          <span>{t('admin.review.evidence')}</span>
        </button>
        {entry.status !== 'VALID' && (
          <button
            type="button"
            className="button button--primary"
            aria-label={actionAria(t, t(rejected ? 'admin.review.undoReject' : 'admin.review.approve'), item, timeZone, lang)}
            onClick={() => onOverlay({ kind: 'action', item, mode: rejected ? 'undoReject' : 'approve' })}
          >
            <Icon name="check" />
            <span>{t(rejected ? 'admin.review.undoReject' : 'admin.review.approve')}</span>
          </button>
        )}
        {!rejected && (
          <button
            type="button"
            className="button button--destructive"
            aria-label={actionAria(t, t('admin.review.reject'), item, timeZone, lang)}
            onClick={() => onOverlay({ kind: 'action', item, mode: 'reject' })}
          >
            <Icon name="x-circle" />
            <span>{t('admin.review.reject')}</span>
          </button>
        )}
        <button
          type="button"
          className="button button--secondary"
          aria-label={actionAria(t, t('admin.review.correct'), item, timeZone, lang)}
          onClick={() => onOverlay({ kind: 'action', item, mode: 'correct' })}
        >
          <AdminIcon name="pencil" />
          <span>{t('admin.review.correct')}</span>
        </button>
        {loneClockIn && (
          <button
            type="button"
            className="button button--secondary"
            onClick={() =>
              onOverlay({
                kind: 'create',
                target: {
                  employee: { id: item.employee.id, name: item.employee.name, code: item.employee.code, active: true },
                  hasBranch: true,
                  type: 'CLOCK_OUT',
                  workDate: item.workDate,
                  isToday: false,
                  existingIn: entry.effectiveTime,
                },
              })
            }
          >
            <span>{t('admin.review.create')}</span>
          </button>
        )}
      </div>
    </article>
  )
}

export function ReviewQueuePage() {
  const { t } = useAdminI18n()
  const { announce } = useAnnouncer()
  const shell = useAdminShell()
  const session = useSession()
  const timeZone = session.identity?.timeZone ?? 'America/Costa_Rica'
  const [params, setParams] = useSearchParams()
  const status: ReviewQuery['status'] = params.get('status') === 'REJECTED' ? 'REJECTED' : 'PENDING_REVIEW'
  const from = ISO_DATE.test(params.get('from') ?? '') ? params.get('from') : null
  const to = ISO_DATE.test(params.get('to') ?? '') ? params.get('to') : null
  const employeeParam = Number(params.get('employee'))
  const employeeId = Number.isInteger(employeeParam) && employeeParam > 0 ? employeeParam : null
  const pageParam = Number(params.get('page'))
  const page = Number.isInteger(pageParam) && pageParam >= 1 ? pageParam - 1 : 0

  const [overlay, setOverlay] = useState<Overlay>(null)
  const [changedBox, setChangedBox] = useState(false)
  const [resetKey, setResetKey] = useState(0)
  const [employees, setEmployees] = useState<EmployeeOption[] | null>(null)
  const listHeading = useRef<HTMLHeadingElement>(null)
  const focusIndex = useRef<number | null>(null)
  const ids = { status: useId(), from: useId(), to: useId(), employee: useId() }

  const key = `${status}|${from}|${to}|${employeeId}|${page}`
  const query = useAdminQuery(() => fetchReviewQueue({ status, from, to, employeeId, page }), key)
  const data = query.data
  const { reload } = query
  const total = data?.totalItems ?? 0
  const pages = Math.max(1, Math.ceil(total / REVIEW_PAGE_SIZE))

  const { setDialogOpen } = shell
  useEffect(() => {
    setDialogOpen(overlay !== null)
    return () => setDialogOpen(false)
  }, [overlay, setDialogOpen])

  // Announce the result of a filter or page change; move focus where the last action asked.
  const announcedKey = useRef<string | null>(null)
  useEffect(() => {
    if (!data || query.loading) return
    if (announcedKey.current !== null && announcedKey.current !== key) {
      announce(t(total === 1 ? 'admin.review.count.one' : 'admin.review.count.other', { n: total }))
    }
    announcedKey.current = key
    if (focusIndex.current !== null) {
      const headings = document.querySelectorAll<HTMLElement>('[data-card-index]')
      const target = headings[Math.min(focusIndex.current, headings.length - 1)] ?? listHeading.current
      target?.focus()
      focusIndex.current = null
    }
  }, [data, query.loading, key, total, announce, t])

  function setParam(name: string, value: string | null, keepPage = false) {
    setParams(
      (prev) => {
        const next = new URLSearchParams(prev)
        if (value) next.set(name, value)
        else next.delete(name)
        if (!keepPage) next.delete('page')
        return next
      },
      { replace: true },
    )
  }

  function loadEmployees() {
    if (employees !== null) return
    void fetchEmployees().then(setEmployees, () => setEmployees([]))
  }

  function actionDone(index: number, mode: ActionMode) {
    setOverlay(null)
    const remaining = Math.max(0, total - 1)
    const base = t(
      mode === 'reject' ? 'admin.review.rejected' : mode === 'correct' ? 'admin.review.corrected' : mode === 'undoReject' ? 'admin.review.undone' : 'admin.review.approved',
    )
    announce(`${base} ${t(remaining === 1 ? 'admin.review.remaining.one' : 'admin.review.remaining.other', { n: remaining })}`)
    focusIndex.current = index
    setChangedBox(false)
    reload()
    shell.refreshPending()
  }

  const filtered = from !== null || to !== null || employeeId !== null || status !== 'PENDING_REVIEW'
  const items = data?.items ?? []
  const index = (item: ReviewItem) => items.indexOf(item)

  return (
    <>
      <PageHeading title={t('admin.review.title')}>
        <p className="adm-sub">{t('admin.review.intro')}</p>
      </PageHeading>

      <fieldset className="adm-filters">
        <legend className="visually-hidden">{t('admin.review.filters')}</legend>
        <div className="field">
          <label htmlFor={ids.status}>{t('admin.review.status')}</label>
          <select id={ids.status} value={status} onChange={(event) => setParam('status', event.target.value === 'REJECTED' ? 'REJECTED' : null)}>
            <option value="PENDING_REVIEW">{t('admin.review.statusPending')}</option>
            <option value="REJECTED">{t('admin.review.statusRejected')}</option>
          </select>
        </div>
        <div className="field">
          <label htmlFor={ids.from}>{t('admin.review.from')}</label>
          <DateFilter key={`from-${resetKey}`} id={ids.from} applied={from} today={shell.today} onCommit={(value) => setParam('from', value)} />
        </div>
        <div className="field">
          <label htmlFor={ids.to}>{t('admin.review.to')}</label>
          <DateFilter key={`to-${resetKey}`} id={ids.to} applied={to} today={shell.today} onCommit={(value) => setParam('to', value)} />
        </div>
        <div className="field">
          <label htmlFor={ids.employee}>{t('admin.review.employee')}</label>
          <select
            id={ids.employee}
            value={employeeId ?? ''}
            onFocus={loadEmployees}
            onChange={(event) => setParam('employee', event.target.value || null)}
          >
            <option value="">{t('admin.review.allEmployees')}</option>
            {employeeId !== null && !employees?.some((e) => e.id === employeeId) && <option value={employeeId}>{employeeId}</option>}
            {employees?.map((e) => (
              <option key={e.id} value={e.id}>
                {e.name} ({e.code})
              </option>
            ))}
          </select>
        </div>
        <button type="button" className="button button--secondary" onClick={() => {
            setResetKey((n) => n + 1)
            setParams({}, { replace: true })
          }}>
          {t('admin.review.clear')}
        </button>
      </fieldset>

      {changedBox && <ErrorBox>{t('admin.review.changedBox')}</ErrorBox>}
      {query.error !== null && <QueryError error={query.error} onRetry={reload} />}

      <h2 ref={listHeading} tabIndex={-1} className="adm-h2">
        {t('admin.review.list')}
      </h2>
      {data && <p className="adm-hint">{t(total === 1 ? 'admin.review.count.one' : 'admin.review.count.other', { n: total })}</p>}

      <div className={query.loading && data ? 'adm-dim' : undefined} aria-busy={query.loading || undefined}>
        {query.loading && !data && (
          <p role="status" className="adm-hint">
            <Icon name="spinner" />
            <span>{t('admin.review.loading')}</span>
          </p>
        )}
        {data && items.length === 0 && !filtered && (
          <div className="notice notice--info">
            <Icon name="check-circle" />
            <div>
              <p>
                <strong>{t('admin.review.empty')}</strong>
              </p>
              <p>{t('admin.review.emptyHelp')}</p>
            </div>
          </div>
        )}
        {data && items.length === 0 && filtered && (
          <div className="notice notice--info">
            <Icon name="info" />
            <span>{t('admin.review.emptyFiltered')}</span>
          </div>
        )}
        <ul className="adm-review-list">
          {items.map((item) => (
            <li key={item.entry.id}>
              <ReviewCard item={item} index={index(item)} timeZone={timeZone} onOverlay={setOverlay} />
            </li>
          ))}
        </ul>
      </div>

      <Pagination
        page={page}
        pages={pages}
        onPage={(next) => {
          focusIndex.current = -1
          setParam('page', next > 0 ? String(next + 1) : null, true)
        }}
      />

      {overlay?.kind === 'evidence' && (
        <EvidenceDialog
          entryId={overlay.item.entry.id}
          employeeName={overlay.item.employee.name}
          timeZone={timeZone}
          onClose={() => setOverlay(null)}
          onChanged={() => {
            reload()
            shell.refreshPending()
          }}
        />
      )}
      {overlay?.kind === 'action' && (
        <ReviewActionDialog
          mode={overlay.mode}
          target={toTarget(overlay.item)}
          ownEntry={overlay.item.ownEntry}
          timeZone={timeZone}
          onCancel={() => setOverlay(null)}
          onEntryChanged={() => {
            const at = index(overlay.item)
            setOverlay(null)
            setChangedBox(true)
            focusIndex.current = at
            reload()
            shell.refreshPending()
          }}
          onDone={(_detail, mode) => actionDone(index(overlay.item), mode)}
        />
      )}
      {overlay?.kind === 'create' && (
        <CreateEntryDialog
          target={overlay.target}
          timeZone={timeZone}
          onCancel={() => setOverlay(null)}
          onRefresh={() => {
            setOverlay(null)
            reload()
          }}
          onDone={() => {
            const name = overlay.target.employee.name
            setOverlay(null)
            announce(t('admin.create.doneOut', { name }))
            reload()
            shell.refreshPending()
          }}
        />
      )}
    </>
  )
}
