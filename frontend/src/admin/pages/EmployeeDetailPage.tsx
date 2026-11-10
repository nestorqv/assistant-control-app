import { useEffect, useId, useMemo, useRef, useState, type FormEvent } from 'react'
import { Link, useNavigate, useParams } from 'react-router'
import { signOut } from '../../auth/session'
import { useSession } from '../../auth/useSession'
import { useAnnouncer } from '../../components/announcerContext'
import { ConfirmDialog } from '../../components/ConfirmDialog'
import { Icon } from '../../components/Icon'
import { AdminIcon } from '../AdminIcon'
import { useAdminI18n } from '../adminI18n'
import { fetchBranches, fetchEmployee, fetchEmployees, setEmployeeActive, unlockEmployee, updateEmployee } from '../api/adminApi'
import type { AdminEmployeeDetail, AdminEmployeeView, PinResetResult } from '../api/adminTypes'
import { AccessPanel, type AccessAction } from '../components/AccessPanel'
import { Chip } from '../components/Chips'
import { draftToInput, EmployeeFields, validateDraft, type DraftErrors, type EmployeeDraft } from '../components/EmployeeFields'
import { GuardedLink } from '../components/GuardedLink'
import { PageHeading, QueryError } from '../components/PageParts'
import { PinResetDialog } from '../components/PinResetDialog'
import { PinRevealDialog } from '../components/PinRevealDialog'
import { ScheduleEditor } from '../components/ScheduleEditor'
import { WriteError } from '../components/WriteError'
import { problemStatus } from '../errors'
import { useAdminQuery } from '../hooks/useAdminQuery'
import { useAdminShell } from '../shell'

type Overlay = 'role' | 'deactivate' | 'reset' | null
// An interface (not an arrow type) keeps this file free of text the JSX scan would misread.
interface AccessCall {
  (): Promise<AdminEmployeeView>
}
type Reveal = { pin: string; name: string; code: string; self: boolean }

function draftOf(e: AdminEmployeeView): EmployeeDraft {
  return { code: e.code, name: e.name, jobTitle: e.jobTitle ?? '', role: e.role, branchId: e.branchId === null ? '' : String(e.branchId) }
}

/** Profile, access and weekly schedule of one person. Names appear here only (the h1 inside the admin area), never in the title or URL. */
export function EmployeeDetailPage() {
  const { t } = useAdminI18n()
  const { announce } = useAnnouncer()
  const shell = useAdminShell()
  const session = useSession()
  const navigate = useNavigate()
  const params = useParams()
  const id = Number(params.id)
  const timeZone = session.identity?.timeZone ?? 'America/Costa_Rica'
  const query = useAdminQuery(() => fetchEmployee(id), `employee-${id}`)
  const branchesQuery = useAdminQuery(() => fetchBranches(), 'branches-for-employee')
  const listQuery = useAdminQuery(() => fetchEmployees(), 'employees-for-detail')
  const [view, setView] = useState<AdminEmployeeDetail | null>(null)
  const [draft, setDraft] = useState<EmployeeDraft | null>(null)
  const [errors, setErrors] = useState<DraftErrors>({})
  const [writeError, setWriteError] = useState<unknown>(null)
  const [accessError, setAccessError] = useState<unknown>(null)
  const [attempt, setAttempt] = useState(0)
  const [saving, setSaving] = useState(false)
  const [busy, setBusy] = useState<AccessAction>(null)
  const [overlay, setOverlay] = useState<Overlay>(null)
  const [reveal, setReveal] = useState<Reveal | null>(null)
  const resetRef = useRef<HTMLButtonElement>(null)
  const focusResetNext = useRef(false)
  const refs = {
    code: useRef<HTMLInputElement | HTMLSelectElement>(null),
    name: useRef<HTMLInputElement | HTMLSelectElement>(null),
    jobTitle: useRef<HTMLInputElement | HTMLSelectElement>(null),
    branch: useRef<HTMLInputElement | HTMLSelectElement>(null),
  }
  const selfNoteId = useId()
  const lastNoteId = useId()
  const profileId = useId()

  const { data } = query
  useEffect(() => {
    if (data) {
      setView(data)
      setDraft(draftOf(data))
    }
  }, [data])

  // Runs after the render that removed the unlock button, so the target exists.
  useEffect(() => {
    if (focusResetNext.current && view && !view.locked) {
      focusResetNext.current = false
      resetRef.current?.focus()
    }
  }, [view, busy])

  const { setDialogOpen } = shell
  useEffect(() => {
    setDialogOpen(overlay !== null || reveal !== null)
    return () => setDialogOpen(false)
  }, [overlay, reveal, setDialogOpen])

  const self = view !== null && session.identity?.employeeId === view.id
  const activeAdmins = (listQuery.data ?? []).filter((e) => e.role === 'ADMIN' && e.active)
  const lastAdmin = view !== null && view.role === 'ADMIN' && view.active && activeAdmins.length === 1 && activeAdmins[0].id === view.id
  const roleLocked = self || lastAdmin
  const others = useMemo(() => (listQuery.data ?? []).filter((e) => e.id !== id), [listQuery.data, id])
  const branches = branchesQuery.data ?? []
  const retiredBranch = view && view.branchId !== null && view.branchActive === false ? { id: view.branchId, name: view.branchName ?? '' } : null
  const dirty =
    view !== null &&
    draft !== null &&
    JSON.stringify(draftToInput(draft)) !== JSON.stringify(draftToInput(draftOf(view)))

  function applyView(next: AdminEmployeeView) {
    setView((current) => ({ ...next, lastPinReset: current?.lastPinReset ?? null }))
    setDraft(draftOf(next))
  }

  // Replaces the whole view, e.g. after a stale record or a race the server refused.
  function reloadAll() {
    setWriteError(null)
    setAccessError(null)
    query.reload()
    listQuery.reload()
    announce(t('admin.recordChanged.reloaded'))
  }

  async function save(event?: FormEvent) {
    event?.preventDefault()
    if (!view || !draft || saving || !dirty) return
    const found = validateDraft(draft, t, false)
    setErrors(found)
    const first = (['name', 'jobTitle', 'branch'] as const).find((key) => found[key])
    if (first) {
      refs[first].current?.focus()
      return
    }
    if (draft.role !== view.role && overlay !== 'role') {
      setOverlay('role')
      return
    }
    setOverlay(null)
    setSaving(true)
    setWriteError(null)
    try {
      applyView(await updateEmployee(view.id, { ...draftToInput(draft), expectedVersion: view.version }))
      announce(t('admin.employee.saved'))
    } catch (error) {
      setAttempt((n) => n + 1)
      setWriteError(error)
    } finally {
      setSaving(false)
    }
  }

  async function runAccess(kind: 'unlock' | 'toggle', action: AccessCall, done: string) {
    setBusy(kind)
    setAccessError(null)
    setOverlay(null)
    try {
      applyView(await action())
      announce(done)
      // The unlock button disappears with the lock; keep the keyboard user on the next useful action.
      if (kind === 'unlock') focusResetNext.current = true
    } catch (error) {
      setAttempt((n) => n + 1)
      setAccessError(error)
      // A race the server refused (self, last admin, stale version): show the box and refresh what is shown.
      listQuery.reload()
      query.reload()
    } finally {
      setBusy(null)
    }
  }

  function toggleActive() {
    if (!view) return
    if (view.active) setOverlay('deactivate')
    else void runAccess('toggle', () => setEmployeeActive(view.id, true, view.version), t('admin.employee.activated'))
  }

  function revealDone(result: PinResetResult) {
    // The employee is merged into the page; the PIN goes to the dialog only and is never kept in a cache.
    setOverlay(null)
    setView(result.employee)
    setDraft(draftOf(result.employee))
    setReveal({ pin: result.pin, name: result.employee.name, code: result.employee.code, self })
  }

  async function closeReveal() {
    const wasSelf = reveal?.self === true
    setReveal(null)
    if (wasSelf) {
      // The token is already invalid on the server, so the logout API is not called: only the local wipe.
      await signOut()
      void navigate('/login', { replace: true })
      return
    }
    announce(t('admin.pin.statusReset'))
    resetRef.current?.focus()
  }

  if (query.error !== null && !view) {
    if (problemStatus(query.error) === 404) {
      return (
        <>
          <PageHeading title={t('admin.employee.notFoundTitle')} documentTitle={t('admin.employee.pageTitle')} />
          <div className="notice notice--info">
            <Icon name="info" />
            <span>{t('admin.employee.notFound')}</span>
          </div>
          <Link className="adm-link-button" to="/admin/employees">
            <AdminIcon name="chevron-left" />
            <span>{t('admin.employee.back')}</span>
          </Link>
        </>
      )
    }
    return (
      <>
        <PageHeading title={t('admin.employee.pageTitle')} />
        <QueryError error={query.error} onRetry={query.reload} />
      </>
    )
  }

  if (!view || !draft) {
    return (
      <>
        <PageHeading title={t('admin.employee.pageTitle')} />
        <p role="status" className="adm-hint">
          <Icon name="spinner" />
          <span>{t('admin.employee.loading')}</span>
        </p>
      </>
    )
  }

  const subtitle = [view.code, view.jobTitle, view.branchName].filter(Boolean).join(' · ')
  return (
    <>
      <GuardedLink className="adm-link-button" to="/admin/employees">
        <AdminIcon name="chevron-left" />
        <span>{t('admin.employee.back')}</span>
      </GuardedLink>
      <PageHeading title={view.name} documentTitle={t('admin.employee.pageTitle')}>
        <div className="adm-heading__chips">
          {view.active ? (
            <Chip family="teal" icon="check-circle">
              {t('admin.employee.stateActive')}
            </Chip>
          ) : (
            <Chip family="grey" icon="circle">
              {t('admin.employee.stateInactive')}
            </Chip>
          )}
          <Chip family="grey" icon="users">
            {t(view.role === 'ADMIN' ? 'admin.employee.roleAdmin' : 'admin.employee.roleEmployee')}
          </Chip>
        </div>
        <p className="adm-sub">{subtitle}</p>
      </PageHeading>

      <div className="adm-two-columns">
        <section className="adm-section" aria-labelledby={profileId}>
          <h2 id={profileId} className="adm-h2">
            {t('admin.employee.profile')}
          </h2>
          <form onSubmit={(event) => void save(event)} noValidate aria-busy={saving || undefined} className="adm-form-grid">
            <EmployeeFields
              draft={draft}
              onChange={setDraft}
              errors={errors}
              readOnly={saving}
              branches={branches}
              codeEditable={false}
              retiredBranch={retiredBranch}
              roleLockedNoteId={roleLocked ? (self ? selfNoteId : lastNoteId) : undefined}
              refs={refs}
            />
            {writeError !== null && <WriteError error={writeError} attempt={attempt} onReload={reloadAll} />}
            <div className="modal__actions">
              <button type="submit" className="button button--primary" aria-disabled={!dirty || saving || undefined} aria-busy={saving || undefined}>
                {saving && <Icon name="spinner" />}
                <span>{saving ? t('admin.action.saving') : t('admin.employee.save')}</span>
              </button>
            </div>
          </form>
        </section>
        <div>
          {accessError !== null && <WriteError error={accessError} attempt={attempt} onReload={reloadAll} />}
          <AccessPanel
            employee={view}
            timeZone={timeZone}
            self={self}
            lastAdmin={lastAdmin}
            busy={busy}
            selfNoteId={selfNoteId}
            lastNoteId={lastNoteId}
            resetRef={resetRef}
            onUnlock={() => void runAccess('unlock', () => unlockEmployee(view.id), t('admin.employee.unlocked'))}
            onResetPin={() => setOverlay('reset')}
            onToggle={toggleActive}
          />
        </div>
      </div>

      <ScheduleEditor employeeId={view.id} others={others} />

      <ConfirmDialog
        open={overlay === 'role'}
        title={t(draft.role === 'ADMIN' ? 'admin.confirm.makeAdminTitle' : 'admin.confirm.removeAdminTitle', { name: view.name })}
        body={<p>{t(draft.role === 'ADMIN' ? 'admin.confirm.makeAdminBody' : 'admin.confirm.removeAdminBody')}</p>}
        confirmLabel={t(draft.role === 'ADMIN' ? 'admin.confirm.makeAdmin' : 'admin.confirm.removeAdmin')}
        cancelLabel={t('common.cancel')}
        tone={draft.role === 'ADMIN' ? 'default' : 'destructive'}
        busy={saving}
        busyLabel={t('admin.action.saving')}
        onConfirm={() => void save()}
        onCancel={() => setOverlay(null)}
      />
      <ConfirmDialog
        open={overlay === 'deactivate'}
        title={t('admin.confirm.deactivateTitle', { name: view.name })}
        body={<p>{t('admin.confirm.deactivateBody', { name: view.name })}</p>}
        confirmLabel={t('admin.confirm.deactivate')}
        cancelLabel={t('common.cancel')}
        tone="destructive"
        busy={busy === 'toggle'}
        busyLabel={t('admin.confirm.deactivating')}
        onConfirm={() => void runAccess('toggle', () => setEmployeeActive(view.id, false, view.version), t('admin.employee.deactivated'))}
        onCancel={() => setOverlay(null)}
      />
      {overlay === 'reset' && (
        <PinResetDialog target={{ id: view.id, name: view.name }} self={self} onCancel={() => setOverlay(null)} onDone={revealDone} />
      )}
      {reveal && (
        <PinRevealDialog
          mode="reset"
          name={reveal.name}
          code={reveal.code}
          pin={reveal.pin}
          self={reveal.self}
          onClose={() => void closeReveal()}
        />
      )}
    </>
  )
}
