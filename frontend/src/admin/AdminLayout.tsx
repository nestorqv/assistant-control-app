import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { useSession } from '../auth/useSession'
import { ConfirmDialog } from '../components/ConfirmDialog'
import { HealthStatus } from '../components/HealthStatus'
import { Icon } from '../components/Icon'
import { LanguageSwitcher } from '../components/LanguageSwitcher'
import { LogoutConfirm } from '../components/LogoutConfirm'
import { ReauthController } from '../components/ReauthController'
import { AdminIcon } from './AdminIcon'
import { useAdminI18n } from './adminI18n'
import { fetchReviewCount } from './api/adminApi'
import { GuardedLink } from './components/GuardedLink'
import { AdminShellContext, type AdminShell } from './shell'

const POLL_MS = 60_000

function ReviewBadge({ count }: { count: number }) {
  const { t } = useAdminI18n()
  if (count <= 0) return null
  return (
    <>
      <span className="adm-badge" aria-hidden="true">
        {count > 99 ? '99+' : count}
      </span>
      <span className="visually-hidden">, {count === 1 ? t('admin.nav.reviewCount.one') : t('admin.nav.reviewCount.other', { n: count })}</span>
    </>
  )
}

/**
 * Skip link, header (company, my clock, language, sign out), main navigation with the review badge, the
 * content, and the footer. Also owns the PIN dialog that opens when the 6 h token expires.
 */
export function AdminLayout({ children }: { children: ReactNode }) {
  const { t } = useAdminI18n()
  const session = useSession()
  const [pending, setPending] = useState(0)
  const [today, setToday] = useState<string | null>(null)
  const [dialogOpen, setDialogOpen] = useState(false)
  const [signingOut, setSigningOut] = useState(false)
  const [reauthOpen, setReauthOpen] = useState(false)
  const [suspended, setSuspended] = useState(false)
  const [unsaved, setUnsaved] = useState(false)
  const [leaveOpen, setLeaveOpen] = useState(false)
  const main = useRef<HTMLElement>(null)
  const suspendedRef = useRef(false)
  const dirtyKeys = useRef(new Set<string>())
  const leaveAnswer = useRef<((leave: boolean) => void) | null>(null)

  const refreshPending = useCallback(() => {
    if (suspendedRef.current) return // a self PIN reset is on screen: the token is already invalid
    fetchReviewCount()
      .then((count) => {
        setPending(count.pending)
        setToday(count.today)
      })
      .catch(() => undefined) // the page that is open shows its own errors
  }, [])

  useEffect(() => {
    refreshPending()
    const onVisible = () => {
      if (document.visibilityState === 'visible') refreshPending()
    }
    document.addEventListener('visibilitychange', onVisible)
    const timer = setInterval(onVisible, POLL_MS)
    return () => {
      document.removeEventListener('visibilitychange', onVisible)
      clearInterval(timer)
    }
  }, [refreshPending])

  // The token lasts 6 h with no refresh: a 401 opens the PIN dialog over the page. Nothing is replayed.
  // While suspended (self PIN reset) the dialog and the banner stay away.
  const reauthShown = useRef(false)
  useEffect(() => {
    if (session.needsReauth) {
      if (!suspended && !reauthShown.current) {
        reauthShown.current = true
        setReauthOpen(true)
      }
    } else if (reauthShown.current) {
      reauthShown.current = false
      refreshPending()
    }
  }, [session.needsReauth, suspended, refreshPending])

  const suspendSession = useCallback((value: boolean) => {
    suspendedRef.current = value
    setSuspended(value)
  }, [])

  const setUnsavedChanges = useCallback((key: string, dirty: boolean) => {
    if (dirty) dirtyKeys.current.add(key)
    else dirtyKeys.current.delete(key)
    setUnsaved(dirtyKeys.current.size > 0)
  }, [])

  // The browser's own prompt for reload, tab close and typed addresses.
  useEffect(() => {
    if (!unsaved) return
    const onBeforeUnload = (event: BeforeUnloadEvent) => event.preventDefault()
    window.addEventListener('beforeunload', onBeforeUnload)
    return () => window.removeEventListener('beforeunload', onBeforeUnload)
  }, [unsaved])

  const confirmLeave = useCallback((): Promise<boolean> => {
    if (dirtyKeys.current.size === 0) return Promise.resolve(true)
    return new Promise<boolean>((resolve) => {
      leaveAnswer.current?.(false)
      leaveAnswer.current = resolve
      setLeaveOpen(true)
    })
  }, [])

  function answerLeave(leave: boolean) {
    setLeaveOpen(false)
    leaveAnswer.current?.(leave)
    leaveAnswer.current = null
  }

  const shell = useMemo<AdminShell>(
    () => ({
      pending,
      today,
      refreshPending,
      setDialogOpen,
      dialogOpen: dialogOpen || reauthOpen,
      setUnsavedChanges,
      unsaved,
      confirmLeave,
      suspendSession,
    }),
    [pending, today, refreshPending, dialogOpen, reauthOpen, setUnsavedChanges, unsaved, confirmLeave, suspendSession],
  )

  return (
    <AdminShellContext value={shell}>
      <div className="adm-shell">
        <a
          className="adm-skip"
          href="#main"
          onClick={(event) => {
            event.preventDefault()
            main.current?.focus()
          }}
        >
          {t('admin.nav.skipToContent')}
        </a>
        <header className="adm-header">
          <p className="adm-brand">
            <strong>{t('app.title')}</strong>
            <span>{session.identity?.companyName ?? ''}</span>
          </p>
          <div className="adm-header__actions">
            <GuardedLink className="header-button" to="/">
              <Icon name="log-in" />
              <span>{t('admin.nav.myClock')}</span>
            </GuardedLink>
            <LanguageSwitcher />
            <button
              type="button"
              className="header-button"
              onClick={() => {
                void confirmLeave().then((leave) => leave && setSigningOut(true))
              }}
            >
              <Icon name="log-out" />
              <span>{t('logout.button')}</span>
            </button>
          </div>
        </header>
        <nav className="adm-nav" aria-label={t('admin.nav.label')}>
          <ul>
            <li>
              <GuardedLink nav to="/admin" end>
                <AdminIcon name="layout-dashboard" />
                <span>{t('admin.nav.dashboard')}</span>
              </GuardedLink>
            </li>
            <li>
              <GuardedLink nav to="/admin/review">
                <AdminIcon name="clipboard-check" />
                <span>{t('admin.nav.review')}</span>
                <ReviewBadge count={pending} />
              </GuardedLink>
            </li>
            <li>
              <GuardedLink nav to="/admin/employees">
                <AdminIcon name="users" />
                <span>{t('admin.nav.employees')}</span>
              </GuardedLink>
            </li>
            <li>
              <GuardedLink nav to="/admin/branches">
                <AdminIcon name="branch" />
                <span>{t('admin.nav.branches')}</span>
              </GuardedLink>
            </li>
          </ul>
        </nav>
        <main id="main" ref={main} tabIndex={-1} className="adm-main">
          {session.needsReauth && !reauthOpen && !suspended && (
            <p className="notice notice--warn" role="alert">
              <Icon name="alert-triangle" />
              <span>{t('admin.reauth.banner')}</span>
              <button type="button" className="button button--secondary" onClick={() => setReauthOpen(true)}>
                {t('admin.reauth.bannerAction')}
              </button>
            </p>
          )}
          {children}
        </main>
        <footer className="adm-footer">
          <HealthStatus quiet />
        </footer>
      </div>
      <LogoutConfirm open={signingOut} onClose={() => setSigningOut(false)} />
      <ConfirmDialog
        open={leaveOpen}
        title={t('admin.confirm.leaveTitle')}
        body={<p>{t('admin.confirm.leaveBody')}</p>}
        confirmLabel={t('admin.confirm.leave')}
        cancelLabel={t('admin.confirm.stay')}
        onConfirm={() => answerLeave(true)}
        onCancel={() => answerLeave(false)}
      />
      {reauthOpen && session.needsReauth && !suspended && (
        <ReauthController
          onClose={() => setReauthOpen(false)}
          title={t('admin.reauth.title')}
          body={t('admin.reauth.body')}
          doneMessage={t('admin.reauth.done')}
        />
      )}
    </AdminShellContext>
  )
}
