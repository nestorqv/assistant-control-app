import { parseInstant } from '../api/parse'
import type { Language } from '../i18n/language'
import { formatDuration, formatLongDate, formatSentenceDate, formatTime, localDateToMs, localeFor } from '../time/format'
import type { AdminTranslate } from './adminI18n'

/** Epoch ms of an ISO instant sent by the backend. */
export function ms(iso: string): number {
  return parseInstant(iso).getTime()
}

/** HH:mm in the company zone, never the zone of the administrator's PC. */
export function timeOf(iso: string, timeZone: string, lang: Language): string {
  return formatTime(ms(iso), timeZone, lang)
}

/** "28 oct, 10:14": short date and time in the company zone. */
export function shortDateTime(iso: string, timeZone: string, lang: Language): string {
  const locale = localeFor(lang)
  return new Intl.DateTimeFormat(locale, {
    day: 'numeric',
    month: 'short',
    hour: '2-digit',
    minute: '2-digit',
    hourCycle: 'h23',
    timeZone,
  })
    .format(ms(iso))
    .replace(/\./g, '')
}

/** A LocalDate (workDate, date) as a sentence date, e.g. "Wednesday 28 October". */
export function localDateSentence(localDate: string, lang: Language): string {
  return formatSentenceDate(localDateToMs(localDate), 'UTC', lang)
}

export function localDateHeading(localDate: string, lang: Language): string {
  return formatLongDate(localDateToMs(localDate), 'UTC', lang)
}

/** "9 h 05 min" from minutes. */
export function hoursMinutes(minutes: number, t: AdminTranslate): string {
  return formatDuration(minutes, t as Parameters<typeof formatDuration>[1])
}

export function initials(name: string): string {
  const parts = name.trim().split(/\s+/).filter(Boolean)
  const letters = [parts[0], parts.length > 1 ? parts[parts.length - 1] : undefined]
    .filter((p): p is string => p !== undefined)
    .map((p) => Array.from(p)[0]?.toLocaleUpperCase() ?? '')
  return letters.join('')
}

/** YYYY-MM-DDTHH:mm of an instant in the company zone: the value of a datetime-local input. */
export function toLocalInput(iso: string, timeZone: string): string {
  const parts = new Intl.DateTimeFormat('en-CA', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hourCycle: 'h23',
    timeZone,
  }).formatToParts(ms(iso))
  const pick = (type: string) => parts.find((p) => p.type === type)?.value ?? ''
  return `${pick('year')}-${pick('month')}-${pick('day')}T${pick('hour')}:${pick('minute')}`
}

/** "Central Standard Time (America/Costa_Rica)": the zone the typed time is read in. */
export function zoneLabel(timeZone: string, lang: Language): string {
  const name = new Intl.DateTimeFormat(localeFor(lang), { timeZone, timeZoneName: 'long' })
    .formatToParts(Date.now())
    .find((p) => p.type === 'timeZoneName')?.value
  return name ? `${name} (${timeZone})` : timeZone
}

/** Plain external link, opened only on click. Built from numbers only (no user text goes into the URL). */
export function googleMapsUrl(latitude: number, longitude: number): string {
  return `https://www.google.com/maps/search/?api=1&query=${latitude.toFixed(6)},${longitude.toFixed(6)}`
}

export function formatCoordinates(latitude: number, longitude: number): string {
  return `${latitude.toFixed(6)}, ${longitude.toFixed(6)}`
}

/** Seconds between two instants, as whole minutes (for "N min difference"). */
export function minutesApart(a: string, b: string): number {
  return Math.round(Math.abs(ms(a) - ms(b)) / 60_000)
}

/** Adds days to a YYYY-MM-DD string without any zone involved. */
export function addDays(localDate: string, days: number): string {
  const date = new Date(localDateToMs(localDate) + days * 86_400_000)
  return date.toISOString().slice(0, 10)
}
