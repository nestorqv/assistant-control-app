import { render, screen } from '@testing-library/react'
import { expect, test, vi } from 'vitest'
import { HealthStatus } from './HealthStatus'

const json = { 'content-type': 'application/json' }

test('shows the loading state while the request is pending', async () => {
  vi.stubGlobal('fetch', vi.fn().mockReturnValue(new Promise(() => {})))
  render(<HealthStatus />)
  const status = await screen.findByRole('status')
  expect(status.textContent).toContain('Verificando servidor...')
})

test('formats the server time in America/Costa_Rica (UTC-6)', async () => {
  const body = { status: 'UP', service: 'asistencia-backend', serverTime: '2026-10-13T18:00:00Z' }
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify(body), { headers: json })))
  render(<HealthStatus />)
  const el = await screen.findByText(/Servidor en línea/)
  expect(el.textContent).toContain('(UP)')
  expect(el.textContent).toMatch(/12:00/)
})

test('shows the HTTP status on a non-200 response', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('boom', { status: 503 })))
  render(<HealthStatus />)
  const el = await screen.findByText(/No se pudo contactar al servidor/)
  expect(el.textContent).toContain('HTTP 503')
})

test('shows an error when a 200 response is not JSON (e.g. proxy HTML)', async () => {
  vi.stubGlobal(
    'fetch',
    vi.fn().mockResolvedValue(new Response('<html></html>', { headers: { 'content-type': 'text/html' } })),
  )
  render(<HealthStatus />)
  const el = await screen.findByText(/No se pudo contactar al servidor/)
  expect(el.textContent).toContain('Respuesta inesperada del servidor')
})

test('shows the network failure message', async () => {
  vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')))
  render(<HealthStatus />)
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
  const { unmount } = render(<HealthStatus />)
  expect(signal?.aborted).toBe(false)
  unmount()
  expect(signal?.aborted).toBe(true)
})

test('status region is announced politely', async () => {
  vi.stubGlobal('fetch', vi.fn().mockReturnValue(new Promise(() => {})))
  render(<HealthStatus />)
  const status = await screen.findByRole('status')
  expect(status.getAttribute('aria-live')).toBe('polite')
})
