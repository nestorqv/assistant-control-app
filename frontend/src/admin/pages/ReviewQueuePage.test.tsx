import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, expect, test } from 'vitest'
import { adminBackend, trackObjectUrls } from '../../test/adminBackend'
import { adminIdentity, entry, reviewItem } from '../../test/adminFixtures'
import { jsonResponse, problemResponse } from '../../test/fixtures'
import { renderApp, seedSession } from '../../test/appHarness'

beforeEach(() => {
  trackObjectUrls()
})

async function asAdmin(path = '/admin/review') {
  await seedSession({ identity: adminIdentity })
  return renderApp(path)
}

const queueCalls = (calls: { url: string }[]) => calls.filter((c) => c.url.startsWith('/api/admin/review-queue?'))
const page = (items = [reviewItem()], totalItems = items.length) => jsonResponse({ items, page: 0, size: 25, totalItems })

function rejectedItem() {
  const base = reviewItem()
  return reviewItem({ entry: { ...base.entry, status: 'REJECTED', reviewReasons: [] } })
}

test('a pending entry offers approve, reject and correct; a rejected one offers undo and correct but no reject', async () => {
  adminBackend({ queue: () => page([reviewItem(), { ...rejectedItem(), employee: { id: 3, code: '1005', name: 'Rosa Pérez', jobTitle: null } }].map((i, n) => ({ ...i, entry: { ...i.entry, id: 100 + n } }))) })
  await asAdmin()
  const cards = await screen.findAllByRole('article')
  expect(within(cards[0]).getByRole('button', { name: /^Aprobar marca de Luis Vargas/ })).toBeDefined()
  expect(within(cards[0]).getByRole('button', { name: /^Rechazar marca de Luis Vargas/ })).toBeDefined()
  expect(within(cards[0]).getByRole('button', { name: /^Corregir hora marca de Luis Vargas/ })).toBeDefined()
  expect(within(cards[1]).getByRole('button', { name: /^Deshacer rechazo marca de Rosa Pérez/ })).toBeDefined()
  expect(within(cards[1]).queryByRole('button', { name: /^Rechazar/ })).toBeNull()
  expect(within(cards[1]).getByRole('button', { name: /^Corregir hora/ })).toBeDefined()
})

test('the card shows the reason with the distance, the other entry of the day and the phone time', async () => {
  const base = reviewItem()
  adminBackend({
    queue: () =>
      page([
        reviewItem({
          pair: entry({ id: 50, type: 'CLOCK_OUT', effectiveTime: '2026-10-07T23:00:00Z' }),
          entry: { ...base.entry, reviewReasons: ['OUTSIDE_GEOFENCE', 'DEVICE_CLOCK_AHEAD'], syncedLate: false },
        }),
      ]),
  })
  await asAdmin()
  const card = await screen.findByRole('article')
  expect(within(card).getByText(/Fuera del área · .*420/)).toBeDefined()
  expect(within(card).getByText(/Hora del teléfono 07:55 · 6 min de diferencia/)).toBeDefined()
  expect(within(card).getByText(/17:00/)).toBeDefined()
})

test('the queue shows neither exact coordinates nor a map link', async () => {
  adminBackend()
  await asAdmin()
  await screen.findByRole('article')
  expect(document.body.textContent).not.toMatch(/9\.93\d*, -84\.0/)
  expect(document.querySelector('a[href*="google"]')).toBeNull()
  expect(document.querySelector('a[target="_blank"]')).toBeNull()
})

test('rejecting sends the trimmed reason and the status the admin saw', async () => {
  const posts: { url: string; body: unknown }[] = []
  adminBackend({ post: (url, body) => (posts.push({ url, body }), jsonResponse(reviewItem().entry)) })
  const user = userEvent.setup()
  await asAdmin()
  await user.click(await screen.findByRole('button', { name: /^Rechazar marca de Luis Vargas/ }))
  const dialog = await screen.findByRole('dialog')
  await user.type(within(dialog).getByRole('textbox', { name: /Motivo/ }), '   Marca   hecha\npor error  ')
  await user.click(within(dialog).getByRole('button', { name: 'Rechazar marca' }))
  await waitFor(() => expect(posts).toHaveLength(1))
  expect(posts[0]).toEqual({ url: '/api/admin/time-entries/12/reject', body: { reason: 'Marca hecha por error', expectedStatus: 'PENDING_REVIEW' } })
})

test('a reason of 5 characters is accepted and the counter and the limit follow the text', async () => {
  const posts: unknown[] = []
  adminBackend({ post: (_u, body) => (posts.push(body), jsonResponse(reviewItem().entry)) })
  const user = userEvent.setup()
  await asAdmin()
  await user.click(await screen.findByRole('button', { name: /^Aprobar marca de Luis Vargas/ }))
  const dialog = await screen.findByRole('dialog')
  const box = within(dialog).getByRole('textbox', { name: /Motivo/ }) as HTMLTextAreaElement
  expect(box.maxLength).toBe(500)
  await user.type(box, 'cinco')
  expect(dialog.textContent).toMatch(/5 \/ 500/)
  await user.click(within(dialog).getByRole('button', { name: 'Aprobar marca' }))
  await waitFor(() => expect(posts).toHaveLength(1))
})

test('whitespace alone is not a reason', async () => {
  const posts: unknown[] = []
  adminBackend({ post: (_u, body) => (posts.push(body), jsonResponse(reviewItem().entry)) })
  const user = userEvent.setup()
  await asAdmin()
  await user.click(await screen.findByRole('button', { name: /^Aprobar marca de Luis Vargas/ }))
  const dialog = await screen.findByRole('dialog')
  await user.type(within(dialog).getByRole('textbox', { name: /Motivo/ }), '          ')
  await user.click(within(dialog).getByRole('button', { name: 'Aprobar marca' }))
  expect(within(dialog).getByRole('alert').textContent).toMatch(/al menos 5 caracteres/)
  expect(posts).toHaveLength(0)
})

test('correcting to the same time is refused before any request, and an empty time asks for the time', async () => {
  const posts: unknown[] = []
  adminBackend({ post: (_u, body) => (posts.push(body), jsonResponse(reviewItem().entry)) })
  const user = userEvent.setup()
  await asAdmin()
  await user.click(await screen.findByRole('button', { name: /^Corregir hora marca de Luis Vargas/ }))
  const dialog = await screen.findByRole('dialog')
  await user.click(within(dialog).getByRole('button', { name: 'Hora del teléfono incorrecta' }))
  await user.click(within(dialog).getByRole('button', { name: 'Guardar corrección' }))
  expect(within(dialog).getByText('La nueva hora es igual a la actual.')).toBeDefined()

  const time = within(dialog).getByLabelText(/Nueva hora/) as HTMLInputElement
  fireEvent.change(time, { target: { value: '' } })
  await user.click(within(dialog).getByRole('button', { name: 'Guardar corrección' }))
  expect(within(dialog).getByText('Indique la nueva hora.')).toBeDefined()
  expect(posts).toHaveLength(0)
})

test('INVALID_ENTRY_TIME from the server is shown on the time field and the dialog stays open', async () => {
  adminBackend({ post: () => problemResponse(400, 'INVALID_ENTRY_TIME') })
  const user = userEvent.setup()
  await asAdmin()
  await user.click(await screen.findByRole('button', { name: /^Corregir hora marca de Luis Vargas/ }))
  const dialog = await screen.findByRole('dialog')
  fireEvent.change(within(dialog).getByLabelText(/Nueva hora/), { target: { value: '2026-10-07T08:30' } })
  await user.click(within(dialog).getByRole('button', { name: 'Hora del teléfono incorrecta' }))
  await user.click(within(dialog).getByRole('button', { name: 'Guardar corrección' }))
  expect(await within(dialog).findByText(/La hora está en el futuro/)).toBeDefined()
  expect(screen.getByRole('dialog')).toBeDefined()
})

test('a stale entry (409 ENTRY_CHANGED) closes the dialog, reloads the list, keeps an error box and moves the focus to the card', async () => {
  const { calls } = adminBackend({ post: () => problemResponse(409, 'ENTRY_CHANGED') })
  const user = userEvent.setup()
  await asAdmin()
  await user.click(await screen.findByRole('button', { name: /^Corregir hora marca de Luis Vargas/ }))
  const dialog = await screen.findByRole('dialog')
  fireEvent.change(within(dialog).getByLabelText(/Nueva hora/), { target: { value: '2026-10-07T08:30' } })
  await user.click(within(dialog).getByRole('button', { name: 'Hora del teléfono incorrecta' }))
  const before = queueCalls(calls).length
  await user.click(within(dialog).getByRole('button', { name: 'Guardar corrección' }))
  await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
  await waitFor(() => expect(queueCalls(calls).length).toBe(before + 1))
  expect(screen.getByText(/Otra persona modificó esta marca/)).toBeDefined()
  await waitFor(() => expect(document.activeElement).toBe(screen.getByRole('heading', { level: 3, name: 'Luis Vargas' })))
})

test('after an approval the next step is announced and the focus moves to a card heading', async () => {
  adminBackend({ post: () => jsonResponse(reviewItem().entry) })
  const user = userEvent.setup()
  await asAdmin()
  await user.click(await screen.findByRole('button', { name: /^Aprobar marca de Luis Vargas/ }))
  const dialog = await screen.findByRole('dialog')
  await user.click(within(dialog).getByRole('button', { name: 'Verificado con la foto' }))
  await user.click(within(dialog).getByRole('button', { name: 'Aprobar marca' }))
  await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
  await waitFor(() => expect(Array.from(document.querySelectorAll('[aria-live]')).map((n) => n.textContent).join('|')).toMatch(/Marca aprobada\. Quedan 0 por revisar\./))
  await waitFor(() => expect(document.activeElement?.tagName).toBe('H3'))
})

test('a server failure on an action stays in the dialog with the message and lets the admin retry', async () => {
  let fail = true
  const posts: unknown[] = []
  adminBackend({ post: (_u, body) => (posts.push(body), fail ? problemResponse(500, 'INTERNAL_ERROR') : jsonResponse(reviewItem().entry)) })
  const user = userEvent.setup()
  await asAdmin()
  await user.click(await screen.findByRole('button', { name: /^Aprobar marca de Luis Vargas/ }))
  const dialog = await screen.findByRole('dialog')
  await user.click(within(dialog).getByRole('button', { name: 'Verificado con la foto' }))
  await user.click(within(dialog).getByRole('button', { name: 'Aprobar marca' }))
  expect((await within(dialog).findAllByRole('alert'))[0].textContent).toMatch(/error inesperado/)
  fail = false
  await user.click(within(dialog).getByRole('button', { name: 'Aprobar marca' }))
  await waitFor(() => expect(posts).toHaveLength(2))
  await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
})

test('the own-entry warning is shown to the admin who made the entry', async () => {
  adminBackend({ queue: () => page([reviewItem({ ownEntry: true })]) })
  const user = userEvent.setup()
  await asAdmin()
  const card = await screen.findByRole('article')
  expect(within(card).getByText('Es su propia marca')).toBeDefined()
  await user.click(within(card).getByRole('button', { name: /^Aprobar marca/ }))
  const dialog = await screen.findByRole('dialog')
  expect(dialog.querySelector('.notice--warn')).not.toBeNull()
})

test('Escape cancels an action dialog without sending anything and focus goes back to the button', async () => {
  const posts: unknown[] = []
  adminBackend({ post: (_u, body) => (posts.push(body), undefined) })
  const user = userEvent.setup()
  await asAdmin()
  const opener = await screen.findByRole('button', { name: /^Rechazar marca de Luis Vargas/ })
  await user.click(opener)
  const dialog = await screen.findByRole('dialog')
  fireEvent(dialog, new Event('cancel', { cancelable: true }))
  await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
  expect(posts).toHaveLength(0)
  expect(document.activeElement).toBe(opener)
})

test('a lone clock-in offers to record the clock-out; a card with its pair does not', async () => {
  const posts: { url: string; body: any }[] = []
  adminBackend({
    queue: () => page([reviewItem(), reviewItem({ pair: entry({ id: 60, type: 'CLOCK_OUT' }), entry: { ...reviewItem().entry, id: 61 } })]),
    post: (url, body) => (posts.push({ url, body: body as any }), jsonResponse(reviewItem().entry, 201)),
  })
  const user = userEvent.setup()
  await asAdmin()
  const cards = await screen.findAllByRole('article')
  expect(within(cards[1]).queryByRole('button', { name: 'Registrar salida' })).toBeNull()
  await user.click(within(cards[0]).getByRole('button', { name: 'Registrar salida' }))
  const dialog = await screen.findByRole('dialog')
  expect(within(dialog).getByText(/Entrada registrada: 08:01/)).toBeDefined()
  await user.click(within(dialog).getByRole('button', { name: 'Olvidó marcar la salida' }))
  await user.click(within(dialog).getByRole('button', { name: 'Registrar salida' }))
  await waitFor(() => expect(posts).toHaveLength(1))
  expect(posts[0].url).toBe('/api/admin/time-entries')
  expect(posts[0].body).toMatchObject({ employeeId: 2, type: 'CLOCK_OUT', workDate: '2026-10-07', localTime: '2026-10-07T17:00' })
})

test('paging asks the server for the next page and shows where the admin is', async () => {
  const { calls } = adminBackend({ queue: () => page([reviewItem()], 60) })
  const user = userEvent.setup()
  await asAdmin()
  expect(await screen.findByText('Página 1 de 3')).toBeDefined()
  await user.click(screen.getByRole('button', { name: 'Siguiente' }))
  await waitFor(() => expect(queueCalls(calls).some((c) => /page=1(&|$)/.test(c.url))).toBe(true))
  expect(await screen.findByText('Página 2 de 3')).toBeDefined()
})

test('the status filter shows rejected entries and clearing the filters goes back to the pending ones', async () => {
  const { calls } = adminBackend()
  const user = userEvent.setup()
  await asAdmin()
  await screen.findByRole('article')
  await user.selectOptions(screen.getByLabelText('Estado'), 'REJECTED')
  await waitFor(() => expect(queueCalls(calls).some((c) => c.url.includes('status=REJECTED'))).toBe(true))
  const before = queueCalls(calls).length
  await user.click(screen.getByRole('button', { name: 'Limpiar filtros' }))
  await waitFor(() => expect(queueCalls(calls).length).toBeGreaterThan(before))
  expect(queueCalls(calls).at(-1)?.url).not.toContain('status=REJECTED')
})

test('with filters on, an empty answer says no entry matches (not that everything is reviewed)', async () => {
  adminBackend({ queue: () => page([], 0) })
  await asAdmin('/admin/review?status=REJECTED')
  expect(await screen.findByText('Ninguna marca coincide con los filtros.')).toBeDefined()
  expect(screen.queryByText('No hay marcas por revisar.')).toBeNull()
})

test('a queue load failure shows the error with a retry that reloads', async () => {
  let fail = true
  const { calls } = adminBackend({ queue: () => (fail ? problemResponse(500, 'INTERNAL_ERROR') : page()) })
  const user = userEvent.setup()
  await asAdmin()
  await screen.findByRole('button', { name: 'Reintentar' })
  fail = false
  const before = queueCalls(calls).length
  await user.click(screen.getByRole('button', { name: 'Reintentar' }))
  await waitFor(() => expect(queueCalls(calls).length).toBe(before + 1))
  expect(await screen.findByRole('article')).toBeDefined()
})

test('a 403 from the server says the session ended instead of offering the PIN', async () => {
  adminBackend({ queue: () => problemResponse(403, 'FORBIDDEN') })
  await asAdmin()
  expect(await screen.findByText(/sesión|session/i, { selector: '[role="alert"], [role="alert"] *' })).toBeDefined()
})

test('opening the evidence from a card and closing it returns the focus to that button', async () => {
  adminBackend()
  const user = userEvent.setup()
  await asAdmin()
  const opener = await screen.findByRole('button', { name: /^Evidencia marca de Luis Vargas/ })
  await user.click(opener)
  const dialog = await screen.findByRole('dialog')
  await within(dialog).findByRole('img')
  await user.click(within(dialog).getByRole('button', { name: 'Cerrar' }))
  await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
  expect(document.activeElement).toBe(opener)
})
