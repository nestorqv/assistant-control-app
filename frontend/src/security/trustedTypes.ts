// Trusted Types "default" policy. navigator.serviceWorker.register() is a TrustedScriptURL sink and
// workbox-window passes a plain string, so without a default policy the strict CSP
// (require-trusted-types-for 'script') would block the service worker. Only /sw.js is allowed;
// every other sink (HTML, script text, any other script URL) throws.

const ALLOWED_SCRIPT_PATH = '/sw.js'

/** Same origin, path /sw.js, nothing else (no query, no other scheme). */
export function scriptUrlAllowed(url: string, base: string = globalThis.location?.href ?? 'http://localhost/'): boolean {
  try {
    const parsed = new URL(url, base)
    const origin = new URL(base).origin
    return parsed.origin === origin && parsed.pathname === ALLOWED_SCRIPT_PATH && parsed.search === '' && parsed.hash === ''
  } catch {
    return false
  }
}

type PolicyFactory = {
  createPolicy(
    name: string,
    rules: {
      createHTML: () => never
      createScript: () => never
      createScriptURL: (input: string) => string
    },
  ): unknown
}

/** A no-op where Trusted Types do not exist (Firefox, older Safari). */
export function installTrustedTypesPolicy(factory: PolicyFactory | undefined = (globalThis as { trustedTypes?: PolicyFactory }).trustedTypes) {
  if (!factory) return false
  factory.createPolicy('default', {
    createHTML: () => {
      throw new TypeError('Trusted Types: HTML is not allowed')
    },
    createScript: () => {
      throw new TypeError('Trusted Types: script text is not allowed')
    },
    createScriptURL: (input: string) => {
      if (!scriptUrlAllowed(input)) throw new TypeError('Trusted Types: script URL is not allowed')
      return input
    },
  })
  return true
}

try {
  installTrustedTypesPolicy()
} catch {
  // A second policy named "default" (hot reload) throws; the first one stays in force.
}
