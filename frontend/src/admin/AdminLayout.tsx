import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { Link, NavLink } from 'react-router'
import { useSession } from '../auth/useSession'
import { HealthStatus } from '../components/HealthStatus'
import { Icon } from '../components/Icon'
import { LanguageSwitcher } from '../components/LanguageSwitcher'
import { LogoutConfirm } from '../components/LogoutConfirm'
import { ReauthController } from '../components/ReauthController'
import { AdminIcon } from './AdminIcon'
import { useAdminI18n } from './adminI18n'
import { fetchReviewCount } from './api/adminApi'
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
  const main = useRef<HTMLElement>(null)

  const refreshPending = useCallback(() => {
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
  const wasNeedingReauth = useRef(false)
  useEffect(() => {
    if (session.needsReauth && !wasNeedingReauth.current) setReauthOpen(true)
    if (!session.needsReauth && wasNeedingReauth.current) refreshPending()
    wasNeedingReauth.current = session.needsReauth
  }, [session.needsReauth, refreshPending])

  const shell = useMemo<AdminShell>(
    () => ({ pending, today, refreshPending, setDialogOpen, dialogOpen: dialogOpen || reauthOpen }),
    [pending, today, refreshPending, dialogOpen, reauthOpen],
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
            <Link className="header-button" to="/">
              <Icon name="log-in" />
              <span>{t('admin.nav.myClock')}</span>
            </Link>
            <LanguageSwitcher />
            <button type="button" className="header-button" onClick={() => setSigningOut(true)}>
              <Icon name="log-out" />
              <span>{t('logout.button')}</span>
            </button>
          </div>
        </header>
        <nav className="adm-nav" aria-label={t('admin.nav.label')}>
          <ul>
            <li>
              <NavLink to="/admin" end>
                <AdminIcon name="layout-dashboard" />
                <span>{t('admin.nav.dashboard')}</span>
              </NavLink>
            </li>
            <li>
              <NavLink to="/admin/review">
                <AdminIcon name="clipboard-check" />
                <span>{t('admin.nav.review')}</span>
                <ReviewBadge count={pending} />
              </NavLink>
            </li>
          </ul>
        </nav>
        <main id="main" ref={main} tabIndex={-1} className="adm-main">
          {session.needsReauth && !reauthOpen && (
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
      {reauthOpen && session.needsReauth && (
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
