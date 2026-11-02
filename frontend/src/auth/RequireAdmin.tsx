import type { ReactNode } from 'react'
import { Navigate, useLocation } from 'react-router'
import { useSession } from './useSession'

/**
 * Cosmetic guard for /admin/*: it only avoids rendering a console that would get 403s. The server is the real
 * control (URL rule, @PreAuthorize and the role check in the database on every request). It lives in the
 * employee bundle so a non-admin never downloads the admin chunk.
 * No identity or no token (cold open) -> /login?next=...; an EMPLOYEE -> /. An expired token keeps the page
 * (the PIN dialog opens over it).
 */
export function RequireAdmin({ children }: { children: ReactNode }) {
  const session = useSession()
  const { pathname, search } = useLocation()
  if (!session.ready) return null
  if (!session.identity || (!session.token && !session.needsReauth)) {
    return <Navigate to={`/login?next=${encodeURIComponent(pathname + search)}`} replace />
  }
  if (session.identity.role !== 'ADMIN') return <Navigate to="/" replace />
  return <>{children}</>
}
