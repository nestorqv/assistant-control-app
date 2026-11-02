import { useEffect, useRef, type ReactNode } from 'react'
import { useNavigate } from 'react-router'
import { performLogout } from '../../auth/logout'
import { ErrorBox } from '../../components/ErrorBox'
import { useAdminI18n } from '../adminI18n'
import { adminErrorText, problemCode, problemStatus } from '../errors'

/** One h1 per page: focused on navigation and mirrored into the document title (WCAG 2.4.2). */
export function PageHeading({ title, children }: { title: string; children?: ReactNode }) {
  const { t } = useAdminI18n()
  const ref = useRef<HTMLHeadingElement>(null)
  useEffect(() => {
    ref.current?.focus()
  }, [])
  useEffect(() => {
    document.title = `${title} · ${t('admin.shell.title')}`
  }, [title, t])
  return (
    <header className="adm-heading">
      <h1 ref={ref} tabIndex={-1} className="adm-h1">
        {title}
      </h1>
      {children}
    </header>
  )
}

/**
 * Error of a load. A 403 means the session ended on the server (demoted or deactivated): the PIN would not
 * help, so the way out is the login screen. A 400 REPORT_TOO_LARGE asks for a branch.
 */
export function QueryError({ error, onRetry }: { error: unknown; onRetry: () => void }) {
  const { t } = useAdminI18n()
  const navigate = useNavigate()
  if (problemStatus(error) === 403) {
    return (
      <div className="adm-error">
        <ErrorBox>{t('admin.sessionEnded')}</ErrorBox>
        <button
          type="button"
          className="button button--primary"
          onClick={() => {
            void performLogout().then(() => navigate('/login', { replace: true }))
          }}
        >
          {t('admin.sessionEnded.action')}
        </button>
      </div>
    )
  }
  const message = problemCode(error) === 'REPORT_TOO_LARGE' ? t('admin.dashboard.tooLarge') : adminErrorText(error, t)
  return (
    <div className="adm-error">
      <ErrorBox>{message}</ErrorBox>
      <button type="button" className="button button--secondary" onClick={onRetry}>
        {t('common.retry')}
      </button>
    </div>
  )
}

export function Pagination({ page, pages, onPage }: { page: number; pages: number; onPage: (page: number) => void }) {
  const { t } = useAdminI18n()
  if (pages <= 1) return null
  const first = page <= 0
  const last = page >= pages - 1
  return (
    <nav className="adm-pager" aria-label={t('admin.review.list')}>
      <button type="button" className="button button--secondary" aria-disabled={first} onClick={() => !first && onPage(page - 1)}>
        {t('admin.review.prev')}
      </button>
      <span>{t('admin.review.page', { page: page + 1, pages })}</span>
      <button type="button" className="button button--secondary" aria-disabled={last} onClick={() => !last && onPage(page + 1)}>
        {t('admin.review.next')}
      </button>
    </nav>
  )
}
