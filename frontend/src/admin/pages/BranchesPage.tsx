import { useEffect, useId, useRef, useState } from 'react'
import { Link } from 'react-router'
import { useAnnouncer } from '../../components/announcerContext'
import { ConfirmDialog } from '../../components/ConfirmDialog'
import { Icon } from '../../components/Icon'
import { AdminIcon } from '../AdminIcon'
import { Chip } from '../components/Chips'
import { BranchFormDialog } from '../components/BranchFormDialog'
import { PageHeading, QueryError } from '../components/PageParts'
import { WriteError } from '../components/WriteError'
import { useAdminI18n } from '../adminI18n'
import { fetchBranches, setBranchActive } from '../api/adminApi'
import type { BranchView } from '../api/adminTypes'
import { problemCode } from '../errors'
import { useAdminQuery } from '../hooks/useAdminQuery'
import { useMediaQuery } from '../hooks/useMediaQuery'
import { useAdminShell } from '../shell'

type Overlay = { kind: 'form'; branch: BranchView | null } | { kind: 'deactivate'; branch: BranchView } | null

function StatusChip({ active }: { active: boolean }) {
  const { t } = useAdminI18n()
  return active ? (
    <Chip family="teal" icon="check-circle">
      {t('admin.branches.active')}
    </Chip>
  ) : (
    <Chip family="grey" icon="circle">
      {t('admin.branches.inactive')}
    </Chip>
  )
}

function ActiveCount({ branch }: { branch: BranchView }) {
  const { t } = useAdminI18n()
  if (branch.activeEmployees === 0) return <>{branch.activeEmployees}</>
  return (
    <Link to={`/admin/employees?branch=${branch.id}`}>
      {branch.activeEmployees}
      <span className="visually-hidden"> {t('admin.branches.activeCountSuffix')}</span>
    </Link>
  )
}

type ActionProps = {
  branch: BranchView
  busy: boolean
  noteId: string
  onEdit: (branch: BranchView) => void
  onToggle: (branch: BranchView) => void
}

function Actions({ branch, busy, noteId, onEdit, onToggle }: ActionProps) {
  const { t } = useAdminI18n()
  const inUse = branch.active && branch.activeEmployees > 0
  return (
    <div className="adm-row-actions">
      <button type="button" className="button button--secondary" aria-label={t('admin.branches.editAria', { name: branch.name })} onClick={() => onEdit(branch)}>
        {t('admin.branches.edit')}
      </button>
      <button
        type="button"
        id={`branch-toggle-${branch.id}`}
        className={`button ${branch.active ? 'button--destructive' : 'button--secondary'}`}
        aria-label={t(branch.active ? 'admin.branches.deactivateAria' : 'admin.branches.activateAria', { name: branch.name })}
        aria-disabled={inUse || busy || undefined}
        aria-describedby={inUse ? noteId : undefined}
        aria-busy={busy || undefined}
        onClick={() => {
          if (!inUse && !busy) onToggle(branch)
        }}
      >
        {busy && <Icon name="spinner" />}
        <span>{t(branch.active ? 'admin.branches.deactivate' : 'admin.branches.activate')}</span>
      </button>
    </div>
  )
}

export function BranchesPage() {
  const { t } = useAdminI18n()
  const { announce } = useAnnouncer()
  const shell = useAdminShell()
  const wide = useMediaQuery('(min-width: 48rem)')
  const query = useAdminQuery(() => fetchBranches(), 'branches')
  const [rows, setRows] = useState<BranchView[] | null>(null)
  const [overlay, setOverlay] = useState<Overlay>(null)
  const [busyId, setBusyId] = useState<number | null>(null)
  const [writeError, setWriteError] = useState<unknown>(null)
  const [attempt, setAttempt] = useState(0)
  const [listUpdated, setListUpdated] = useState(false)
  const newButton = useRef<HTMLButtonElement>(null)
  const focusAfter = useRef<string | null>(null)
  const noteBase = useId()

  const { data, reload } = query
  useEffect(() => {
    if (data) setRows(data)
  }, [data])

  const { setDialogOpen } = shell
  useEffect(() => {
    setDialogOpen(overlay !== null)
    return () => setDialogOpen(false)
  }, [overlay, setDialogOpen])

  // After a write the response replaced a row; the button that swapped label gets the focus back.
  useEffect(() => {
    if (!focusAfter.current) return
    document.getElementById(focusAfter.current)?.focus()
    focusAfter.current = null
  }, [rows])

  function replaceRow(saved: BranchView) {
    setRows((current) => {
      if (!current) return [saved]
      const exists = current.some((b) => b.id === saved.id)
      const next = exists ? current.map((b) => (b.id === saved.id ? saved : b)) : [...current, saved]
      return next.sort((a, b) => a.name.localeCompare(b.name))
    })
  }

  async function toggle(branch: BranchView) {
    const activate = !branch.active
    setOverlay(null)
    setBusyId(branch.id)
    setWriteError(null)
    setListUpdated(false)
    try {
      const saved = await setBranchActive(branch.id, activate, branch.version)
      focusAfter.current = `branch-toggle-${branch.id}`
      replaceRow(saved)
      announce(t(activate ? 'admin.branches.activated' : 'admin.branches.deactivated'))
    } catch (error) {
      setAttempt((n) => n + 1)
      setWriteError(error)
      // A stale version or a stale employee count: the list has no edits to lose, so it refetches by itself.
      const code = problemCode(error)
      if (code === 'RECORD_CHANGED' || code === 'BRANCH_IN_USE') {
        setListUpdated(code === 'RECORD_CHANGED')
        reload()
      }
    } finally {
      setBusyId(null)
    }
  }

  const count = rows?.length ?? 0
  const items = rows ?? []
  return (
    <>
      <PageHeading title={t('admin.branches.title')}>
        <button ref={newButton} type="button" className="button button--primary" onClick={() => setOverlay({ kind: 'form', branch: null })}>
          <AdminIcon name="plus" />
          <span>{t('admin.branches.new')}</span>
        </button>
      </PageHeading>

      {writeError !== null && <WriteError error={writeError} attempt={attempt} listUpdated={listUpdated} />}
      {query.error !== null && <QueryError error={query.error} onRetry={reload} />}

      {rows && <p className="adm-hint">{t(count === 1 ? 'admin.branches.count.one' : 'admin.branches.count.other', { n: count })}</p>}

      {query.loading && !rows && (
        <p role="status" className="adm-hint">
          <Icon name="spinner" />
          <span>{t('admin.branches.loading')}</span>
        </p>
      )}
      {rows && items.length === 0 && (
        <div className="notice notice--info">
          <Icon name="info" />
          <span>{t('admin.branches.empty')}</span>
        </div>
      )}

      {items.length > 0 && wide && (
        <div className="adm-table-wrap">
          <table className="adm-table">
            <caption className="visually-hidden">{t('admin.branches.caption')}</caption>
            <thead>
              <tr>
                <th scope="col">{t('admin.branches.col.name')}</th>
                <th scope="col">{t('admin.branches.col.radius')}</th>
                <th scope="col">{t('admin.branches.col.employees')}</th>
                <th scope="col">{t('admin.branches.col.status')}</th>
                <th scope="col">{t('admin.branches.col.actions')}</th>
              </tr>
            </thead>
            <tbody>
              {items.map((branch) => {
                const noteId = `${noteBase}-${branch.id}`
                return (
                  <tr key={branch.id}>
                    <th scope="row">
                      {branch.name}
                      {branch.active && branch.activeEmployees > 0 && (
                        <p className="adm-hint" id={noteId}>
                          {t(branch.activeEmployees === 1 ? 'admin.branches.inUse.one' : 'admin.branches.inUse.other', { n: branch.activeEmployees })}
                        </p>
                      )}
                    </th>
                    <td>{t('admin.review.distance', { meters: branch.radiusMeters })}</td>
                    <td>
                      <ActiveCount branch={branch} />
                    </td>
                    <td>
                      <StatusChip active={branch.active} />
                    </td>
                    <td>
                      <Actions
                        branch={branch}
                        busy={busyId === branch.id}
                        noteId={noteId}
                        onEdit={(b) => setOverlay({ kind: 'form', branch: b })}
                        onToggle={(b) => (b.active ? setOverlay({ kind: 'deactivate', branch: b }) : void toggle(b))}
                      />
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
      )}

      {items.length > 0 && !wide && (
        <ul className="adm-card-list">
          {items.map((branch) => {
            const noteId = `${noteBase}-${branch.id}`
            return (
              <li key={branch.id}>
                <article className="adm-card-item" aria-labelledby={`${noteBase}-h-${branch.id}`}>
                  <h3 id={`${noteBase}-h-${branch.id}`}>{branch.name}</h3>
                  <StatusChip active={branch.active} />
                  <dl className="adm-facts">
                    <div>
                      <dt>{t('admin.branches.col.radius')}</dt>
                      <dd>{t('admin.review.distance', { meters: branch.radiusMeters })}</dd>
                    </div>
                    <div>
                      <dt>{t('admin.branches.col.employees')}</dt>
                      <dd>
                        <ActiveCount branch={branch} />
                      </dd>
                    </div>
                  </dl>
                  <Actions
                    branch={branch}
                    busy={busyId === branch.id}
                    noteId={noteId}
                    onEdit={(b) => setOverlay({ kind: 'form', branch: b })}
                    onToggle={(b) => (b.active ? setOverlay({ kind: 'deactivate', branch: b }) : void toggle(b))}
                  />
                  {branch.active && branch.activeEmployees > 0 && (
                    <p className="adm-hint" id={noteId}>
                      {t(branch.activeEmployees === 1 ? 'admin.branches.inUse.one' : 'admin.branches.inUse.other', { n: branch.activeEmployees })}
                    </p>
                  )}
                </article>
              </li>
            )
          })}
        </ul>
      )}

      {overlay?.kind === 'form' && (
        <BranchFormDialog
          branch={overlay.branch}
          onCancel={() => setOverlay(null)}
          onSaved={(saved) => {
            setOverlay(null)
            focusAfter.current = null
            replaceRow(saved)
            announce(t('admin.branches.saved'))
          }}
        />
      )}
      <ConfirmDialog
        open={overlay?.kind === 'deactivate'}
        title={t('admin.branches.confirmDeactivateTitle', { branch: overlay?.kind === 'deactivate' ? overlay.branch.name : '' })}
        body={<p>{t('admin.branches.confirmDeactivateBody')}</p>}
        confirmLabel={t('admin.branches.deactivate')}
        cancelLabel={t('common.cancel')}
        tone="destructive"
        onConfirm={() => overlay?.kind === 'deactivate' && void toggle(overlay.branch)}
        onCancel={() => setOverlay(null)}
      />
    </>
  )
}
