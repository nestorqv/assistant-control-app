import type { MouseEvent, ReactNode } from 'react'
import { Link, NavLink, useNavigate } from 'react-router'
import { useAdminShell } from '../shell'

type Props = {
  to: string
  children: ReactNode
  /** NavLink with the active-page styling and aria-current */
  nav?: boolean
  end?: boolean
  className?: string
}

/**
 * A router link that first asks "leave without saving?" while the shell has unsaved edits. Without unsaved
 * edits it behaves exactly like Link / NavLink. The browser Back button is not intercepted (declarative router).
 */
export function GuardedLink({ to, children, nav = false, end = false, className }: Props) {
  const shell = useAdminShell()
  const navigate = useNavigate()
  const onClick = (event: MouseEvent<HTMLAnchorElement>) => {
    const modified = event.metaKey || event.ctrlKey || event.shiftKey || event.altKey || event.button !== 0
    if (!shell.unsaved || modified || event.defaultPrevented) return
    event.preventDefault()
    void shell.confirmLeave().then((leave) => {
      if (leave) void navigate(to)
    })
  }
  return nav ? (
    <NavLink to={to} end={end} className={className} onClick={onClick}>
      {children}
    </NavLink>
  ) : (
    <Link to={to} className={className} onClick={onClick}>
      {children}
    </Link>
  )
}
