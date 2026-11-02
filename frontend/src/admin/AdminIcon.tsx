import { Icon, type IconName } from '../components/Icon'

// Admin-only icons live here (not in components/Icon.tsx) so the employee bundle does not grow.
const ADMIN_PATHS = {
  clock: (
    <>
      <circle cx="12" cy="12" r="9" />
      <path d="M12 7v5l3 2" />
    </>
  ),
  'clock-alert': (
    <>
      <path d="M20 12a8 8 0 11-4.2-7" />
      <path d="M12 8v4l2.5 1.5M19 3v4M19 9.5v.5" />
    </>
  ),
  eye: (
    <>
      <path d="M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12z" />
      <circle cx="12" cy="12" r="3" />
    </>
  ),
  'minus-circle': (
    <>
      <circle cx="12" cy="12" r="9" />
      <path d="M8 12h8" />
    </>
  ),
  'help-circle': (
    <>
      <circle cx="12" cy="12" r="9" />
      <path d="M9.5 9.5a2.5 2.5 0 114 2c-.9.6-1.5 1.1-1.5 2.5M12 17v.5" />
    </>
  ),
  'calendar-off': (
    <>
      <path d="M8 3v3M16 3v3M4 8h16v12H4zM9 13h6" />
    </>
  ),
  pencil: <path d="M4 20l1-4L16 5l3 3L8 19zM14 7l3 3" />,
  'external-link': <path d="M14 4h6v6M20 4l-9 9M18 14v5a1 1 0 01-1 1H5a1 1 0 01-1-1V7a1 1 0 011-1h5" />,
  'chevron-left': <path d="M15 5l-7 7 7 7" />,
  'chevron-right': <path d="M9 5l7 7-7 7" />,
  'clipboard-check': (
    <>
      <path d="M9 4h6v3H9zM7 5.5H5v15h14v-15h-2" />
      <path d="M9 14l2.5 2.5L16 12" />
    </>
  ),
  'layout-dashboard': <path d="M4 4h7v9H4zM13 4h7v5h-7zM13 11h7v9h-7zM4 15h7v5H4z" />,
} as const

export type AdminIconName = keyof typeof ADMIN_PATHS | IconName

export function AdminIcon({ name }: { name: AdminIconName }) {
  if (!(name in ADMIN_PATHS)) return <Icon name={name as IconName} />
  return (
    <svg
      className="icon"
      width="20"
      height="20"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      focusable="false"
    >
      {ADMIN_PATHS[name as keyof typeof ADMIN_PATHS]}
    </svg>
  )
}
