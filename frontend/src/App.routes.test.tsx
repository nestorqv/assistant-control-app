import { screen } from '@testing-library/react'
import { expect, test } from 'vitest'
import { jsonResponse, entrySummary, today } from './test/fixtures'
import { renderApp, seedSession, stubFetch } from './test/appHarness'

const health = { status: 'UP', service: 'attendance-backend', serverTime: '2026-10-13T18:00:00Z' }

function backend(over: Parameters<typeof today>[0] = {}) {
  return stubFetch(
    (url) => (url === '/api/time-entries/today' ? jsonResponse(today(over)) : undefined),
    (url) => (url === '/api/health' ? jsonResponse(health) : undefined),
  )
}

test('/ without an identity redirects to the login page', async () => {
  backend()
  renderApp('/')
  expect(await screen.findByRole('heading', { name: 'Iniciar sesión' })).toBeDefined()
})

test('/ with a stored session renders the main screen', async () => {
  await seedSession()
  backend()
  renderApp('/')
  expect(await screen.findByRole('heading', { name: 'Hola, María' })).toBeDefined()
  expect(await screen.findByText('Aún no has marcado hoy')).toBeDefined()
})

test('a session with consent pending landing on / is redirected to /consent', async () => {
  await seedSession({ consentPending: true })
  backend()
  renderApp('/')
  expect(await screen.findByRole('heading', { name: 'Tus datos y tu consentimiento' })).toBeDefined()
})

test('/consent without a pending consent goes to the main screen', async () => {
  await seedSession()
  backend()
  renderApp('/consent')
  expect(await screen.findByRole('heading', { name: 'Hola, María' })).toBeDefined()
})

test('/consent without an identity goes to the login page', async () => {
  backend()
  renderApp('/consent')
  expect(await screen.findByRole('heading', { name: 'Iniciar sesión' })).toBeDefined()
})

test('/login with a valid session redirects to the main screen', async () => {
  await seedSession()
  backend()
  renderApp('/login')
  expect(await screen.findByRole('heading', { name: 'Hola, María' })).toBeDefined()
})

test('/admin renders the placeholder and the health status', async () => {
  backend()
  renderApp('/admin')
  expect(await screen.findByRole('heading', { name: 'Panel del administrador' })).toBeDefined()
  expect((await screen.findByRole('status')).textContent).toMatch(/Servidor en línea/)
  expect(screen.queryByRole('heading', { name: /Hola/ })).toBeNull()
})

test('/admin/ with a trailing slash still renders admin', async () => {
  backend()
  renderApp('/admin/')
  await screen.findByRole('heading', { name: 'Panel del administrador' })
})

test('unknown path renders not found with a link home', async () => {
  backend()
  renderApp('/no-existe/algo')
  await screen.findByText('Página no encontrada')
  expect(screen.getByRole('link').getAttribute('href')).toBe('/')
})

test('English renders the same routes in English', async () => {
  backend()
  const login = renderApp('/login', 'en')
  await screen.findByRole('heading', { name: 'Sign in' })
  login.unmount()

  const admin = renderApp('/admin', 'en')
  await screen.findByRole('heading', { name: 'Admin panel' })
  admin.unmount()

  renderApp('/nowhere', 'en')
  await screen.findByText('Page not found')
})

test.each(['/login', '/admin', '/no-existe'])('the language switcher is present on %s', async (path) => {
  backend()
  renderApp(path)
  expect(await screen.findByRole('group', { name: 'Idioma' })).toBeDefined()
  expect(screen.getByRole('button', { name: 'English' })).toBeDefined()
})

test('the language switcher and the sign-out button are on the main screen', async () => {
  await seedSession()
  backend({ clockIn: entrySummary(), nextAction: 'CLOCK_OUT' })
  renderApp('/')
  await screen.findByRole('heading', { name: 'Hola, María' })
  expect(screen.getByRole('group', { name: 'Idioma' })).toBeDefined()
  expect(screen.getByRole('button', { name: 'Cerrar sesión' })).toBeDefined()
})

test('the sign-out button is not shown on the login page', async () => {
  await seedSession({ token: false })
  backend()
  renderApp('/login')
  await screen.findByRole('heading', { name: 'Iniciar sesión' })
  expect(screen.queryByRole('button', { name: 'Cerrar sesión' })).toBeNull()
})
