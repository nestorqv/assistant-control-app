import { configure } from '@testing-library/react'
import { vi } from 'vitest'
import { dashboard, detail, reviewItem } from './adminFixtures'
import { jsonResponse } from './fixtures'
import { stubFetch } from './appHarness'

// Rendering the whole admin app is heavier than a component test: when the whole suite runs in parallel the
// default 1 s of findBy/waitFor is too tight. Only the waiting limit grows; passing tests are not slower.
configure({ asyncUtilTimeout: 5000 })
vi.setConfig({ testTimeout: 20_000 })

export const branches = [{ id: 1, name: 'Central', latitude: 9.93, longitude: -84.08, radiusMeters: 100, active: true, activeEmployees: 4 }]

export type Routes = {
  dash?: (url: string) => Response
  detailFor?: (id: number) => Response
  photo?: (id: number) => Response
  queue?: (url: string) => Response
  post?: (url: string, body: unknown) => Response | undefined
  login?: () => Response
}

/** A fake admin API. Every route can be replaced; anything not replaced answers with the standard fixtures. */
export function adminBackend(routes: Routes = {}) {
  return stubFetch(
    (url) => (url === '/api/auth/login' ? routes.login?.() : undefined),
    (url) => (url.startsWith('/api/admin/dashboard') ? (routes.dash?.(url) ?? jsonResponse(dashboard())) : undefined),
    (url) => (url === '/api/admin/branches' ? jsonResponse(branches) : undefined),
    (url) => (url === '/api/admin/review-queue/count' ? jsonResponse({ pending: 3, today: '2026-10-07', timeZone: 'America/Costa_Rica' }) : undefined),
    (url) =>
      url.startsWith('/api/admin/review-queue?')
        ? (routes.queue?.(url) ?? jsonResponse({ items: [reviewItem()], page: 0, size: 25, totalItems: 1 }))
        : undefined,
    (url) => (url === '/api/admin/employees' ? jsonResponse([{ id: 2, code: '1004', name: 'Luis Vargas', active: true }]) : undefined),
    (url) => {
      const m = /\/api\/admin\/time-entries\/(\d+)\/photo$/.exec(url)
      if (!m) return undefined
      return routes.photo?.(Number(m[1])) ?? new Response(new Uint8Array([1, 2, 3]), { headers: { 'content-type': 'image/jpeg' } })
    },
    (url) => {
      const m = /\/api\/admin\/time-entries\/(\d+)$/.exec(url)
      if (!m) return undefined
      return routes.detailFor?.(Number(m[1])) ?? jsonResponse(detail())
    },
    (url, init) => {
      if (init?.method !== 'POST') return undefined
      return routes.post?.(url, init.body ? JSON.parse(String(init.body)) : null)
    },
    (url) => (url === '/api/health' ? jsonResponse({ status: 'UP', service: 'x', serverTime: '2026-10-13T18:00:00Z' }) : undefined),
  )
}

/** Counts the object URLs handed out and revoked, so a test can see that none is left behind. */
export function trackObjectUrls() {
  let n = 0
  const created: string[] = []
  const revoked: string[] = []
  vi.stubGlobal(
    'URL',
    Object.assign(URL, {
      createObjectURL: vi.fn(() => {
        const url = `blob:photo-${++n}`
        created.push(url)
        return url
      }),
      revokeObjectURL: vi.fn((url: string) => {
        revoked.push(url)
      }),
    }),
  )
  return { created, revoked }
}

/** Makes (min-width: 48rem) false: the dashboard shows the stacked card list. */
export function narrowScreen() {
  vi.stubGlobal(
    'matchMedia',
    (query: string) => ({
      matches: false,
      media: query,
      addEventListener: () => undefined,
      removeEventListener: () => undefined,
    }),
  )
}
