import { render, screen } from '@testing-library/react'
import { expect, test, vi } from 'vitest'
import { HealthStatus } from './HealthStatus'

test('shows the server as online when /api/health succeeds', async () => {
  const body = { status: 'UP', service: 'asistencia-backend', serverTime: '2026-10-13T18:00:00Z' }
  vi.stubGlobal(
    'fetch',
    vi.fn().mockResolvedValue(
      new Response(JSON.stringify(body), { headers: { 'content-type': 'application/json' } }),
    ),
  )

  render(<HealthStatus />)

  await screen.findByText(/Servidor en línea/)
  expect(fetch).toHaveBeenCalledWith('/api/health', expect.anything())
})

test('shows an error when the request fails', async () => {
  vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('network down')))

  render(<HealthStatus />)

  await screen.findByText(/No se pudo contactar al servidor/)
})
