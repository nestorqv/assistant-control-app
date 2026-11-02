import { useCallback, useEffect, useId, useRef, useState } from 'react'
import { useSearchParams } from 'react-router'
import { useAnnouncer } from '../../components/announcerContext'
import { Icon } from '../../components/Icon'
import { useSession } from '../../auth/useSession'
import { AdminIcon } from '../AdminIcon'
import { useAdminI18n } from '../adminI18n'
import { fetchBranches, fetchDashboard } from '../api/adminApi'
import type { DashboardResponse, DashboardRow } from '../api/adminTypes'
import { AttendanceList, AttendanceTable } from '../components/AttendanceTable'
import { CreateEntryDialog, type CreateTarget } from '../components/CreateEntryDialog'
import { EvidenceDialog } from '../components/EvidenceDialog'
import { KpiCards } from '../components/KpiCards'
import { PageHeading, QueryError } from '../components/PageParts'
import { addDays, localDateHeading, localDateSentence, timeOf } from '../format'
import { useAdminQuery } from '../hooks/useAdminQuery'
import { useMediaQuery } from '../hooks/useMediaQuery'
import { useAdminShell } from '../shell'

const REFRESH_MS = 60_000
const ISO_DATE = /^\d{4}-\d{2}-\d{2}$/

/** Opening the dialog needs the schedule of the day; the dashboard row already carries it. */
function createTargetFor(row: DashboardRow, type: 'CLOCK_IN' | 'CLOCK_OUT', data: DashboardResponse): CreateTarget {
  const schedule = row.schedule
  const scheduled = schedule ? (type === 'CLOCK_IN' ? schedule.startTime : schedule.endTime).slice(0, 5) : null
  return {
    employee: { id: row.employee.id, name: row.employee.name, code: row.employee.code, active: row.employee.active },
    hasBranch: row.branchName !== null,
    type,
    workDate: data.date,
    isToday: data.date === data.today,
    existingIn: row.clockIn?.effectiveTime ?? null,
    scheduled,
  }
}

export function DashboardPage() {
  const { t, lang } = useAdminI18n()
  const { announce } = useAnnouncer()
  const shell = useAdminShell()
  const session = useSession()
  const timeZone = session.identity?.timeZone ?? 'America/Costa_Rica'
  const [params, setParams] = useSearchParams()
  const dateParam = params.get('date')
  const date = dateParam && ISO_DATE.test(dateParam) ? dateParam : null
  const branchParam = Number(params.get('branch'))
  const branch = Number.isInteger(branchParam) && branchParam > 0 ? branchParam : null

  const [auto, setAuto] = useState(true)
  const [evidenceId, setEvidenceId] = useState<{ id: number; name: string } | null>(null)
  const [createTarget, setCreateTarget] = useState<CreateTarget | null>(null)
  const desktopLayout = useMediaQuery('(min-width: 48rem)')
  const autoId = useId()
  const dateId = useId()
  const branchId = useId()

  const key = `${date ?? ''}|${branch ?? ''}`
  const query = useAdminQuery(() => fetchDashboard(date, branch), key)
  const branches = useAdminQuery(() => fetchBranches(), 'branches')
  const data = query.data
  const { reload } = query

  const { setDialogOpen } = shell
  const dialogOpen = evidenceId !== null || createTarget !== null
  useEffect(() => {
    setDialogOpen(dialogOpen)
    return () => setDialogOpen(false)
  }, [dialogOpen, setDialogOpen])

  // Reload on visibility, and every minute while visible, for today only. Paused while a dialog is open.
  const live = useRef({ auto, today: data?.date === data?.today, paused: shell.dialogOpen })
  useEffect(() => {
    live.current = { auto, today: data !== null && data.date === data.today, paused: shell.dialogOpen }
  })
  useEffect(() => {
    const maybeReload = () => {
      const { auto: on, today, paused } = live.current
      if (on && today && !paused && document.visibilityState === 'visible') reload()
    }
    document.addEventListener('visibilitychange', maybeReload)
    const timer = setInterval(maybeReload, REFRESH_MS)
    return () => {
      document.removeEventListener('visibilitychange', maybeReload)
      clearInterval(timer)
    }
  }, [reload])

  // Announce once per date or branch change (not for refreshes, and not for the very first load).
  const announcedKey = useRef<string | null>(null)
  useEffect(() => {
    if (!data || query.loading) return
    if (announcedKey.current !== null && announcedKey.current !== key) {
      const n = data.rows.length
      announce(t(n === 1 ? 'admin.dashboard.loaded.one' : 'admin.dashboard.loaded.other', { date: localDateSentence(data.date, lang), n }))
    }
    announcedKey.current = key
  }, [data, query.loading, key, announce, t, lang])

  const setParam = useCallback(
    (name: 'date' | 'branch', value: string | null) => {
      setParams(
        (prev) => {
          const next = new URLSearchParams(prev)
          if (value) next.set(name, value)
          else next.delete(name)
          return next
        },
        { replace: true },
      )
    },
    [setParams],
  )

  const shown = data?.date ?? date ?? ''
  const isToday = data !== null && data.date === data.today
  const branchName = branch !== null ? (branches.data?.find((b) => b.id === branch)?.name ?? '') : t('admin.dashboard.allBranches')
  const count = data?.rows.length ?? 0
  const caption = data
    ? t('admin.table.caption', {
        date: localDateHeading(data.date, lang),
        branch: branchName,
        count: t(count === 1 ? 'admin.dashboard.count.one' : 'admin.dashboard.count.other', { n: count }),
      })
    : ''
  const title = data && !isToday ? t('admin.dashboard.titleDate', { date: localDateHeading(data.date, lang) }) : t('admin.dashboard.titleToday')
  const staleError = query.error !== null && data !== null

  function changed() {
    reload()
    shell.refreshPending()
  }

  return (
    <>
      <PageHeading title={title}>
        {data && (
          <p className="adm-sub">
            {t('admin.dashboard.subtitle', { company: data.companyName, branch: branchName })}
          </p>
        )}
      </PageHeading>

      <div className="adm-toolbar" role="group" aria-label={t('admin.dashboard.date')}>
        <div className="adm-toolbar__date">
          <button
            type="button"
            className="adm-icon-button"
            aria-label={t('admin.dashboard.prevDay')}
            disabled={!shown}
            onClick={() => setParam('date', addDays(shown, -1))}
          >
            <AdminIcon name="chevron-left" />
          </button>
          <div className="field">
            <label htmlFor={dateId}>{t('admin.dashboard.date')}</label>
            <input
              id={dateId}
              type="date"
              value={shown}
              onChange={(event) => setParam('date', event.target.value || null)}
            />
          </div>
          <button
            type="button"
            className="adm-icon-button"
            aria-label={t('admin.dashboard.nextDay')}
            disabled={!shown}
            onClick={() => setParam('date', addDays(shown, 1))}
          >
            <AdminIcon name="chevron-right" />
          </button>
          {data && !isToday && (
            <button type="button" className="button button--secondary" onClick={() => setParam('date', null)}>
              {t('admin.dashboard.today')}
            </button>
          )}
        </div>
        <div className="field">
          <label htmlFor={branchId}>{t('admin.dashboard.branch')}</label>
          <select id={branchId} value={branch ?? ''} onChange={(event) => setParam('branch', event.target.value || null)}>
            <option value="">{t('admin.dashboard.allBranches')}</option>
            {branches.data?.map((b) => (
              <option key={b.id} value={b.id}>
                {b.name}
              </option>
            ))}
          </select>
        </div>
        <button type="button" className="button button--secondary" aria-busy={query.refreshing || undefined} onClick={reload}>
          <Icon name={query.refreshing ? 'spinner' : 'refresh'} />
          <span>{t('admin.dashboard.refresh')}</span>
        </button>
        <div className="adm-check">
          <input id={autoId} type="checkbox" checked={auto} onChange={(event) => setAuto(event.target.checked)} />
          <label htmlFor={autoId}>{t('admin.dashboard.autoRefresh')}</label>
        </div>
        {query.updatedAt !== null && (
          <p className="adm-hint">
            {t('admin.dashboard.updatedAt', {
              time: timeOf(new Date(query.updatedAt).toISOString(), timeZone, lang),
            })}
          </p>
        )}
      </div>

      {query.error !== null && <QueryError error={query.error} onRetry={reload} />}
      {staleError && data && (
        <p className="adm-hint">{t('admin.dashboard.staleData', { time: timeOf(data.generatedAt, timeZone, lang) })}</p>
      )}

      <KpiCards kpis={data?.kpis ?? null} date={shown} loading={query.loading} />

      {data && !isToday && data.date > data.today && (
        <p className="notice notice--info">
          <Icon name="info" />
          <span>{t('admin.dashboard.futureDate')}</span>
        </p>
      )}

      <section className={query.loading || staleError ? 'adm-dim' : undefined} aria-busy={query.loading || undefined}>
        {query.loading && !data && (
          <p role="status" className="adm-hint">
            <Icon name="spinner" />
            <span>{t('admin.dashboard.loading')}</span>
          </p>
        )}
        {data && data.rows.length === 0 && (
          <p className="notice notice--info">
            <Icon name="info" />
            <span>{t(branch !== null ? 'admin.dashboard.emptyBranch' : 'admin.dashboard.empty')}</span>
          </p>
        )}
        {data && data.rows.length > 0 && (
          <>
            {desktopLayout ? (
              <AttendanceTable
                rows={data.rows}
                caption={caption}
                timeZone={timeZone}
                onEvidence={(id, name) => setEvidenceId({ id, name })}
                onCreate={(row, type) => setCreateTarget(createTargetFor(row, type, data))}
              />
            ) : (
              <AttendanceList
                rows={data.rows}
                caption={caption}
                timeZone={timeZone}
                onEvidence={(id, name) => setEvidenceId({ id, name })}
                onCreate={(row, type) => setCreateTarget(createTargetFor(row, type, data))}
              />
            )}
            <p className="adm-hint">{t(count === 1 ? 'admin.dashboard.count.one' : 'admin.dashboard.count.other', { n: count })}</p>
          </>
        )}
      </section>

      {evidenceId && (
        <EvidenceDialog
          entryId={evidenceId.id}
          employeeName={evidenceId.name}
          timeZone={timeZone}
          onClose={() => setEvidenceId(null)}
          onChanged={changed}
        />
      )}
      {createTarget && (
        <CreateEntryDialog
          target={createTarget}
          timeZone={timeZone}
          onCancel={() => setCreateTarget(null)}
          onRefresh={() => {
            setCreateTarget(null)
            changed()
          }}
          onDone={() => {
            const done = createTarget
            setCreateTarget(null)
            announce(t(done.type === 'CLOCK_IN' ? 'admin.create.doneIn' : 'admin.create.doneOut', { name: done.employee.name }))
            changed()
          }}
        />
      )}
    </>
  )
}
