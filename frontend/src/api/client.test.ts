import { expect, test, vi } from 'vitest'
import { setLanguage } from '../i18n/language'
import { apiFetch } from './client'
import { fetchHealth } from './health'

function stubFetch() {
  const fetchMock = vi.fn().mockResolvedValue(
    new Response(JSON.stringify({ status: 'UP', service: 'x', serverTime: '2026-10-13T18:00:00Z' }), {
      headers: { 'content-type': 'application/json' },
    }),
  )
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

const sentLanguage = (fetchMock: ReturnType<typeof stubFetch>) =>
  new Headers(fetchMock.mock.calls[0][1]?.headers).get('Accept-Language')

test('sends Accept-Language es by default', async () => {
  const fetchMock = stubFetch()
  await apiFetch('/api/anything')
  expect(sentLanguage(fetchMock)).toBe('es')
})

test('sends Accept-Language en after the language changes', async () => {
  const fetchMock = stubFetch()
  setLanguage('en', false)
  await apiFetch('/api/anything')
  expect(sentLanguage(fetchMock)).toBe('en')
})

test('keeps the signal and other headers, but not a caller Accept-Language', async () => {
  const fetchMock = stubFetch()
  const controller = new AbortController()
  await apiFetch('/api/anything', {
    signal: controller.signal,
    headers: { 'X-Test': '1', 'Accept-Language': 'fr' },
  })
  const init = fetchMock.mock.calls[0][1] as RequestInit
  expect(init.signal).toBe(controller.signal)
  expect(new Headers(init.headers).get('X-Test')).toBe('1')
  expect(sentLanguage(fetchMock)).toBe('es')
})

test('fetchHealth goes through apiFetch', async () => {
  const fetchMock = stubFetch()
  setLanguage('en', false)
  await fetchHealth()
  expect(fetchMock.mock.calls[0][0]).toBe('/api/health')
  expect(sentLanguage(fetchMock)).toBe('en')
})
