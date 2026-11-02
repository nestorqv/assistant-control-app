import { screen } from '@testing-library/react'
import { afterEach, expect, test, vi } from 'vitest'
import { adminIdentity } from './test/adminFixtures'
import { renderApp, seedSession } from './test/appHarness'

// The lazy admin chunk cannot be loaded (offline, or an old hashed file after a deploy).
vi.mock('./admin/AdminApp', () => {
  throw new Error('Failed to fetch dynamically imported module: /assets/admin-abc123.js')
})

afterEach(() => vi.restoreAllMocks())

test('a chunk that fails to load shows the translated error and a retry button, not a blank page', async () => {
  vi.spyOn(console, 'error').mockImplementation(() => undefined)
  const warn = vi.spyOn(console, 'warn').mockImplementation(() => undefined)
  await seedSession({ identity: adminIdentity })
  renderApp('/admin')

  expect((await screen.findByRole('alert')).textContent).toBe('No se pudo cargar el panel. Revise su conexión.')
  expect(screen.getByRole('button', { name: 'Reintentar' })).toBeDefined()
  expect(screen.queryByRole('heading', { level: 1, name: 'Asistencia de hoy' })).toBeNull()
  // The log carries the error name only, never the message or the asset path.
  const logged = JSON.stringify(warn.mock.calls)
  expect(logged).toContain('[admin] console failed to load')
  expect(logged).not.toContain('admin-abc123')
})

test('the failure text is also available in English', async () => {
  vi.spyOn(console, 'error').mockImplementation(() => undefined)
  vi.spyOn(console, 'warn').mockImplementation(() => undefined)
  await seedSession({ identity: adminIdentity })
  renderApp('/admin', 'en')
  expect((await screen.findByRole('alert')).textContent).toBe('The panel could not be loaded. Check your connection.')
  expect(screen.getByRole('button', { name: 'Try again' })).toBeDefined()
})
