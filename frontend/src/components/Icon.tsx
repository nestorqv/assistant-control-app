// Inline SVG, no icon font and no extra requests. 20 px, currentColor, decorative (the text carries the meaning).
const PATHS = {
  circle: <circle cx="12" cy="12" r="9" />,
  'dot-circle': (
    <>
      <circle cx="12" cy="12" r="9" />
      <circle cx="12" cy="12" r="3" fill="currentColor" />
    </>
  ),
  'check-circle': (
    <>
      <circle cx="12" cy="12" r="9" />
      <path d="M8 12.5l3 3 5-6" />
    </>
  ),
  check: <path d="M5 12.5l4.5 4.5L19 7" />,
  'alert-triangle': (
    <>
      <path d="M12 3l10 18H2z" />
      <path d="M12 10v5M12 18v.5" />
    </>
  ),
  'x-circle': (
    <>
      <circle cx="12" cy="12" r="9" />
      <path d="M9 9l6 6M15 9l-6 6" />
    </>
  ),
  info: (
    <>
      <circle cx="12" cy="12" r="9" />
      <path d="M12 11v6M12 7.5v.5" />
    </>
  ),
  hourglass: <path d="M6 3h12M6 21h12M7 3c0 5 5 6 5 9s-5 4-5 9M17 3c0 5-5 6-5 9s5 4 5 9" />,
  'map-pin': (
    <>
      <path d="M12 21s7-6.5 7-12a7 7 0 10-14 0c0 5.5 7 12 7 12z" />
      <circle cx="12" cy="9" r="2.5" />
    </>
  ),
  'map-pin-off': (
    <>
      <path d="M12 21s7-6.5 7-12a7 7 0 00-11-5.7M5 9c0 3 2.2 6 4.5 8.4" />
      <path d="M3 3l18 18" />
    </>
  ),
  camera: (
    <>
      <path d="M4 8h3l2-3h6l2 3h3v11H4z" />
      <circle cx="12" cy="13" r="3.5" />
    </>
  ),
  'camera-off': (
    <>
      <path d="M9 5h6l2 3h3v10M4 8h1m-1 0v11h13" />
      <path d="M3 3l18 18" />
    </>
  ),
  'wifi-off': (
    <>
      <path d="M2 9a15 15 0 0120 0M5 12.5a10 10 0 0114 0M8.5 16a5 5 0 017 0" />
      <path d="M12 19.5v.5M3 3l18 18" />
    </>
  ),
  refresh: <path d="M20 5v5h-5M4 19v-5h5M5.5 9A8 8 0 0119 8.5M18.5 15A8 8 0 015 15.5" />,
  'log-in': <path d="M10 4h8a2 2 0 012 2v12a2 2 0 01-2 2h-8M4 12h11m-4-4l4 4-4 4" />,
  'log-out': <path d="M14 4H6a2 2 0 00-2 2v12a2 2 0 002 2h8M10 12h10m-4-4l4 4-4 4" />,
  trash: <path d="M4 7h16M9 7V4h6v3M6 7l1 13h10l1-13M10 11v6M14 11v6" />,
  backspace: <path d="M9 5h11v14H9L3 12zM12.5 9.5l5 5M17.5 9.5l-5 5" />,
  spinner: <path d="M12 3a9 9 0 019 9" />,
} as const

export type IconName = keyof typeof PATHS

export function Icon({ name, className }: { name: IconName; className?: string }) {
  return (
    <svg
      className={`icon${name === 'spinner' ? ' icon--spin' : ''}${className ? ` ${className}` : ''}`}
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
      {PATHS[name]}
    </svg>
  )
}
