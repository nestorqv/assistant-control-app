// Optional vibration feedback (Android Chrome; not iOS). Never the only feedback: every event also has text
// and an icon. Skipped when the user asked for reduced motion. Keypad keys never vibrate (it would leak
// the typing rhythm).

function allowed(): boolean {
  if (typeof navigator === 'undefined' || typeof navigator.vibrate !== 'function') return false
  return !(typeof matchMedia === 'function' && matchMedia('(prefers-reduced-motion: reduce)').matches)
}

function vibrate(pattern: number | number[]) {
  if (allowed()) navigator.vibrate(pattern)
}

export const haptics = {
  /** shutter pressed */
  tap: () => vibrate(15),
  /** clock action recorded or queued */
  success: () => vibrate(25),
  /** failure card or failed sign-in */
  error: () => vibrate([40, 60, 40]),
}
