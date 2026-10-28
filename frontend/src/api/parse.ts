/** Java may emit micro- or nanoseconds; older Safari is strict, so keep 3 fraction digits. */
export function parseInstant(value: string): Date {
  return new Date(value.replace(/(\.\d{3})\d+/, '$1'))
}

/** "08:00:00" or "08:00" -> "08:00". */
export function parseLocalTime(value: string): string {
  const match = /^(\d{2}):(\d{2})/.exec(value)
  if (!match) throw new Error('Invalid local time')
  return `${match[1]}:${match[2]}`
}
