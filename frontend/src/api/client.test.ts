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

test('PUT and DELETE are sent with their method', async () => {
  const fetchMock = vi.fn(async (_url: string, _init?: RequestInit) => new Response('{}', { headers: { 'content-type': 'application/json' } }))
  vi.stubGlobal('fetch', fetchMock)
  const { apiRequest } = await import('./client')
  await apiRequest('/api/x', { method: 'PUT', json: { a: 1 } })
  await apiRequest('/api/x', { method: 'DELETE' })
  expect(fetchMock.mock.calls[0][1]?.method).toBe('PUT')
  expect(fetchMock.mock.calls[0][1]?.body).toBe('{"a":1}')
  expect(fetchMock.mock.calls[1][1]?.method).toBe('DELETE')
})

test('apiBlob returns the blob and the safe filename, and maps problems', async () => {
  const { apiBlob, filenameFrom } = await import('./client')
  vi.stubGlobal(
    'fetch',
    vi.fn().mockResolvedValueOnce(new Response('xlsx', { headers: { 'Content-Disposition': 'attachment; filename="asistencia_DEMO_2026-10-01_2026-10-15.xlsx"' } })),
  )
  const ok = await apiBlob('/api/admin/reports/excel')
  expect(ok.filename).toBe('asistencia_DEMO_2026-10-01_2026-10-15.xlsx')
  expect(await ok.blob.text()).toBe('xlsx')
  expect(filenameFrom('attachment; filename="../evil.sh"', 'fallback.bin')).toBe('fallback.bin')
  expect(filenameFrom(null, 'photo.jpg')).toBe('photo.jpg')

  vi.stubGlobal(
    'fetch',
    vi.fn().mockResolvedValueOnce(new Response('{"code":"NOT_FOUND"}', { status: 404, headers: { 'content-type': 'application/problem+json' } })),
  )
  await expect(apiBlob('/api/admin/time-entries/1/photo')).rejects.toMatchObject({ status: 404, code: 'NOT_FOUND' })
})
