import { useEffect, useId, useMemo, useRef, useState } from 'react'
import { Link, useSearchParams } from 'react-router'
import { useSession } from '../../auth/useSession'
import { useAnnouncer } from '../../components/announcerContext'
import { Icon } from '../../components/Icon'
import { AdminIcon } from '../AdminIcon'
import { useAdminI18n } from '../adminI18n'
import { fetchBranches, fetchEmployees } from '../api/adminApi'
import type { AdminEmployeeView, CreatedEmployee } from '../api/adminTypes'
import { Chip } from '../components/Chips'
import { EmployeeFormDialog } from '../components/EmployeeFormDialog'
import { PageHeading, QueryError } from '../components/PageParts'
import { PinRevealDialog } from '../components/PinRevealDialog'
import { initials, timeOf } from '../format'
import { useAdminQuery } from '../hooks/useAdminQuery'
import { useMediaQuery } from '../hooks/useMediaQuery'
import { useAdminShell } from '../shell'

type StatusFilter = 'all' | 'active' | 'inactive' | 'locked'
const STATUSES: readonly StatusFilter[] = ['all', 'active', 'inactive', 'locked']
const STATUS_KEY = {
  all: 'admin.employees.filterAll',
  active: 'admin.employees.filterActive',
  inactive: 'admin.employees.filterInactive',
  locked: 'admin.employees.filterLocked',
} as const satisfies Record<StatusFilter, string>

/** Case and accent insensitive, for the in-memory search. */
function fold(text: string): string {
  return text.normalize('NFD').replace(/\p{M}/gu, '').toLocaleLowerCase()
}

type Reveal = { pin: string; employee: AdminEmployeeView }

function Avatar({ name }: { name: string }) {
  return (
    <span className="adm-avatar" aria-hidden="true">
      {initials(name)}
    </span>
  )
}

function StateChips({ employee, timeZone }: { employee: AdminEmployeeView; timeZone: string }) {
  const { t, lang } = useAdminI18n()
  return (
    <>
      {employee.active ? (
        <Chip family="teal" icon="check-circle">
          {t('admin.employee.stateActive')}
        </Chip>
      ) : (
        <Chip family="grey" icon="circle">
          {t('admin.employee.stateInactive')}
        </Chip>
      )}
      {employee.locked && employee.lockedUntil && (
        <Chip family="redbrown" icon="lock">
          {t('admin.employee.locked', { time: timeOf(employee.lockedUntil, timeZone, lang) })}
        </Chip>
      )}
    </>
  )
}

function branchText(employee: AdminEmployeeView, t: ReturnType<typeof useAdminI18n>['t']): string {
  if (employee.branchName === null) return t('admin.employees.noBranch')
  return employee.branchActive === false ? t('admin.employee.branchInactiveOption', { name: employee.branchName }) : employee.branchName
}

export function EmployeesPage() {
  const { t } = useAdminI18n()
  const { announce } = useAnnouncer()
  const shell = useAdminShell()
  const session = useSession()
  const timeZone = session.identity?.timeZone ?? 'America/Costa_Rica'
  const wide = useMediaQuery('(min-width: 48rem)')
  const [params, setParams] = useSearchParams()
  const branchParam = Number(params.get('branch'))
  const branchFilter = Number.isInteger(branchParam) && branchParam > 0 ? branchParam : null
  // The search text lives in component state only: a name or a code never goes into the URL.
  const [search, setSearch] = useState('')
  const [status, setStatus] = useState<StatusFilter>('all')
  const query = useAdminQuery(() => fetchEmployees(), 'employees')
  const branchesQuery = useAdminQuery(() => fetchBranches(), 'branches-for-employees')
  const [rows, setRows] = useState<AdminEmployeeView[] | null>(null)
  const [creating, setCreating] = useState(false)
  const [reveal, setReveal] = useState<Reveal | null>(null)
  const newButton = useRef<HTMLButtonElement>(null)
  const focusLink = useRef<number | null>(null)
  const ids = { search: useId(), branch: useId(), status: useId() }

  const { data, reload } = query
  useEffect(() => {
    if (data) setRows(data)
  }, [data])

  const { setDialogOpen } = shell
  useEffect(() => {
    setDialogOpen(creating || reveal !== null)
    return () => setDialogOpen(false)
  }, [creating, reveal, setDialogOpen])

  const branches = branchesQuery.data ?? []
  const visible = useMemo(() => {
    const needle = fold(search.trim())
    return (rows ?? []).filter((e) => {
      if (needle && !fold(e.name).includes(needle) && !fold(e.code).includes(needle)) return false
      if (status === 'active' && !e.active) return false
      if (status === 'inactive' && e.active) return false
      if (status === 'locked' && !e.locked) return false
      return branchFilter === null || e.branchId === branchFilter
    })
  }, [rows, search, status, branchFilter])

  // Polite count after the filters settle (400 ms), never containing the search text.
  const first = useRef(true)
  useEffect(() => {
    if (first.current || !rows) {
      first.current = !rows
      return
    }
    const timer = setTimeout(() => {
      announce(t(visible.length === 1 ? 'admin.employees.count.one' : 'admin.employees.count.other', { n: visible.length }))
    }, 400)
    return () => clearTimeout(timer)
    // The announcement follows the filters, not the language or the announcer identity.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [search, status, branchFilter, rows])

  useEffect(() => {
    if (focusLink.current === null) return
    document.getElementById(`employee-link-${focusLink.current}`)?.focus()
    focusLink.current = null
  }, [rows, reveal])

  const filtered = search.trim() !== '' || status !== 'all' || branchFilter !== null
  function clearFilters() {
    setSearch('')
    setStatus('all')
    setParams({}, { replace: true })
  }

  function created(result: CreatedEmployee) {
    setCreating(false)
    setRows((current) => [...(current ?? []), result.employee].sort((a, b) => a.name.localeCompare(b.name)))
    // The PIN goes to the dialog only; the list receives just the employee.
    setReveal({ pin: result.pin, employee: result.employee })
  }

  function closeReveal() {
    const id = reveal?.employee.id ?? null
    setReveal(null)
    focusLink.current = id
    announce(t('admin.pin.statusCreated'))
  }

  const count = visible.length
  return (
    <>
      <PageHeading title={t('admin.employees.title')}>
        <button ref={newButton} type="button" className="button button--primary" onClick={() => setCreating(true)}>
          <AdminIcon name="plus" />
          <span>{t('admin.employees.new')}</span>
        </button>
      </PageHeading>

      <div className="adm-filters">
        <div className="field">
          <label htmlFor={ids.search}>{t('admin.employees.search')}</label>
          <input
            id={ids.search}
            type="search"
            autoComplete="off"
            spellCheck={false}
            value={search}
            onChange={(event) => setSearch(event.target.value)}
          />
        </div>
        <fieldset className="adm-segmented">
          <legend>{t('admin.employees.filter')}</legend>
          {STATUSES.map((value) => (
            <label key={value}>
              <input type="radio" name={ids.status} checked={status === value} onChange={() => setStatus(value)} />
              <span>{t(STATUS_KEY[value])}</span>
            </label>
          ))}
        </fieldset>
        <div className="field">
          <label htmlFor={ids.branch}>{t('admin.employees.branchFilter')}</label>
          <select
            id={ids.branch}
            value={branchFilter ?? ''}
            onChange={(event) => setParams(event.target.value ? { branch: event.target.value } : {}, { replace: true })}
          >
            <option value="">{t('admin.employees.allBranches')}</option>
            {branches.map((branch) => (
              <option key={branch.id} value={branch.id}>
                {branch.active ? branch.name : t('admin.employee.branchInactiveOption', { name: branch.name })}
              </option>
            ))}
          </select>
        </div>
        {filtered && (
          <button type="button" className="button button--secondary" onClick={clearFilters}>
            {t('admin.review.clear')}
          </button>
        )}
      </div>

      {query.error !== null && <QueryError error={query.error} onRetry={reload} />}
      {rows && <p className="adm-hint">{t(count === 1 ? 'admin.employees.count.one' : 'admin.employees.count.other', { n: count })}</p>}
      {query.loading && !rows && (
        <p role="status" className="adm-hint">
          <Icon name="spinner" />
          <span>{t('admin.employees.loading')}</span>
        </p>
      )}
      {rows && rows.length === 0 && (
        <div className="notice notice--info">
          <Icon name="info" />
          <span>{t('admin.employees.empty')}</span>
        </div>
      )}
      {rows && rows.length > 0 && visible.length === 0 && (
        <div className="notice notice--info">
          <Icon name="info" />
          <span>{t('admin.employees.emptyFiltered')}</span>
        </div>
      )}

      {visible.length > 0 && wide && (
        <div className="adm-table-wrap">
          <table className="adm-table">
            <caption className="visually-hidden">{t('admin.employees.caption')}</caption>
            <thead>
              <tr>
                <th scope="col">{t('admin.employees.col.employee')}</th>
                <th scope="col">{t('admin.employees.col.role')}</th>
                <th scope="col">{t('admin.employees.col.branch')}</th>
                <th scope="col">{t('admin.employees.col.status')}</th>
                <th scope="col">{t('admin.employees.col.schedule')}</th>
              </tr>
            </thead>
            <tbody>
              {visible.map((employee) => (
                <tr key={employee.id}>
                  <th scope="row">
                    <span className="adm-employee">
                      <Avatar name={employee.name} />
                      <span>
                        <Link id={`employee-link-${employee.id}`} className="adm-employee__name" to={`/admin/employees/${employee.id}`}>
                          {employee.name}
                        </Link>
                        <span className="adm-employee__title">{[employee.code, employee.jobTitle].filter(Boolean).join(' · ')}</span>
                      </span>
                    </span>
                  </th>
                  <td>{t(employee.role === 'ADMIN' ? 'admin.employee.roleAdmin' : 'admin.employee.roleEmployee')}</td>
                  <td>{branchText(employee, t)}</td>
                  <td>
                    <div className="adm-chip-row">
                      <StateChips employee={employee} timeZone={timeZone} />
                    </div>
                    <span className="adm-hint">
                      {t('admin.employees.consent', { value: t(employee.consentGiven ? 'admin.employees.yes' : 'admin.employees.no') })}
                    </span>
                  </td>
                  <td>{t(employee.hasSchedule ? 'admin.employees.yes' : 'admin.employees.no')}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {visible.length > 0 && !wide && (
        <ul className="adm-card-list">
          {visible.map((employee) => (
            <li key={employee.id}>
              <article className="adm-card-item" aria-labelledby={`employee-card-${employee.id}`}>
                <h3 id={`employee-card-${employee.id}`}>
                  <Link id={`employee-link-${employee.id}`} to={`/admin/employees/${employee.id}`}>
                    {employee.name}
                  </Link>
                </h3>
                <p className="adm-hint">{[employee.code, employee.jobTitle].filter(Boolean).join(' · ')}</p>
                <dl className="adm-facts">
                  <div>
                    <dt>{t('admin.employees.col.role')}</dt>
                    <dd>{t(employee.role === 'ADMIN' ? 'admin.employee.roleAdmin' : 'admin.employee.roleEmployee')}</dd>
                  </div>
                  <div>
                    <dt>{t('admin.employees.col.branch')}</dt>
                    <dd>{branchText(employee, t)}</dd>
                  </div>
                  <div>
                    <dt>{t('admin.employees.col.status')}</dt>
                    <dd className="adm-chip-row">
                      <StateChips employee={employee} timeZone={timeZone} />
                    </dd>
                  </div>
                  <div>
                    <dt>{t('admin.employee.consentLabel')}</dt>
                    <dd>{t(employee.consentGiven ? 'admin.employees.yes' : 'admin.employees.no')}</dd>
                  </div>
                  <div>
                    <dt>{t('admin.employees.col.schedule')}</dt>
                    <dd>{t(employee.hasSchedule ? 'admin.employees.yes' : 'admin.employees.no')}</dd>
                  </div>
                </dl>
              </article>
            </li>
          ))}
        </ul>
      )}

      {creating && (
        <EmployeeFormDialog
          branches={branches}
          onCreated={created}
          onCancel={(refresh) => {
            setCreating(false)
            if (refresh) reload()
            newButton.current?.focus()
          }}
        />
      )}
      {reveal && (
        <PinRevealDialog
          mode="created"
          name={reveal.employee.name}
          code={reveal.employee.code}
          pin={reveal.pin}
          self={false}
          onClose={closeReveal}
        />
      )}
    </>
  )
}
