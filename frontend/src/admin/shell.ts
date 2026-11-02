import { createContext, useContext } from 'react'

export type AdminShell = {
  /** Every PENDING_REVIEW entry of the company (the nav badge) */
  pending: number
  /** Company-local date from the server; the browser zone is never used for dates */
  today: string | null
  refreshPending: () => void
  /** a dialog is open: the automatic refresh waits */
  setDialogOpen: (open: boolean) => void
  dialogOpen: boolean
}

export const AdminShellContext = createContext<AdminShell>({
  pending: 0,
  today: null,
  refreshPending: () => undefined,
  setDialogOpen: () => undefined,
  dialogOpen: false,
})

export function useAdminShell(): AdminShell {
  return useContext(AdminShellContext)
}
