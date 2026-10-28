import { useEffect, type ReactNode } from 'react'
import { initSession } from './session'

/** Loads the stored identity and token once. State itself lives in the module store (see useSession). */
export function SessionProvider({ children }: { children: ReactNode }) {
  useEffect(() => {
    void initSession()
  }, [])
  return <>{children}</>
}
