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
  /** A page has edits that were not saved (the weekly schedule). Registers a beforeunload prompt too. */
  setUnsavedChanges: (key: string, dirty: boolean) => void
  unsaved: boolean
  /** Resolves true at once when nothing is unsaved; otherwise asks "leave without saving?" and resolves the answer. */
  confirmLeave: () => Promise<boolean>
  /**
   * Self PIN reset: the caller's own token is invalid after the commit, so the poll and the re-auth dialog must
   * not run over the PIN dialog. While suspended, neither the poll nor the re-auth dialog / banner is active.
   */
  suspendSession: (suspended: boolean) => void
}

export const AdminShellContext = createContext<AdminShell>({
  pending: 0,
  today: null,
  refreshPending: () => undefined,
  setDialogOpen: () => undefined,
  dialogOpen: false,
  setUnsavedChanges: () => undefined,
  unsaved: false,
  confirmLeave: () => Promise.resolve(true),
  suspendSession: () => undefined,
})

export function useAdminShell(): AdminShell {
  return useContext(AdminShellContext)
}
