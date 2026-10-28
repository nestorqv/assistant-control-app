import { describe, expect, test } from 'vitest'
import { DEV_HEADERS, PROD_CSP, SECURITY_HEADERS } from '../securityHeaders'

const directives = Object.fromEntries(
  PROD_CSP.split(';').map((part) => {
    const [name, ...values] = part.trim().split(/\s+/)
    return [name, values.join(' ')]
  }),
)

describe('PROD_CSP', () => {
  test('contains every directive of the plan', () => {
    expect(directives).toEqual({
      'default-src': "'self'",
      'script-src': "'self'",
      'style-src': "'self'",
      'img-src': "'self' blob:",
      'connect-src': "'self'",
      'worker-src': "'self'",
      'manifest-src': "'self'",
      'font-src': "'self'",
      'media-src': "'self'",
      'object-src': "'none'",
      'base-uri': "'none'",
      'frame-ancestors': "'none'",
      'form-action': "'self'",
      'upgrade-insecure-requests': '',
      'require-trusted-types-for': "'script'",
      'trusted-types': 'default',
    })
  })

  test('has no unsafe-inline, unsafe-eval, data: or third-party origin', () => {
    expect(PROD_CSP).not.toContain('unsafe-inline')
    expect(PROD_CSP).not.toContain('unsafe-eval')
    expect(PROD_CSP).not.toContain('data:')
    expect(PROD_CSP).not.toMatch(/https?:/)
    expect(PROD_CSP).not.toContain('*')
  })
})

describe('headers', () => {
  test('Permissions-Policy allows only the camera and geolocation, for this origin', () => {
    const policy = SECURITY_HEADERS['Permissions-Policy']
    expect(policy).toContain('camera=(self)')
    expect(policy).toContain('geolocation=(self)')
    for (const denied of ['microphone', 'payment', 'usb']) expect(policy).toContain(`${denied}=()`)
    const allowed = policy.split(',').map((part) => part.trim()).filter((part) => !part.endsWith('=()'))
    expect(allowed).toEqual(['camera=(self)', 'geolocation=(self)'])
  })

  test('the other hardening headers are present', () => {
    expect(SECURITY_HEADERS['Referrer-Policy']).toBe('no-referrer')
    expect(SECURITY_HEADERS['X-Content-Type-Options']).toBe('nosniff')
    expect(SECURITY_HEADERS['Cross-Origin-Opener-Policy']).toBe('same-origin')
  })

  test('preview gets the CSP; the dev set has everything except the CSP', () => {
    expect(SECURITY_HEADERS['Content-Security-Policy']).toBe(PROD_CSP)
    expect(DEV_HEADERS).not.toHaveProperty('Content-Security-Policy')
    expect({ ...DEV_HEADERS, 'Content-Security-Policy': PROD_CSP }).toEqual(SECURITY_HEADERS)
  })
})
