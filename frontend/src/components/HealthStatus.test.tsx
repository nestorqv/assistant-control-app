import { screen } from '@testing-library/react'
import { expect, test, vi } from 'vitest'
import { renderWithI18n } from '../test/renderWithI18n'
import { HealthStatus } from './HealthStatus'

const json = { 'content-type': 'application/json' }
const body = { status: 'UP', service: 'attendance-backend', serverTime: '2026-10-13T18:00:00Z' }

test('shows the server as online when /api/health succeeds', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify(body), { headers: json })))

  renderWithI18n(<HealthStatus />)

  await screen.findByText(/Servidor en línea/)
  expect(fetch).toHaveBeenCalledWith('/api/health', expect.anything())
})

test('sends Accept-Language with the current language', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify(body), { headers: json })))

  renderWithI18n(<HealthStatus />, { lang: 'en' })

  await screen.findByText(/Server online/)
  const init = vi.mocked(fetch).mock.calls[0][1]
  expect(new Headers(init?.headers).get('Accept-Language')).toBe('en')
})

test('shows an error when the request fails', async () => {
  vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('network down')))

  renderWithI18n(<HealthStatus />)

  await screen.findByText(/No se pudo contactar al servidor/)
})
