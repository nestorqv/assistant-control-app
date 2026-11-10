import { configure } from '@testing-library/react'
import { vi } from 'vitest'
import App from '../App'
import { saveIdentity } from '../storage/identity'
import type { Identity } from '../storage/db'
import type { Language } from '../i18n/language'
import { renderWithI18n } from './renderWithI18n'

// The first render of the whole app lazy-loads route chunks; on a loaded machine that exceeds the 1 s default of
// findBy*/waitFor (reauth.test.tsx flaked on the first findByRole). Same value as the admin test backends.
configure({ asyncUtilTimeout: 5000 })
// Tests chain several findBy*/waitFor calls, so the 5 s default test timeout would equal one of them.
vi.setConfig({ testTimeout: 20_000 })

export const identity: Identity = {
  employeeId: 7,
  name: 'María Pérez',
  role: 'EMPLOYEE',
  companyName: 'DEMO',
  timeZone: 'America/Costa_Rica',
  companyCode: 'DEMO',
  employeeCode: '1001',
}

/** Seeds a signed-in device the way a login would: identity in IndexedDB, token in sessionStorage. */
export async function seedSession(over: { identity?: Partial<Identity>; token?: boolean; consentPending?: boolean; expiresAt?: number } = {}) {
  const who = { ...identity, ...over.identity }
  await saveIdentity(who)
  if (over.token !== false) {
    sessionStorage.setItem(
      'attendance.session',
      JSON.stringify({
        token: 'token-1',
        expiresAt: over.expiresAt ?? Date.parse('2099-01-01T00:00:00Z'),
        employeeId: who.employeeId,
        consentPending: over.consentPending === true,
      }),
    )
  }
  return who
}

type Handler = (url: string, init?: RequestInit) => Response | Promise<Response | undefined> | undefined

/** A fetch stub: handlers are tried in order; anything unhandled rejects like an unreachable server. */
export function stubFetch(...handlers: Handler[]) {
  const calls: { url: string; init?: RequestInit }[] = []
  const mock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input)
    calls.push({ url, init })
    for (const handler of handlers) {
      const response = await handler(url, init)
      if (response) return response
    }
    throw new TypeError('network down')
  })
  vi.stubGlobal('fetch', mock)
  return { mock, calls }
}

export function renderApp(path: string, lang: Language = 'es') {
  return renderWithI18n(<App />, { route: path, lang, providers: true })
}
