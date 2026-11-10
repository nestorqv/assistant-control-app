import { useEffect, useId, useMemo, useRef, useState } from 'react'
import { useAnnouncer } from '../../components/announcerContext'
import { ErrorBox } from '../../components/ErrorBox'
import { Icon } from '../../components/Icon'
import { AdminIcon } from '../AdminIcon'
import { useAdminI18n } from '../adminI18n'
import { fetchSchedule, saveSchedule } from '../api/adminApi'
import type { AdminEmployeeView, ScheduleDay } from '../api/adminTypes'
import { adminErrorText } from '../errors'
import { useAdminQuery } from '../hooks/useAdminQuery'
import { useMediaQuery } from '../hooks/useMediaQuery'
import { useAdminShell } from '../shell'
import { DAYS, DEFAULT_ROW, gridFromWeek, isOvernight, normalizeWeek, rowProblem, weekFromGrid, type DayRow, type RowProblem } from './scheduleGrid'
import { WriteError } from './WriteError'

type Props = {
  employeeId: number
  /** the other employees, for "copy schedule from" */
  others: AdminEmployeeView[]
}

function weekdayNames(lang: string): string[] {
  const format = new Intl.DateTimeFormat(lang, { weekday: 'long', timeZone: 'UTC' })
  // 2024-01-01 is a Monday.
  return DAYS.map((_, index) => {
    const name = format.format(new Date(Date.UTC(2024, 0, 1 + index)))
    return name.charAt(0).toLocaleUpperCase(lang) + name.slice(1)
  })
}

const PROBLEM_KEY = {
  time: 'admin.schedule.errorTime',
  same: 'admin.schedule.errorSame',
  grace: 'admin.schedule.errorGrace',
} as const satisfies Record<RowProblem, string>

/**
 * Weekly schedule: one row per ISO weekday. The PUT sends the whole new week plus the week that was loaded
 * (the server compares it under the employee lock). While the grid differs from the loaded week the shell
 * guards navigation and reload (no data router, so no router blocker).
 */
export function ScheduleEditor({ employeeId, others }: Props) {
  const { t, lang } = useAdminI18n()
  const { announce } = useAnnouncer()
  const shell = useAdminShell()
  const wide = useMediaQuery('(min-width: 48rem)')
  const query = useAdminQuery(() => fetchSchedule(employeeId), `schedule-${employeeId}`)
  const [loaded, setLoaded] = useState<ScheduleDay[] | null>(null)
  const [grid, setGrid] = useState<DayRow[]>(() => DAYS.map(() => ({ ...DEFAULT_ROW })))
  const [problems, setProblems] = useState<(RowProblem | null)[]>([])
  const [summaryShown, setSummaryShown] = useState(false)
  const [writeError, setWriteError] = useState<unknown>(null)
  const [attempt, setAttempt] = useState(0)
  const [busy, setBusy] = useState(false)
  const [copyFrom, setCopyFrom] = useState('')
  const summary = useRef<HTMLDivElement>(null)
  const idBase = useId()
  const names = useMemo(() => weekdayNames(lang), [lang])

  const { data } = query
  useEffect(() => {
    if (data) {
      setLoaded(data)
      setGrid(gridFromWeek(data))
    }
  }, [data])

  const dirty = loaded !== null && normalizeWeek(weekFromGrid(grid)) !== normalizeWeek(loaded)
  const { setUnsavedChanges } = shell
  useEffect(() => {
    setUnsavedChanges('schedule', dirty)
    return () => setUnsavedChanges('schedule', false)
  }, [dirty, setUnsavedChanges])

  useEffect(() => {
    if (summaryShown) summary.current?.focus()
  }, [summaryShown, attempt])

  function update(index: number, patch: Partial<DayRow>) {
    setGrid((current) => current.map((row, i) => (i === index ? { ...row, ...patch } : row)))
    setProblems([])
    setSummaryShown(false)
  }

  function discard() {
    if (!dirty || !loaded) return
    setGrid(gridFromWeek(loaded))
    setProblems([])
    setSummaryShown(false)
    setWriteError(null)
  }

  async function save() {
    if (busy || !dirty || !loaded) return
    const found = grid.map(rowProblem)
    setProblems(found)
    if (found.some(Boolean)) {
      setAttempt((n) => n + 1)
      setSummaryShown(true)
      return
    }
    setBusy(true)
    setWriteError(null)
    try {
      const saved = await saveSchedule(employeeId, loaded, weekFromGrid(grid))
      setLoaded(saved)
      setGrid(gridFromWeek(saved))
      announce(t('admin.schedule.saved'))
    } catch (error) {
      setAttempt((n) => n + 1)
      setWriteError(error)
    } finally {
      setBusy(false)
    }
  }

  async function reload() {
    try {
      const fresh = await fetchSchedule(employeeId)
      setLoaded(fresh)
      setGrid(gridFromWeek(fresh))
      setProblems([])
      setWriteError(null)
      announce(t('admin.recordChanged.reloaded'))
    } catch (error) {
      setAttempt((n) => n + 1)
      setWriteError(error)
    }
  }

  async function copy() {
    if (!copyFrom || busy) return
    try {
      const week = await fetchSchedule(Number(copyFrom))
      setGrid(gridFromWeek(week))
      setProblems([])
      setSummaryShown(false)
      setWriteError(null)
      announce(t('admin.schedule.copied'))
    } catch (error) {
      setAttempt((n) => n + 1)
      setWriteError(error)
    }
  }

  const fieldId = (index: number, part: string) => `${idBase}-${index}-${part}`

  function controls(index: number) {
    const row = grid[index]
    const day = names[index].toLocaleLowerCase(lang)
    const invalid = problems[index] ?? null
    const enabled = row.checked && !busy
    return {
      works: (
        <input
          id={fieldId(index, 'works')}
          type="checkbox"
          checked={row.checked}
          disabled={busy}
          aria-label={t('admin.schedule.worksAria', { day })}
          onChange={(event) => update(index, { checked: event.target.checked })}
        />
      ),
      start: (
        <input
          id={fieldId(index, 'start')}
          type="time"
          step={60}
          value={row.start}
          disabled={!enabled}
          aria-label={t('admin.schedule.startAria', { day })}
          aria-invalid={invalid === 'time' || invalid === 'same' ? true : undefined}
          onChange={(event) => update(index, { start: event.target.value })}
        />
      ),
      end: (
        <input
          id={fieldId(index, 'end')}
          type="time"
          step={60}
          value={row.end}
          disabled={!enabled}
          aria-label={t('admin.schedule.endAria', { day })}
          aria-invalid={invalid === 'time' || invalid === 'same' ? true : undefined}
          onChange={(event) => update(index, { end: event.target.value })}
        />
      ),
      grace: (
        <input
          id={fieldId(index, 'grace')}
          type="text"
          inputMode="numeric"
          autoComplete="off"
          value={row.grace}
          disabled={!enabled}
          aria-label={t('admin.schedule.graceAria', { day })}
          aria-invalid={invalid === 'grace' ? true : undefined}
          onChange={(event) => update(index, { grace: event.target.value })}
        />
      ),
      note: row.checked ? (
        isOvernight(row) ? (
          <span className="adm-schedule__note">
            <Icon name="hourglass" />
            <span>{t('admin.schedule.overnight')}</span>
          </span>
        ) : null
      ) : (
        <span className="adm-hint">{t('admin.schedule.dayOff')}</span>
      ),
      problem: invalid ? (
        <p className="field__error">
          <AdminIcon name="clock-alert" />
          <span>{t(PROBLEM_KEY[invalid])}</span>
        </p>
      ) : null,
    }
  }

  const failing = problems.map((p, index) => ({ p, index })).filter((x) => x.p !== null)
  const noneChecked = grid.every((row) => !row.checked)
  const dirtyId = `${idBase}-dirty`
  const pastId = `${idBase}-past`
  return (
    <section className="adm-section adm-schedule-section" aria-labelledby={`${idBase}-title`}>
      <div className="adm-schedule-head">
        <h2 id={`${idBase}-title`} className="adm-h2">
          {t('admin.schedule.title')}
        </h2>
        <div className="adm-schedule-copy">
          <label className="visually-hidden" htmlFor={`${idBase}-copy`}>
            {t('admin.schedule.copyFrom')}
          </label>
          <select id={`${idBase}-copy`} value={copyFrom} disabled={busy} onChange={(event) => setCopyFrom(event.target.value)}>
            <option value="">{t('admin.schedule.copyFrom')}</option>
            {others.map((other) => (
              <option key={other.id} value={String(other.id)}>
                {other.name}
              </option>
            ))}
          </select>
          <button type="button" className="button button--secondary" aria-disabled={!copyFrom || busy} onClick={() => void copy()}>
            {t('admin.schedule.copy')}
          </button>
        </div>
      </div>
      <p className="adm-sub">{t('admin.schedule.intro')}</p>

      {query.loading && !loaded && (
        <p role="status" className="adm-hint">
          <Icon name="spinner" />
          <span>{t('admin.schedule.loading')}</span>
        </p>
      )}
      {query.error !== null && !loaded && <ErrorBox>{adminErrorText(query.error, t)}</ErrorBox>}
      {writeError !== null && <WriteError error={writeError} attempt={attempt} onReload={() => void reload()} />}

      {summaryShown && failing.length > 0 && (
        <div ref={summary} role="alert" tabIndex={-1} className="notice notice--error adm-schedule-summary">
          <Icon name="x-circle" />
          <div>
            <p>{t('admin.schedule.errorSummary')}</p>
            <ul>
              {failing.map(({ p, index }) => (
                <li key={index}>
                  <button type="button" className="adm-link-button" onClick={() => document.getElementById(fieldId(index, p === 'grace' ? 'grace' : 'start'))?.focus()}>
                    {t('admin.schedule.errorSummaryItem', { day: names[index], message: t(PROBLEM_KEY[p as RowProblem]) })}
                  </button>
                </li>
              ))}
            </ul>
          </div>
        </div>
      )}

      {loaded && wide && (
        <div className="adm-table-wrap">
          <table className="adm-table adm-schedule">
            <caption className="visually-hidden">{t('admin.schedule.title')}</caption>
            <thead>
              <tr>
                <th scope="col">{t('admin.schedule.day')}</th>
                <th scope="col">{t('admin.schedule.worksDay')}</th>
                <th scope="col">{t('admin.schedule.start')}</th>
                <th scope="col">{t('admin.schedule.end')}</th>
                <th scope="col">{t('admin.schedule.grace')}</th>
                <th scope="col">{t('admin.schedule.note')}</th>
              </tr>
            </thead>
            <tbody>
              {DAYS.map((_, index) => {
                const c = controls(index)
                return (
                  <tr key={index} className={problems[index] ? 'adm-schedule__row--error' : undefined}>
                    <th scope="row">{names[index]}</th>
                    <td>{c.works}</td>
                    <td>{c.start}</td>
                    <td>{c.end}</td>
                    <td>
                      {c.grace}
                      {c.problem}
                    </td>
                    <td>{c.note}</td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
      )}

      {loaded && !wide && (
        <div className="adm-schedule-cards">
          {DAYS.map((_, index) => {
            const c = controls(index)
            return (
              <fieldset key={index} className={`adm-schedule__day${problems[index] ? ' adm-schedule__row--error' : ''}`}>
                <legend>{names[index]}</legend>
                <label className="adm-check">
                  {c.works}
                  <span>{t('admin.schedule.worksDay')}</span>
                </label>
                <div className="adm-schedule__times">
                  <label>
                    <span>{t('admin.schedule.start')}</span>
                    {c.start}
                  </label>
                  <label>
                    <span>{t('admin.schedule.end')}</span>
                    {c.end}
                  </label>
                </div>
                <label>
                  <span>{t('admin.schedule.grace')}</span>
                  {c.grace}
                </label>
                {c.problem}
                {c.note}
              </fieldset>
            )
          })}
        </div>
      )}

      {loaded && noneChecked && (
        <p className="notice notice--info adm-note">
          <Icon name="info" />
          <span>{t('admin.schedule.noneHint')}</span>
        </p>
      )}
      <p className="adm-hint" id={pastId}>
        {t('admin.schedule.pastNote')}
      </p>
      <div className="adm-schedule-actions">
        {dirty && (
          <span className="adm-hint" id={dirtyId}>
            {t('admin.schedule.dirty')}
          </span>
        )}
        <button
          type="button"
          className="button button--primary"
          aria-disabled={!dirty || busy || undefined}
          aria-busy={busy || undefined}
          aria-describedby={dirty ? `${dirtyId} ${pastId}` : pastId}
          onClick={() => void save()}
        >
          {busy && <Icon name="spinner" />}
          <span>{busy ? t('admin.action.saving') : t('admin.schedule.save')}</span>
        </button>
        <button type="button" className="button button--secondary" aria-disabled={!dirty || busy || undefined} onClick={discard}>
          {t('admin.schedule.discard')}
        </button>
      </div>
    </section>
  )
}
