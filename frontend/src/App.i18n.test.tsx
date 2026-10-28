import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { expect, test, vi } from 'vitest'
import { initLanguage, STORAGE_KEY } from './i18n/language'
import { jsonResponse, today } from './test/fixtures'
import { renderApp, seedSession, stubFetch } from './test/appHarness'

const health = { status: 'UP', service: 'attendance-backend', serverTime: '2026-10-13T18:00:00Z' }

test('switching language re-translates the page, the health status and the document', async () => {
  stubFetch((url) => (url === '/api/health' ? jsonResponse(health) : undefined))
  const user = userEvent.setup()
  renderApp('/admin')
  await screen.findByText(/Servidor en línea/)

  await user.click(screen.getByRole('button', { name: 'English' }))

  expect(screen.getByRole('heading', { name: 'Admin panel' })).toBeDefined()
  expect(screen.getByRole('status').textContent).toMatch(/Server online \(UP\) - 10\/13\/2026, 12:00/)
  expect(document.documentElement.lang).toBe('en')
  expect(document.title).toBe('Attendance Control')
})

test('the main screen re-translates on a language switch', async () => {
  await seedSession()
  stubFetch((url) => (url === '/api/time-entries/today' ? jsonResponse(today()) : undefined))
  const user = userEvent.setup()
  renderApp('/')
  await screen.findByText('Aún no has marcado hoy')

  await user.click(screen.getByRole('button', { name: 'English' }))

  expect(screen.getByRole('heading', { name: 'Hello, María' })).toBeDefined()
  expect(screen.getByText("You haven't clocked in today")).toBeDefined()
  expect(screen.getByRole('button', { name: 'Clock in' })).toBeDefined()
})

test('an error shown by the health status is re-translated on a language switch', async () => {
  stubFetch(() => new Response('boom', { status: 503 }))
  const user = userEvent.setup()
  renderApp('/admin')
  await screen.findByText('No se pudo contactar al servidor: HTTP 503')
  await user.click(screen.getByRole('button', { name: 'English' }))
  expect(screen.getByRole('status').textContent).toBe('Could not reach the server: HTTP 503')
})

test('the choice survives a reload (stored value wins on the next initLanguage)', async () => {
  stubFetch()
  const user = userEvent.setup()
  const first = renderApp('/login')
  await user.click(screen.getByRole('button', { name: 'English' }))
  first.unmount()

  initLanguage()
  expect(localStorage.getItem(STORAGE_KEY)).toBe('en')
  expect(document.documentElement.lang).toBe('en')
  expect(document.title).toBe('Attendance Control')
})

test('switching still works when localStorage throws on write', async () => {
  stubFetch()
  vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
    throw new DOMException('quota', 'QuotaExceededError')
  })
  const user = userEvent.setup()
  renderApp('/login')
  await user.click(screen.getByRole('button', { name: 'English' }))
  expect(screen.getByRole('heading', { name: 'Sign in' })).toBeDefined()
  expect(screen.getByRole('button', { name: 'English' }).getAttribute('aria-pressed')).toBe('true')
})

test('pressing the already active language changes and announces nothing', async () => {
  stubFetch()
  const user = userEvent.setup()
  const { container } = renderApp('/login')
  await user.click(screen.getByRole('button', { name: 'Español' }))
  expect(container.querySelector('.language-switcher [aria-live="polite"]')?.textContent).toBe('')
  expect(localStorage.getItem(STORAGE_KEY)).toBeNull()
})
