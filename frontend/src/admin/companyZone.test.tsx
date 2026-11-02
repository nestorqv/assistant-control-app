import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterAll, beforeAll, beforeEach, expect, test } from 'vitest'
import { adminBackend, trackObjectUrls } from '../test/adminBackend'
import { adminIdentity, detail } from '../test/adminFixtures'
import { jsonResponse } from '../test/fixtures'
import { renderApp, seedSession } from '../test/appHarness'
import { toLocalInput } from './format'

// The company zone (America/Costa_Rica) decides what a typed time means, never the zone of the computer.
// This file runs the computer in Tokyo (UTC+9, no DST), so code that dropped `timeZone` would read 23:01.
const original = process.env.TZ
beforeAll(() => {
  process.env.TZ = 'Asia/Tokyo'
})
afterAll(() => {
  if (original === undefined) delete process.env.TZ
  else process.env.TZ = original
})
beforeEach(() => {
  trackObjectUrls()
})

test('the test really runs in another zone than the company', () => {
  expect(new Date('2026-10-07T14:01:00Z').getHours()).toBe(23)
})

test('toLocalInput formats in the company zone, including the day change', () => {
  expect(toLocalInput('2026-10-07T14:01:00Z', 'America/Costa_Rica')).toBe('2026-10-07T08:01')
  // 03:30 UTC is still the previous day in Costa Rica (21:30), while it is 12:30 the same day in Tokyo.
  expect(toLocalInput('2026-10-07T03:30:00Z', 'America/Costa_Rica')).toBe('2026-10-06T21:30')
})

test('the correction dialog prefills and sends the time in the company zone while the computer is in Tokyo', async () => {
  const posts: { url: string; body: any }[] = []
  adminBackend({ post: (url, body) => (posts.push({ url, body: body as any }), jsonResponse(detail({ status: 'VALID' }))) })
  const user = userEvent.setup()
  await seedSession({ identity: adminIdentity })
  renderApp('/admin/review')
  await user.click(await screen.findByRole('button', { name: /^Corregir hora marca de Luis Vargas/ }))
  const dialog = await screen.findByRole('dialog')
  const time = within(dialog).getByLabelText(/Nueva hora/) as HTMLInputElement
  expect(time.value).toBe('2026-10-07T08:01')
  await user.click(within(dialog).getByRole('button', { name: /Usar la hora del teléfono \(07:55\)/ }))
  expect(time.value).toBe('2026-10-07T07:55')
  await user.click(within(dialog).getByRole('button', { name: 'Hora del teléfono incorrecta' }))
  await user.click(within(dialog).getByRole('button', { name: 'Guardar corrección' }))
  await waitFor(() => expect(posts).toHaveLength(1))
  expect(posts[0].body).toMatchObject({ expectedEffectiveTime: '2026-10-07T14:01:00Z', localTime: '2026-10-07T07:55' })
})
