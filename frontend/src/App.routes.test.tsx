import { screen } from '@testing-library/react'
import { expect, test, vi } from 'vitest'
import App from './App'
import type { Language } from './i18n/language'
import { renderWithI18n } from './test/renderWithI18n'

function renderAt(path: string, lang: Language = 'es') {
  vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('offline')))
  return renderWithI18n(<App />, { route: path, lang })
}

test('/ renders the employee home', async () => {
  renderAt('/')
  await screen.findByRole('heading', { name: 'Control de Asistencia' })
  await screen.findByText(/Pantalla del empleado/)
  expect(screen.queryByRole('heading', { name: 'Panel del administrador' })).toBeNull()
})

test('/admin renders the admin home', async () => {
  renderAt('/admin')
  await screen.findByRole('heading', { name: 'Panel del administrador' })
  expect(screen.queryByText(/Pantalla del empleado/)).toBeNull()
})

test('unknown path renders not found with a link home', async () => {
  renderAt('/no-existe/algo')
  await screen.findByText('Página no encontrada')
  const link = await screen.findByRole('link')
  expect(link.getAttribute('href')).toBe('/')
})

test('/admin/ with trailing slash still renders admin', async () => {
  renderAt('/admin/')
  await screen.findByRole('heading', { name: 'Panel del administrador' })
})

test('each page renders the health status region', async () => {
  renderAt('/')
  await screen.findByRole('status')
})

test('English renders the same routes in English', async () => {
  const { unmount } = renderAt('/', 'en')
  await screen.findByRole('heading', { name: 'Attendance Control' })
  unmount()

  const admin = renderAt('/admin', 'en')
  await screen.findByRole('heading', { name: 'Admin panel' })
  admin.unmount()

  renderAt('/nowhere', 'en')
  await screen.findByText('Page not found')
})

test.each(['/', '/admin', '/no-existe'])('the language switcher is present on %s', async (path) => {
  renderAt(path)
  expect(await screen.findByRole('group', { name: 'Idioma' })).toBeDefined()
  expect(screen.getByRole('button', { name: 'English' })).toBeDefined()
})
