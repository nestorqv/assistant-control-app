import { afterEach, expect, test, vi } from 'vitest'
import { setLanguage } from '../i18n/language'
import { initSession, resetSessionForTests, signIn } from '../auth/session'
import { stubFetch } from '../test/appHarness'
import { baselineJpegBytes, entryResponse, jsonResponse, loginResponse } from '../test/fixtures'
import { buildEntryForm, postTimeEntry, type SendableEntry } from './timeEntries'

const entry: SendableEntry = {
  type: 'CLOCK_IN',
  clientUuid: '3f1c2d4e-5a6b-4c7d-8e9f-0a1b2c3d4e5f',
  deviceTime: '2026-10-07T13:58:00.000Z',
  lat: 9.9325,
  lng: -84.0796,
  accuracy: 12.5,
  photo: baselineJpegBytes().buffer as ArrayBuffer,
  photoType: 'image/jpeg',
}

afterEach(() => {
  vi.useRealTimers()
})

test('the form carries every part with the expected names and values', () => {
  const form = buildEntryForm(entry, new Date('2026-10-07T14:10:00.000Z'))
  expect(form.get('type')).toBe('CLOCK_IN')
  expect(form.get('clientUuid')).toBe(entry.clientUuid)
  expect(form.get('deviceTime')).toBe('2026-10-07T13:58:00.000Z')
  expect(form.get('deviceSentAt')).toBe('2026-10-07T14:10:00.000Z')
  expect(form.get('lat')).toBe('9.9325')
  expect(form.get('lng')).toBe('-84.0796')
  expect(form.get('accuracy')).toBe('12.5')
})

test('the photo part is a JPEG blob named selfie.jpg', () => {
  const photo = buildEntryForm(entry).get('photo') as File
  expect(photo.name).toBe('selfie.jpg')
  expect(photo.type).toBe('image/jpeg')
  expect(photo.size).toBe(baselineJpegBytes().length)
})

test('numbers use dot decimals even when the UI is Spanish and toLocaleString is stubbed', () => {
  setLanguage('es', false)
  vi.spyOn(Number.prototype, 'toLocaleString').mockReturnValue('9,9325')
  const form = buildEntryForm({ ...entry, lat: 9.9325, lng: -84.5, accuracy: 3 })
  expect(form.get('lat')).toBe('9.9325')
  expect(form.get('lng')).toBe('-84.5')
  expect(form.get('accuracy')).toBe('3')
})

test('without a fix no coordinates are sent, and without a photo no photo part', () => {
  const form = buildEntryForm({ ...entry, lat: undefined, lng: undefined, accuracy: undefined, photo: null })
  expect(form.has('lat')).toBe(false)
  expect(form.has('lng')).toBe(false)
  expect(form.has('accuracy')).toBe(false)
  expect(form.has('photo')).toBe(false)
})

test('deviceSentAt is the send time, later than deviceTime, and differs per attempt', () => {
  vi.useFakeTimers()
  vi.setSystemTime(new Date('2026-10-07T14:00:00Z'))
  const first = buildEntryForm(entry).get('deviceSentAt') as string
  vi.setSystemTime(new Date('2026-10-07T14:05:00Z'))
  const second = buildEntryForm(entry).get('deviceSentAt') as string
  expect(first).toBe('2026-10-07T14:00:00.000Z')
  expect(second).toBe('2026-10-07T14:05:00.000Z')
  expect(Date.parse(first)).toBeGreaterThanOrEqual(Date.parse(entry.deviceTime))
})

test('the request has the bearer token, Accept-Language and no manual Content-Type', async () => {
  resetSessionForTests()
  await initSession()
  await signIn(loginResponse(), { companyCode: 'DEMO', employeeCode: '1001' })
  setLanguage('en', false)
  const { mock } = stubFetch((url) => (url === '/api/time-entries' ? jsonResponse(entryResponse(), 201) : undefined))

  const result = await postTimeEntry(entry)

  expect(result.status).toBe(201)
  const [, init] = mock.mock.calls[0]
  const headers = new Headers(init!.headers)
  expect(headers.get('Authorization')).toBe('Bearer token-1')
  expect(headers.get('Accept-Language')).toBe('en')
  expect(headers.has('Content-Type')).toBe(false)
  expect(init!.method).toBe('POST')
  expect(init!.body).toBeInstanceOf(FormData)
  expect(init!.signal).toBeDefined()
})

test('a 200 replay is returned as such', async () => {
  resetSessionForTests()
  await initSession()
  await signIn(loginResponse(), { companyCode: 'DEMO', employeeCode: '1001' })
  stubFetch(() => jsonResponse(entryResponse({ replayed: true }), 200))
  const result = await postTimeEntry(entry)
  expect(result.status).toBe(200)
  expect(result.body.replayed).toBe(true)
})
