import { resources } from './resources'

export const SUPPORTED = ['es', 'en'] as const
export type Language = (typeof SUPPORTED)[number]

export const STORAGE_KEY = 'attendance.lang'
const DEFAULT_LANGUAGE: Language = 'es'

let current: Language = DEFAULT_LANGUAGE
const listeners = new Set<() => void>()

function isLanguage(value: unknown): value is Language {
  return typeof value === 'string' && (SUPPORTED as readonly string[]).includes(value)
}

// Storage can throw (Safari private mode, disabled storage, quota): fall back to memory only.
function readStored(): Language | null {
  try {
    const value = localStorage.getItem(STORAGE_KEY)
    return isLanguage(value) ? value : null
  } catch {
    return null
  }
}

function writeStored(lang: Language) {
  try {
    localStorage.setItem(STORAGE_KEY, lang)
  } catch {
    // Not persisted; the choice still applies until the page is closed.
  }
}

/** Stored choice, else the browser's primary language when supported, else Spanish. */
export function detectInitialLanguage(): Language {
  const stored = readStored()
  if (stored) return stored
  const primary = (navigator.languages?.[0] ?? navigator.language ?? '').toLowerCase().split('-')[0]
  return isLanguage(primary) ? primary : DEFAULT_LANGUAGE
}

function applyToDocument(lang: Language) {
  document.documentElement.lang = lang
  document.title = resources[lang]['app.title']
}

export function getLanguage(): Language {
  return current
}

export function setLanguage(lang: Language, persist = true) {
  if (!isLanguage(lang)) return
  current = lang
  if (persist) writeStored(lang)
  applyToDocument(lang)
  listeners.forEach((listener) => listener())
}

export function subscribe(listener: () => void) {
  listeners.add(listener)
  return () => {
    listeners.delete(listener)
  }
}

/** Call once before the first render so <html lang> and the title do not flash. */
export function initLanguage() {
  setLanguage(detectInitialLanguage(), false)
}
