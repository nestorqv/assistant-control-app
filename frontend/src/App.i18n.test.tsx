import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { expect, test, vi } from 'vitest'
import App from './App'
import { initLanguage, STORAGE_KEY } from './i18n/language'
import { renderWithI18n } from './test/renderWithI18n'

const json = { 'content-type': 'application/json' }
const health = { status: 'UP', service: 'attendance-backend', serverTime: '2026-10-13T18:00:00Z' }

test('switching language in the app re-translates the page, the health status and the document', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify(health), { headers: json })))
  const user = userEvent.setup()
  renderWithI18n(<App />, { route: '/' })
  await screen.findByText(/Servidor en línea/)

  await user.click(screen.getByRole('button', { name: 'English' }))

  expect(screen.getByRole('heading', { name: 'Attendance Control' })).toBeDefined()
  expect(screen.getByText('Employee screen (under construction)')).toBeDefined()
  expect(screen.getByRole('link', { name: 'Go to the admin panel' })).toBeDefined()
  expect(screen.getByRole('status').textContent).toMatch(/Server online \(UP\) - 10\/13\/2026, 12:00/)
  expect(document.documentElement.lang).toBe('en')
  expect(document.title).toBe('Attendance Control')
})

test('an error shown by the health status is re-translated on a language switch', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('boom', { status: 503 })))
  const user = userEvent.setup()
  renderWithI18n(<App />, { route: '/admin' })
  await screen.findByText('No se pudo contactar al servidor: HTTP 503')
  await user.click(screen.getByRole('button', { name: 'English' }))
  expect(screen.getByRole('status').textContent).toBe('Could not reach the server: HTTP 503')
})

test('requests made after an in-app switch carry the new Accept-Language', async () => {
  const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify(health), { headers: json }))
  vi.stubGlobal('fetch', fetchMock)
  const user = userEvent.setup()
  renderWithI18n(<App />, { route: '/' })
  await screen.findByText(/Servidor en línea/)
  expect(new Headers(fetchMock.mock.calls[0][1].headers).get('Accept-Language')).toBe('es')

  await user.click(screen.getByRole('button', { name: 'English' }))
  // Navigating to another page mounts a new HealthStatus, which fetches again.
  await user.click(screen.getByRole('link', { name: 'Go to the admin panel' }))
  await waitFor(() => expect(fetchMock.mock.calls.length).toBe(2))
  expect(new Headers(fetchMock.mock.calls[1][1].headers).get('Accept-Language')).toBe('en')
})

test('the choice survives a reload (stored value wins on the next initLanguage)', async () => {
  vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('offline')))
  const user = userEvent.setup()
  const first = renderWithI18n(<App />, { route: '/' })
  await user.click(screen.getByRole('button', { name: 'English' }))
  first.unmount()

  initLanguage()
  expect(localStorage.getItem(STORAGE_KEY)).toBe('en')
  expect(document.documentElement.lang).toBe('en')
  expect(document.title).toBe('Attendance Control')
})

test('switching still works when localStorage throws on write', async () => {
  vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('offline')))
  vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
    throw new DOMException('quota', 'QuotaExceededError')
  })
  const user = userEvent.setup()
  renderWithI18n(<App />, { route: '/' })
  await user.click(screen.getByRole('button', { name: 'English' }))
  expect(screen.getByRole('heading', { name: 'Attendance Control' })).toBeDefined()
  expect(screen.getByRole('button', { name: 'English' }).getAttribute('aria-pressed')).toBe('true')
})

test('pressing the already active language changes and announces nothing', async () => {
  vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('offline')))
  const user = userEvent.setup()
  const { container } = renderWithI18n(<App />, { route: '/' })
  await user.click(screen.getByRole('button', { name: 'Español' }))
  expect(container.querySelector('.language-switcher [aria-live="polite"]')?.textContent).toBe('')
  expect(localStorage.getItem(STORAGE_KEY)).toBeNull()
})
