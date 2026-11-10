import { describe, expect, test } from 'vitest'
import { parsePastedPair, parseLatitude, parseLongitude, parseRadius } from './coordinates'
import { cleanText, hasDigitRun, hasUnsafeText, isTextInRange } from './text'

// The same lists as backend common/TextRulesTest: the client mirrors the server rule.
describe('text rule (L-1)', () => {
  test.each([
    ['right-to-left override', 'a‮b'],
    ['isolate', 'a⁦b'],
    ['zero-width space', 'a​b'],
    ['zero-width joiner', 'a‍b'],
    ['word joiner', 'a⁠b'],
    ['BOM', 'a﻿b'],
    ['line separator', 'a b'],
    ['paragraph separator', 'a b'],
    ['NUL', 'a\u0000b'],
    ['tab', 'a\tb'],
    ['line feed', 'a\nb'],
    ['lone surrogate', 'a\uD800b'],
  ])('%s is refused', (_name, text) => {
    expect(hasUnsafeText(text)).toBe(true)
  })

  test.each(['José Ñandú Pérez', 'Ana María O Neil', 'Café 24/7', 'Turno 😀', 'plain text'])('%s passes', (text) => {
    expect(hasUnsafeText(text)).toBe(false)
  })

  test('cleanText applies NFC and strips', () => {
    const cleaned = cleanText('  José  ')
    expect(cleaned).toBe('José')
    expect(cleaned).toHaveLength(4)
  })

  test('length is counted after cleaning and in UTF-16 units, like the server', () => {
    expect(isTextInRange('   ', 1, 120)).toBe(false)
    expect(isTextInRange('😀'.repeat(60), 1, 120)).toBe(true)
    expect(isTextInRange('😀'.repeat(61), 1, 120)).toBe(false)
    expect(isTextInRange('x'.repeat(121), 1, 120)).toBe(false)
    expect(isTextInRange('', 0, 80)).toBe(true)
  })

  test.each(['1234', '12 34', '1-2345-6789', '8888.8888', 'x 12/34 y', '١٢٣٤',
    '12 34 56 78', '12 34 56 78', '12 34 56 78', '12　34　56　78'])('digit run %s is refused', (text) => {
    expect(hasDigitRun(text)).toBe(true)
  })

  test.each(['123', '12 a 34', 'plain text', 'ticket 12', ''])('%s has no digit run', (text) => {
    expect(hasDigitRun(text)).toBe(false)
  })
})

describe('branch numbers', () => {
  test('a pasted pair splits and a decimal comma does not', () => {
    expect(parsePastedPair('9.9325, -84.0796')).toEqual({ kind: 'pair', latitude: '9.9325', longitude: '-84.0796' })
    expect(parsePastedPair(' 10,-84 ')).toEqual({ kind: 'pair', latitude: '10', longitude: '-84' })
    expect(parsePastedPair('9,9325')).toEqual({ kind: 'comma' })
    expect(parsePastedPair('9,9325, -84,0796')).toEqual({ kind: 'comma' })
    expect(parsePastedPair('hello')).toEqual({ kind: 'none' })
  })

  test('latitude, longitude and radius ranges', () => {
    expect(parseLatitude('9.9')).toBe(9.9)
    expect(parseLatitude('91')).toBeNull()
    expect(parseLatitude('NaN')).toBeNull()
    expect(parseLatitude('9,9')).toBeNull()
    expect(parseLongitude('-181')).toBeNull()
    expect(parseLongitude('-84.07')).toBe(-84.07)
    expect(parseRadius('9')).toBeNull()
    expect(parseRadius('10')).toBe(10)
    expect(parseRadius('5001')).toBeNull()
    expect(parseRadius('100.5')).toBeNull()
  })
})
