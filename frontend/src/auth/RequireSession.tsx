import type { ReactNode } from 'react'
import { Navigate } from 'react-router'
import { useSession } from './useSession'

/**
 * No identity -> /login. Consent pending (with a live token) -> /consent.
 * An identity without a token (cold open, possibly offline) stays: the main screen works from the snapshot.
 */
export function RequireSession({ children }: { children: ReactNode }) {
  const session = useSession()
  if (!session.ready) return null
  if (!session.identity) return <Navigate to="/login" replace />
  if (session.consentPending) return <Navigate to="/consent" replace />
  return <>{children}</>
}
