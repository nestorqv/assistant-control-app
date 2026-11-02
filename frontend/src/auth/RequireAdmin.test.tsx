import { screen } from '@testing-library/react'
import { Route, Routes, useLocation } from 'react-router'
import { expect, test } from 'vitest'
import { adminBackend } from '../test/adminBackend'
import { adminIdentity } from '../test/adminFixtures'
import { renderApp, seedSession } from '../test/appHarness'
import { renderWithI18n } from '../test/renderWithI18n'
import { RequireAdmin } from './RequireAdmin'

function WhereAmI() {
  const { pathname, search } = useLocation()
  return <p data-testid="where">{pathname + search}</p>
}

function renderGuard(route: string) {
  return renderWithI18n(
    <Routes>
      <Route path="/login" element={<WhereAmI />} />
      <Route path="/" element={<WhereAmI />} />
      <Route
        path="/admin/*"
        element={
          <RequireAdmin>
            <p>panel</p>
          </RequireAdmin>
        }
      />
    </Routes>,
    { route, providers: true },
  )
}

test('without a session the guard redirects to /login with the encoded path and query as next', async () => {
  adminBackend()
  renderGuard('/admin/review?status=REJECTED')
  expect((await screen.findByTestId('where')).textContent).toBe('/login?next=%2Fadmin%2Freview%3Fstatus%3DREJECTED')
})

test('the next of a plain admin path is /admin/review encoded', async () => {
  adminBackend()
  renderGuard('/admin/review')
  expect((await screen.findByTestId('where')).textContent).toBe('/login?next=%2Fadmin%2Freview')
})

test('an employee is redirected to / and the panel is not rendered', async () => {
  adminBackend()
  await seedSession()
  renderGuard('/admin/review')
  expect((await screen.findByTestId('where')).textContent).toBe('/')
  expect(screen.queryByText('panel')).toBeNull()
})

test('an employee is sent home and the admin API is never called', async () => {
  const { calls } = adminBackend()
  await seedSession()
  renderApp('/admin/review')
  expect(await screen.findByRole('heading', { name: 'Hola, María' })).toBeDefined()
  expect(calls.filter((c) => c.url.startsWith('/api/admin'))).toHaveLength(0)
  expect(screen.queryByRole('heading', { level: 1, name: 'Asistencia de hoy' })).toBeNull()
})

test.each(['/admin', '/admin/review', '/admin/review?status=REJECTED', '/admin/anything/else'])(
  'a device without a session at %s goes to the login and does not call the admin API',
  async (path) => {
    const { calls } = adminBackend()
    renderApp(path)
    expect(await screen.findByRole('heading', { name: 'Iniciar sesión' })).toBeDefined()
    expect(calls.filter((c) => c.url.startsWith('/api/admin'))).toHaveLength(0)
  },
)

test('an admin whose session token is gone (cold open) must sign in again', async () => {
  const { calls } = adminBackend()
  await seedSession({ identity: adminIdentity, token: false })
  renderApp('/admin')
  expect(await screen.findByRole('heading', { name: 'Iniciar sesión' })).toBeDefined()
  expect(calls.filter((c) => c.url.startsWith('/api/admin/dashboard'))).toHaveLength(0)
})

test('an admin with a valid token reaches the panel, and an unknown admin path lands on the dashboard', async () => {
  adminBackend()
  await seedSession({ identity: adminIdentity })
  renderApp('/admin/does-not-exist')
  expect(await screen.findByRole('heading', { level: 1, name: 'Asistencia de hoy' })).toBeDefined()
})
