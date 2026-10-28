import { expect, test } from 'vitest'
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
