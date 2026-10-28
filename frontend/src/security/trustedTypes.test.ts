import { describe, expect, test, vi } from 'vitest'
import { installTrustedTypesPolicy, scriptUrlAllowed } from './trustedTypes'

const base = 'https://app.example.com/some/page'

describe('scriptUrlAllowed', () => {
  test('/sw.js on the same origin is allowed', () => {
    expect(scriptUrlAllowed('/sw.js', base)).toBe(true)
    expect(scriptUrlAllowed('https://app.example.com/sw.js', base)).toBe(true)
  })

  test.each([
    'https://evil.example/sw.js',
    '/other.js',
    '/sw.js?x=1',
    '/sw.js#frag',
    '//evil.example/sw.js',
    'javascript:alert(1)',
    'data:text/javascript,alert(1)',
    'blob:https://app.example.com/1234',
    '',
  ])('%s is refused', (url) => {
    expect(scriptUrlAllowed(url, base)).toBe(false)
  })
})

describe('installTrustedTypesPolicy', () => {
  test('creates a default policy that only lets /sw.js through and rejects HTML and script text', () => {
    let rules: Record<string, (input: string) => unknown> = {}
    const createPolicy = vi.fn((_name: string, r: Record<string, (input: string) => unknown>) => {
      rules = r
    })
    expect(installTrustedTypesPolicy({ createPolicy } as never)).toBe(true)

    expect(createPolicy).toHaveBeenCalledWith('default', expect.any(Object))
    expect(rules.createScriptURL('/sw.js')).toBe('/sw.js')
    expect(() => rules.createScriptURL('https://evil/sw.js')).toThrow(TypeError)
    expect(() => rules.createScriptURL('/other.js')).toThrow(TypeError)
    expect(() => rules.createScriptURL('javascript:alert(1)')).toThrow(TypeError)
    expect(() => rules.createHTML('<img onerror=x>')).toThrow(TypeError)
    expect(() => rules.createScript('alert(1)')).toThrow(TypeError)
  })

  test('is a no-op where Trusted Types do not exist', () => {
    expect(installTrustedTypesPolicy(undefined)).toBe(false)
  })
})
