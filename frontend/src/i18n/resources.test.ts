import { expect, test } from 'vitest'
import { adminEn, adminEs } from './adminResources'
import { en, es } from './resources'

const placeholders = (text: string) => (text.match(/\{\w+\}/g) ?? []).sort()
const keys = Object.keys(es) as (keyof typeof es)[]

test('es and en have exactly the same keys', () => {
  expect(Object.keys(en).sort()).toEqual(Object.keys(es).sort())
})

test('no text is empty', () => {
  for (const key of keys) {
    expect(es[key].trim(), `es ${key}`).not.toBe('')
    expect(en[key].trim(), `en ${key}`).not.toBe('')
  }
})

test('placeholders match between languages', () => {
  for (const key of keys) {
    expect(placeholders(en[key]), key).toEqual(placeholders(es[key]))
  }
})

// Language-neutral texts are the same in both languages on purpose.
const SAME_IN_BOTH: string[] = [
  'health.httpStatus', // "HTTP 503"
  'login.pin', // "PIN" is the same word in both languages
  'duration.hm', // "9 h 05 min": international symbols
  'duration.m', // "45 min"
  'home.noTime', // "--:--" placeholder
]

test('english differs from spanish except the language endonyms', () => {
  for (const key of keys) {
    if (key.startsWith('language.name.') || SAME_IN_BOTH.includes(key)) {
      expect(en[key]).toBe(es[key])
    } else {
      expect(en[key], key).not.toBe(es[key])
    }
  }
})

// Keys that are the same word or symbol in both languages.
const ADMIN_SAME_IN_BOTH: string[] = [
  'admin.dashboard.subtitle', // "{company} · {branch}"
  'admin.kpi.toReviewReason',
  'admin.evidence.accuracyValue',
  'admin.evidence.pairValue',
  'admin.evidence.source.TABLET', // "Tablet"
  'admin.review.distance',
]

test('admin: es and en have the same keys, texts and placeholders', () => {
  expect(Object.keys(adminEn).sort()).toEqual(Object.keys(adminEs).sort())
  for (const key of Object.keys(adminEs) as (keyof typeof adminEs)[]) {
    expect(adminEs[key].trim(), key).not.toBe('')
    expect(placeholders(adminEn[key]), key).toEqual(placeholders(adminEs[key]))
    if (ADMIN_SAME_IN_BOTH.includes(key)) expect(adminEn[key]).toBe(adminEs[key])
    else expect(adminEn[key], key).not.toBe(adminEs[key])
  }
})

test('admin keys never repeat a key of the employee resources', () => {
  const shared = Object.keys(adminEs).filter((key) => key in es)
  expect(shared).toEqual([])
})

// Spanish is "usted" everywhere. \b is ASCII-only in JavaScript, so a plain \btú\b can never match ("ú" is not a
// word character); the lookarounds below are Unicode-aware (u flag).
const TU_FORMS =
  /(?<![\p{L}\p{N}_])(tú|tu|tus|ti|te|tienes|puedes|quieres|debes|necesitas|eres|llevas|tenías|estás|tendrás|podrás|fallaste|marcaste|ingresa|escribe|revisa|contacta|vuelve|elige|toca|(?<![}] )(?<!ya )usa|centra|libera|conéctate|inténtalo|ciérrala|espera)(?![\p{L}\p{N}_])/iu

test('the "tú" pattern really matches accented forms (regression)', () => {
  expect(TU_FORMS.test('¿No eres tú?')).toBe(true)
  expect(TU_FORMS.test('Escribe tu código')).toBe(true)
  expect(TU_FORMS.test('¿No es usted?')).toBe(false)
  expect(TU_FORMS.test('actuar')).toBe(false)
})

test('no Spanish text uses the "tú" register', () => {
  for (const [key, value] of Object.entries(es)) expect(TU_FORMS.test(value), key + ': ' + value).toBe(false)
  for (const [key, value] of Object.entries(adminEs)) expect(TU_FORMS.test(value), key + ': ' + value).toBe(false)
})
