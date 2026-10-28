import type { Translate } from '../i18n/i18nContext'
import type { Language } from '../i18n/language'

export function localeFor(lang: Language): string {
  return lang === 'en' ? 'en-US' : 'es-CR'
}

/** "Miercoles, 7 de octubre" in the company time zone, first letter capitalized. */
export function formatLongDate(ms: number, timeZone: string, lang: Language): string {
  const locale = localeFor(lang)
  const text = new Intl.DateTimeFormat(locale, { weekday: 'long', day: 'numeric', month: 'long', timeZone }).format(ms)
  return text.charAt(0).toLocaleUpperCase(locale) + text.slice(1)
}

// Spanish text has no comma after the weekday ("martes 6 de octubre"); Intl adds one.
function withoutWeekdayComma(text: string, lang: Language): string {
  return lang === 'es' ? text.replace(/^(\p{L}+),\s*/u, '$1 ') : text
}

/** Same as formatLongDate but lower case, for use inside a sentence. */
export function formatSentenceDate(ms: number, timeZone: string, lang: Language): string {
  const text = new Intl.DateTimeFormat(localeFor(lang), { weekday: 'long', day: 'numeric', month: 'long', timeZone }).format(ms)
  return withoutWeekdayComma(text, lang)
}

export function formatShortDate(ms: number, timeZone: string, lang: Language): string {
  const text = new Intl.DateTimeFormat(localeFor(lang), { weekday: 'short', day: 'numeric', month: 'short', timeZone })
    .format(ms)
    .replace(/\./g, '')
  return withoutWeekdayComma(text, lang)
}

/** HH:mm, 24 hour clock in both languages. */
export function formatTime(ms: number, timeZone: string, lang: Language): string {
  return new Intl.DateTimeFormat(localeFor(lang), {
    hour: '2-digit',
    minute: '2-digit',
    hourCycle: 'h23',
    timeZone,
  }).format(ms)
}

export function formatShortDateTime(ms: number, timeZone: string, lang: Language): string {
  return `${formatShortDate(ms, timeZone, lang)}, ${formatTime(ms, timeZone, lang)}`
}

/** A LocalDate ("YYYY-MM-DD") has no zone; format it at noon UTC so the day never shifts. */
export function localDateToMs(localDate: string): number {
  return Date.parse(`${localDate}T12:00:00Z`)
}

export function formatDuration(minutes: number, t: Translate): string {
  const total = Math.max(0, Math.round(minutes))
  const h = Math.floor(total / 60)
  const m = total % 60
  if (h === 0) return t('duration.m', { m })
  return t('duration.hm', { h, mm: String(m).padStart(2, '0') })
}

export function firstName(name: string): string {
  return name.trim().split(/\s+/)[0] ?? ''
}

/** Screen readers read the middle dot aloud; a comma gives the right pause. */
export function toSpoken(text: string): string {
  return text.replaceAll(' · ', ', ')
}

/** "YYYY-MM-DD" of an instant in the given zone (en-CA prints ISO order). */
export function localDateString(ms: number, timeZone: string): string {
  return new Intl.DateTimeFormat('en-CA', { year: 'numeric', month: '2-digit', day: '2-digit', timeZone }).format(ms)
}
