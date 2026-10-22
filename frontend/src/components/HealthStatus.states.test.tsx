import { screen } from '@testing-library/react'
import { expect, test, vi } from 'vitest'
import { es } from '../i18n/resources'
import { renderWithI18n } from '../test/renderWithI18n'
import { HealthStatus } from './HealthStatus'

const json = { 'content-type': 'application/json' }
const body = { status: 'UP', service: 'attendance-backend', serverTime: '2026-10-13T18:00:00Z' }

test('shows the loading state while the request is pending', async () => {
  vi.stubGlobal('fetch', vi.fn().mockReturnValue(new Promise(() => {})))
  renderWithI18n(<HealthStatus />)
  const status = await screen.findByRole('status')
  expect(status.textContent).toContain(es['health.checking'])
})

test('formats the server time in America/Costa_Rica (UTC-6)', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify(body), { headers: json })))
  renderWithI18n(<HealthStatus />)
  const el = await screen.findByText(/Servidor en línea/)
  expect(el.textContent).toContain('(UP)')
  expect(el.textContent).toMatch(/12:00/)
})

test('formats the server time for English in the same zone', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify(body), { headers: json })))
  renderWithI18n(<HealthStatus />, { lang: 'en' })
  const el = await screen.findByText(/Server online/)
  expect(el.textContent).toContain('(UP)')
  // en-US uses month/day/year and a 12-hour clock.
  expect(el.textContent).toMatch(/10\/13\/2026, 12:00/)
})

test('shows the HTTP status on a non-200 response', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('boom', { status: 503 })))
  renderWithI18n(<HealthStatus />)
  const el = await screen.findByText(/No se pudo contactar al servidor/)
  expect(el.textContent).toContain('HTTP 503')
})

test('shows an error when a 200 response is not JSON (e.g. proxy HTML)', async () => {
  vi.stubGlobal(
    'fetch',
    vi.fn().mockResolvedValue(new Response('<html></html>', { headers: { 'content-type': 'text/html' } })),
  )
  renderWithI18n(<HealthStatus />)
  const el = await screen.findByText(/No se pudo contactar al servidor/)
  expect(el.textContent).toContain(es['health.unexpectedResponse'])
})

test('translates the unexpected-response error in English', async () => {
  vi.stubGlobal(
    'fetch',
    vi.fn().mockResolvedValue(new Response('<html></html>', { headers: { 'content-type': 'text/html' } })),
  )
  renderWithI18n(<HealthStatus />, { lang: 'en' })
  const el = await screen.findByText(/Could not reach the server/)
  expect(el.textContent).toContain('Unexpected response from the server')
})

test('shows the network failure message', async () => {
  vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')))
  renderWithI18n(<HealthStatus />)
  const el = await screen.findByText(/No se pudo contactar al servidor/)
  expect(el.textContent).toContain('Failed to fetch')
})

test('aborts the request on unmount', async () => {
  let signal: AbortSignal | undefined
  vi.stubGlobal(
    'fetch',
    vi.fn((_url: string, init?: RequestInit) => {
      signal = init?.signal ?? undefined
      return new Promise(() => {})
    }),
  )
  const { unmount } = renderWithI18n(<HealthStatus />)
  expect(signal?.aborted).toBe(false)
  unmount()
  expect(signal?.aborted).toBe(true)
})

test('status region is announced politely', async () => {
  vi.stubGlobal('fetch', vi.fn().mockReturnValue(new Promise(() => {})))
  renderWithI18n(<HealthStatus />)
  const status = await screen.findByRole('status')
  expect(status.getAttribute('aria-live')).toBe('polite')
})
