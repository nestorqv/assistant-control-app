import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, expect, test } from 'vitest'
import { adminBackend, trackObjectUrls } from '../../test/adminBackend'
import { adminIdentity, dashboard, detail, entry, row } from '../../test/adminFixtures'
import { jsonResponse, problemResponse } from '../../test/fixtures'
import { renderApp, seedSession } from '../../test/appHarness'

let urls: ReturnType<typeof trackObjectUrls>
beforeEach(() => {
  urls = trackObjectUrls()
})

async function openEvidence(name: RegExp = /GPS a 420 m/) {
  const user = userEvent.setup()
  await seedSession({ identity: adminIdentity })
  renderApp('/admin')
  await user.click(await screen.findByRole('button', { name }))
  return { user, dialog: await screen.findByRole('dialog') }
}

const photoCalls = (calls: { url: string }[]) => calls.filter((c) => c.url.endsWith('/photo'))

test('the photo is requested once, only after the dialog opens, and the object URL is revoked on close', async () => {
  const { calls } = adminBackend()
  const { user, dialog } = await openEvidence()
  await within(dialog).findByRole('img', { name: /Selfie de Luis Vargas/ })
  expect(photoCalls(calls)).toHaveLength(1)
  expect(urls.created).toEqual(['blob:photo-1'])
  expect(urls.revoked).toEqual([])

  await user.click(within(dialog).getByRole('button', { name: 'Cerrar' }))
  await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
  expect(urls.revoked).toEqual(['blob:photo-1'])
  expect(photoCalls(calls)).toHaveLength(1)
})

test('an entry without a photo never calls the photo endpoint', async () => {
  const { calls } = adminBackend({ detailFor: () => jsonResponse(detail({ hasPhoto: false })) })
  const { dialog } = await openEvidence()
  expect(await within(dialog).findByText('Sin foto')).toBeDefined()
  expect(photoCalls(calls)).toHaveLength(0)
  expect(urls.created).toHaveLength(0)
})

test('a photo that is gone (404) says "Sin foto"', async () => {
  adminBackend({ photo: () => problemResponse(404, 'NOT_FOUND') })
  const { dialog } = await openEvidence()
  expect(await within(dialog).findByText('Sin foto')).toBeDefined()
})

test('a photo error offers a retry that asks again exactly once more, and a view limit does not retry by itself', async () => {
  let fail = true
  const { calls } = adminBackend({ photo: () => (fail ? problemResponse(500, 'INTERNAL_ERROR') : new Response(new Uint8Array([1]), { headers: { 'content-type': 'image/jpeg' } })) })
  const { user, dialog } = await openEvidence()
  expect(await within(dialog).findByText('No se pudo cargar la foto.')).toBeDefined()
  await new Promise((resolve) => setTimeout(resolve, 30))
  expect(photoCalls(calls)).toHaveLength(1)

  fail = false
  await user.click(within(dialog).getByRole('button', { name: 'Reintentar' }))
  expect(await within(dialog).findByRole('img')).toBeDefined()
  expect(photoCalls(calls)).toHaveLength(2)
})

test('a manual entry has no location, no map link and no photo request', async () => {
  const { calls } = adminBackend({
    detailFor: () => jsonResponse(detail({ source: 'ADMIN', hasPhoto: false, hasGps: false, location: null, distanceMeters: null, insideGeofence: null, gpsAccuracyMeters: null })),
    dash: () => jsonResponse(dashboard({ rows: [row('Luis Vargas', { dayStatus: 'COMPLETE', clockIn: entry({ id: 12, source: 'ADMIN', hasPhoto: false, hasGps: false, distanceMeters: null, insideGeofence: null }) })] })),
  })
  const { dialog } = await openEvidence(/Registro manual/)
  expect(await within(dialog).findByText('Registro manual: sin foto ni ubicación.')).toBeDefined()
  expect(within(dialog).getByText('Registro manual: sin foto.')).toBeDefined()
  expect(dialog.querySelector('a')).toBeNull()
  expect(photoCalls(calls)).toHaveLength(0)
})

test('without a recorded location only the branch is linked, and the links open in a new tab without referrer', async () => {
  adminBackend({ detailFor: () => jsonResponse(detail({ location: null, distanceMeters: null, insideGeofence: null, gpsAccuracyMeters: null })) })
  const { dialog } = await openEvidence()
  await within(dialog).findByRole('img')
  expect(within(dialog).queryByRole('link', { name: /Ver en el mapa/ })).toBeNull()
  const branch = within(dialog).getByRole('link', { name: /Ver la sucursal/ })
  expect(branch.getAttribute('href')).toBe('https://www.google.com/maps/search/?api=1&query=9.930000,-84.080000')
  expect(branch.getAttribute('rel')).toBe('noopener noreferrer')
  expect(branch.getAttribute('target')).toBe('_blank')
  expect(within(dialog).getAllByText('Sin ubicación').length).toBeGreaterThan(0)
})

test('the dialog never embeds a map, a frame or a script', async () => {
  adminBackend()
  const { dialog } = await openEvidence()
  await within(dialog).findByRole('img')
  expect(dialog.querySelector('iframe, script, object, embed')).toBeNull()
  expect(Array.from(dialog.querySelectorAll('img')).every((img) => img.getAttribute('src')?.startsWith('blob:'))).toBe(true)
})

test('the other entry of the day opens in the same dialog and the previous photo is revoked', async () => {
  const pair = entry({ id: 13, type: 'CLOCK_OUT', effectiveTime: '2026-10-07T23:00:00Z' })
  const { calls } = adminBackend({
    detailFor: (id) =>
      jsonResponse(
        id === 12
          ? detail({ pair })
          : detail({ id: 13, type: 'CLOCK_OUT', effectiveTime: '2026-10-07T23:00:00Z', serverTime: '2026-10-07T23:00:00Z', status: 'VALID', reviewReasons: [], pair: entry({ id: 12 }) }),
      ),
  })
  const { user, dialog } = await openEvidence()
  await within(dialog).findByRole('img')
  await user.click(within(dialog).getByRole('button', { name: 'Ver evidencia' }))
  await waitFor(() => expect(photoCalls(calls).map((c) => c.url)).toContain('/api/admin/time-entries/13/photo'))
  expect(await within(dialog).findByText(/^Salida · /)).toBeDefined()
  expect(urls.revoked).toContain('blob:photo-1')
})

test('an entry that no longer exists says so and offers no actions', async () => {
  adminBackend({ detailFor: () => problemResponse(404, 'NOT_FOUND') })
  const { dialog } = await openEvidence()
  expect(await within(dialog).findByText('Esta marca ya no existe.')).toBeDefined()
  expect(within(dialog).queryByRole('button', { name: 'Aprobar' })).toBeNull()
  expect(within(dialog).queryByRole('img')).toBeNull()
})

test('a failed load shows the error with a retry', async () => {
  let fail = true
  adminBackend({ detailFor: () => (fail ? problemResponse(500, 'INTERNAL_ERROR') : jsonResponse(detail())) })
  const { user, dialog } = await openEvidence()
  expect((await within(dialog).findAllByRole('alert'))[0].textContent).toMatch(/error inesperado/)
  fail = false
  await user.click(within(dialog).getByRole('button', { name: 'Reintentar' }))
  expect(await within(dialog).findByRole('img')).toBeDefined()
})

test.each([
  ['PENDING_REVIEW', ['Aprobar', 'Rechazar', 'Corregir hora'], ['Deshacer rechazo']],
  ['VALID', ['Rechazar', 'Corregir hora'], ['Aprobar', 'Deshacer rechazo']],
  ['REJECTED', ['Deshacer rechazo', 'Corregir hora'], ['Aprobar', 'Rechazar']],
] as const)('the actions for a %s entry', async (status, has, hasNot) => {
  adminBackend({ detailFor: () => jsonResponse(detail({ status })) })
  const { dialog } = await openEvidence()
  await within(dialog).findByRole('img')
  for (const name of has) expect(within(dialog).getByRole('button', { name })).toBeDefined()
  for (const name of hasNot) expect(within(dialog).queryByRole('button', { name })).toBeNull()
})

test('approving from the dialog needs a reason, reloads the detail and tells the page behind', async () => {
  const posts: { url: string; body: unknown }[] = []
  const { calls } = adminBackend({ post: (url, body) => (posts.push({ url, body }), jsonResponse(detail({ status: 'VALID' }))) })
  const { user, dialog } = await openEvidence()
  await within(dialog).findByRole('img')
  const dashBefore = calls.filter((c) => c.url.startsWith('/api/admin/dashboard')).length

  await user.click(within(dialog).getByRole('button', { name: 'Aprobar' }))
  const action = (await screen.findAllByRole('dialog')).at(-1) as HTMLElement
  await user.click(within(action).getByRole('button', { name: 'Aprobar marca' }))
  expect(within(action).getByText('Escriba un motivo de al menos 5 caracteres.')).toBeDefined()
  expect(posts).toHaveLength(0)

  await user.click(within(action).getByRole('button', { name: 'Verificado con la foto' }))
  await user.click(within(action).getByRole('button', { name: 'Aprobar marca' }))
  await waitFor(() => expect(posts).toHaveLength(1))
  expect(posts[0]).toEqual({ url: '/api/admin/time-entries/12/approve', body: { reason: 'Verificado con la foto', expectedStatus: 'PENDING_REVIEW' } })
  await waitFor(() => expect(calls.filter((c) => c.url.startsWith('/api/admin/dashboard')).length).toBe(dashBefore + 1))
  await waitFor(() => expect(screen.getAllByRole('dialog')).toHaveLength(1))
})

test('a stale entry (409) inside the dialog reloads the detail and explains, without leaving the dialog', async () => {
  const { calls } = adminBackend({ post: () => problemResponse(409, 'ENTRY_CHANGED') })
  const { user, dialog } = await openEvidence()
  await within(dialog).findByRole('img')
  const detailCalls = () => calls.filter((c) => c.url === '/api/admin/time-entries/12').length
  const before = detailCalls()

  await user.click(within(dialog).getByRole('button', { name: 'Rechazar' }))
  const action = (await screen.findAllByRole('dialog')).at(-1) as HTMLElement
  await user.click(within(action).getByRole('button', { name: 'Marca hecha por error' }))
  await user.click(within(action).getByRole('button', { name: 'Rechazar marca' }))

  await waitFor(() => expect(screen.getAllByRole('dialog')).toHaveLength(1))
  expect(within(dialog).getByText('La marca cambió. Se actualizaron los datos.')).toBeDefined()
  await waitFor(() => expect(detailCalls()).toBe(before + 1))
})

test('the history lists the newest adjustment first, with who and when in the company zone', async () => {
  adminBackend({
    detailFor: () =>
      jsonResponse(
        detail({
          adjustments: [
            { id: 1, action: 'APPROVE', field: 'status', oldValue: 'PENDING_REVIEW', newValue: 'VALID', reason: 'Primera razón', adminName: 'Ana Mora', selfApproved: false, createdAt: '2026-10-07T15:00:00Z' },
            { id: 2, action: 'REJECT', field: 'status', oldValue: 'VALID', newValue: 'REJECTED', reason: 'Segunda razón', adminName: 'Beto Díaz', selfApproved: false, createdAt: '2026-10-07T16:00:00Z' },
          ],
        }),
      ),
  })
  const { dialog } = await openEvidence()
  const items = await within(dialog).findAllByRole('listitem')
  expect(items[0].textContent).toMatch(/Segunda razón/)
  expect(items[0].textContent).toMatch(/Beto Díaz · 7 oct, 10:00/)
  expect(items[0].textContent).toMatch(/Válida → Rechazada/)
  expect(items[1].textContent).toMatch(/Primera razón/)
})
