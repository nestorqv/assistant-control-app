import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, expect, test, vi } from 'vitest'
import { jsonResponse, problemResponse } from '../test/fixtures'
import { adminIdentity, dashboard, detail, reviewItem } from '../test/adminFixtures'
import { renderApp, seedSession, stubFetch } from '../test/appHarness'

const branches = [{ id: 1, name: 'Central', latitude: 9.93, longitude: -84.08, radiusMeters: 100, active: true, activeEmployees: 4 }]

type Routes = {
  dash?: () => Response
  detailFor?: () => Response
  queue?: () => Response
  post?: (url: string, body: unknown) => Response | undefined
}

function backend(routes: Routes = {}) {
  return stubFetch(
    (url) => (url.startsWith('/api/admin/dashboard') ? (routes.dash?.() ?? jsonResponse(dashboard())) : undefined),
    (url) => (url === '/api/admin/branches' ? jsonResponse(branches) : undefined),
    (url) => (url === '/api/admin/review-queue/count' ? jsonResponse({ pending: 3, today: '2026-10-07', timeZone: 'America/Costa_Rica' }) : undefined),
    (url) =>
      url.startsWith('/api/admin/review-queue?')
        ? (routes.queue?.() ?? jsonResponse({ items: [reviewItem()], page: 0, size: 25, totalItems: 1 }))
        : undefined,
    (url) => (url === '/api/admin/employees' ? jsonResponse([{ id: 2, code: '1004', name: 'Luis Vargas', active: true }]) : undefined),
    (url) => (/\/api\/admin\/time-entries\/\d+$/.test(url) ? (routes.detailFor?.() ?? jsonResponse(detail())) : undefined),
    (url) => (/\/photo$/.test(url) ? new Response(new Uint8Array([1, 2, 3]), { headers: { 'content-type': 'image/jpeg' } }) : undefined),
    (url, init) => {
      if (init?.method !== 'POST') return undefined
      return routes.post?.(url, init.body ? JSON.parse(String(init.body)) : null)
    },
    (url) => (url === '/api/health' ? jsonResponse({ status: 'UP', service: 'x', serverTime: '2026-10-13T18:00:00Z' }) : undefined),
  )
}

const created: string[] = []
beforeEach(() => {
  created.length = 0
  vi.stubGlobal('URL', Object.assign(URL, {
    createObjectURL: vi.fn(() => {
      created.push('blob:photo')
      return 'blob:photo'
    }),
    revokeObjectURL: vi.fn(),
  }))
})
afterEach(() => vi.unstubAllGlobals())

async function asAdmin(path: string, lang: 'es' | 'en' = 'es') {
  await seedSession({ identity: adminIdentity })
  return renderApp(path, lang)
}

test('an employee never sees the panel and an admin without a token goes to the login with next', async () => {
  backend()
  await seedSession({ identity: adminIdentity, token: false })
  renderApp('/admin/review')
  expect(await screen.findByRole('heading', { name: 'Iniciar sesión' })).toBeDefined()
})

test('the dashboard shows the four KPIs, the chips, the hours and the accessible table', async () => {
  backend()
  await asAdmin('/admin')

  expect(await screen.findByRole('heading', { level: 1, name: 'Asistencia de hoy' })).toBeDefined()
  const cards = await screen.findAllByRole('listitem')
  const text = cards.map((c) => c.textContent ?? '').join('|')
  expect(text).toMatch(/Presentes.*9.*de 12/)
  expect(text).toMatch(/Llegadas tardías.*2/)
  expect(text).toMatch(/Sin marcar.*3.*\+1 aún no inician/)
  expect(text).toMatch(/Por revisar.*1.*1 fuera del área/)

  const table = await screen.findByRole('table')
  expect(within(table).getAllByRole('columnheader')).toHaveLength(6)
  expect(within(table).getByRole('rowheader', { name: /María Rojas/ })).toBeDefined()
  expect(within(table).getByText('Fuera del área')).toBeDefined()
  expect(within(table).getByText('Completo')).toBeDefined()
  expect(within(table).getByText('Sin marcar')).toBeDefined()
  expect(within(table).getAllByText('en turno')).toHaveLength(2)
  expect(within(table).getByText('8 h 04 min')).toBeDefined()
  expect(within(table).getByText('07:58')).toBeDefined()
  expect(table.querySelector('caption')?.textContent).toMatch(/Asistencia del Miércoles, 7 de octubre, Todas las sucursales, 4 empleados/)
  expect(screen.getByRole('button', { name: 'GPS a 420 m, ver evidencia de Luis Vargas, entrada 08:01' })).toBeDefined()
  expect(screen.getByRole('button', { name: 'Foto + GPS, ver evidencia de María Rojas, entrada 07:58' })).toBeDefined()
})

test('the panel has no export button or reports link before the export exists', async () => {
  backend()
  await asAdmin('/admin')
  await screen.findByRole('table')
  expect(screen.queryByText(/Exportar/)).toBeNull()
  expect(screen.queryByRole('link', { name: /Reportes/ })).toBeNull()
  expect(await screen.findByRole('link', { name: 'Revisión, 3 pendientes' })).toBeDefined()
  expect(screen.getByRole('link', { name: 'Saltar al contenido' })).toBeDefined()
  expect(screen.getByRole('link', { name: 'Mi marca' }).getAttribute('href')).toBe('/')
})

test('changing the date asks the server for that date; the default is the server day', async () => {
  const { calls } = backend()
  const user = userEvent.setup()
  await asAdmin('/admin')
  await screen.findByRole('table')
  expect(calls.find((c) => c.url.startsWith('/api/admin/dashboard'))?.url).toBe('/api/admin/dashboard')

  await user.click(screen.getByRole('button', { name: 'Día anterior' }))
  await waitFor(() => expect(calls.some((c) => c.url === '/api/admin/dashboard?date=2026-10-06')).toBe(true))
})

test('the auto refresh can be switched off and refreshes on demand', async () => {
  const { calls } = backend()
  const user = userEvent.setup()
  await asAdmin('/admin')
  await screen.findByRole('table')
  const box = screen.getByRole('checkbox', { name: 'Actualizar automáticamente' }) as HTMLInputElement
  expect(box.checked).toBe(true)
  await user.click(box)
  expect(box.checked).toBe(false)
  const before = calls.filter((c) => c.url.startsWith('/api/admin/dashboard')).length
  await user.click(screen.getByRole('button', { name: 'Actualizar' }))
  await waitFor(() => expect(calls.filter((c) => c.url.startsWith('/api/admin/dashboard')).length).toBe(before + 1))
})

test('the dashboard error keeps a retry and an empty company shows the empty message', async () => {
  backend({ dash: () => problemResponse(500, 'INTERNAL_ERROR') })
  await asAdmin('/admin')
  expect((await screen.findAllByRole('alert'))[0].textContent).toMatch(/error inesperado/)
  expect(screen.getByRole('button', { name: 'Reintentar' })).toBeDefined()
})

test('everything is available in English', async () => {
  backend()
  await asAdmin('/admin', 'en')
  expect(await screen.findByRole('heading', { level: 1, name: "Today's attendance" })).toBeDefined()
  expect(screen.getByText('Late arrivals')).toBeDefined()
  expect(await screen.findByRole('link', { name: 'Review, 3 pending' })).toBeDefined()
})

test('the evidence dialog loads the photo only when opened, shows the facts and revokes the photo on close', async () => {
  const { calls } = backend()
  const user = userEvent.setup()
  await asAdmin('/admin')
  const button = await screen.findByRole('button', { name: /GPS a 420 m/ })
  expect(calls.some((c) => c.url.includes('/photo'))).toBe(false)

  await user.click(button)
  const dialog = await screen.findByRole('dialog')
  expect(await within(dialog).findByRole('img', { name: 'Selfie de Luis Vargas, entrada 08:01' })).toBeDefined()
  expect(calls.some((c) => c.url === '/api/admin/time-entries/12/photo')).toBe(true)
  expect(within(dialog).getByText('9.932500, -84.079600')).toBeDefined()
  expect(within(dialog).getByText(/420 m de Central \(radio actual 100 m\)/)).toBeDefined()
  expect(within(dialog).getByText('±12 m')).toBeDefined()
  expect(within(dialog).getByText('Cada consulta de foto queda registrada.')).toBeDefined()
  const map = within(dialog).getByRole('link', { name: /Ver en el mapa/ })
  expect(map.getAttribute('href')).toBe('https://www.google.com/maps/search/?api=1&query=9.932500,-84.079600')
  expect(map.getAttribute('rel')).toMatch(/noopener/)
  expect(map.getAttribute('rel')).toMatch(/noreferrer/)
  expect(map.getAttribute('target')).toBe('_blank')
  expect(within(dialog).getByText(/Se abrirá Google Maps/)).toBeDefined()
  expect(dialog.querySelector('iframe, script')).toBeNull()

  await user.click(within(dialog).getByRole('button', { name: 'Cerrar' }))
  await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
  expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:photo')
})

test('a missing photo shows "Sin foto" and the adjustment history is formatted in the company zone', async () => {
  backend({
    detailFor: () =>
      jsonResponse(
        detail({
          hasPhoto: false,
          adjustments: [
            { id: 1, action: 'CORRECT_TIME', field: 'effective_time', oldValue: '2026-10-07T08:17-06:00', newValue: '2026-10-07T08:03-06:00', reason: 'Hora del teléfono incorrecta', adminName: 'Ana Mora', selfApproved: true, createdAt: '2026-10-07T16:14:00Z' },
          ],
        }),
      ),
  })
  const user = userEvent.setup()
  await asAdmin('/admin')
  await user.click(await screen.findByRole('button', { name: /GPS a 420 m/ }))
  const dialog = await screen.findByRole('dialog')
  expect(await within(dialog).findByText('Sin foto')).toBeDefined()
  expect(within(dialog).getByText(/por Ana Mora · 7 oct, 10:14/)).toBeDefined()
  expect(within(dialog).getByText('Autoaprobado')).toBeDefined()
  expect(within(dialog).getByText(/Hora vigente: 7 oct, 08:17 → 7 oct, 08:03/)).toBeDefined()
})

test('an unknown history value is a dash for the eyes and "sin valor" for screen readers', async () => {
  backend({
    detailFor: () =>
      jsonResponse(
        detail({
          adjustments: [
            { id: 1, action: 'CORRECT_TIME', field: 'late_minutes', oldValue: null, newValue: '', reason: 'Hora del teléfono incorrecta', adminName: 'Ana Mora', selfApproved: false, createdAt: '2026-10-07T16:14:00Z' },
            { id: 2, action: 'CORRECT_TIME', field: 'late_minutes', oldValue: 'null', newValue: '3', reason: 'Hora del teléfono incorrecta', adminName: 'Ana Mora', selfApproved: false, createdAt: '2026-10-07T16:15:00Z' },
          ],
        }),
      ),
  })
  const user = userEvent.setup()
  await asAdmin('/admin')
  await user.click(await screen.findByRole('button', { name: /GPS a 420 m/ }))
  const dialog = await screen.findByRole('dialog')
  await within(dialog).findAllByText('sin valor')
  expect(within(dialog).getAllByText('sin valor')).toHaveLength(3)
  for (const dash of dialog.querySelectorAll('[aria-hidden="true"]')) {
    if (dash.textContent === '—') expect(dash.nextElementSibling?.textContent).toBe('sin valor')
  }
  expect(within(dialog).queryByText('null')).toBeNull()
})

test('a photo limit (429) is explained and offers a retry', async () => {
  stubFetch(
    (url) => (url.startsWith('/api/admin/dashboard') ? jsonResponse(dashboard()) : undefined),
    (url) => (url === '/api/admin/branches' ? jsonResponse(branches) : undefined),
    (url) => (url === '/api/admin/review-queue/count' ? jsonResponse({ pending: 0, today: '2026-10-07', timeZone: 'America/Costa_Rica' }) : undefined),
    (url) => (/\/photo$/.test(url) ? problemResponse(429, 'TOO_MANY_ATTEMPTS', { 'Retry-After': '600' }) : undefined),
    (url) => (/time-entries\/\d+$/.test(url) ? jsonResponse(detail()) : undefined),
  )
  const user = userEvent.setup()
  await asAdmin('/admin')
  await user.click(await screen.findByRole('button', { name: /GPS a 420 m/ }))
  expect(await screen.findByText(/Se consultaron demasiadas fotos. Inténtelo de nuevo en 10 min./)).toBeDefined()
})

test('approving from the queue needs a reason and sends the reason and the expected status', async () => {
  const posts: { url: string; body: unknown }[] = []
  backend({
    post: (url, body) => {
      posts.push({ url, body })
      return jsonResponse(detail({ status: 'VALID' }))
    },
  })
  const user = userEvent.setup()
  await asAdmin('/admin/review')
  expect(await screen.findByRole('heading', { level: 3, name: 'Luis Vargas' })).toBeDefined()

  await user.click(screen.getByRole('button', { name: /^Aprobar marca de Luis Vargas, entrada 08:01/ }))
  const dialog = await screen.findByRole('dialog')
  await user.click(within(dialog).getByRole('button', { name: 'Aprobar marca' }))
  expect(within(dialog).getByText('Escriba un motivo de al menos 5 caracteres.')).toBeDefined()
  expect(posts).toHaveLength(0)

  await user.click(within(dialog).getByRole('button', { name: 'Verificado con la foto' }))
  await user.click(within(dialog).getByRole('button', { name: 'Aprobar marca' }))
  await waitFor(() => expect(posts).toHaveLength(1))
  expect(posts[0]).toEqual({ url: '/api/admin/time-entries/12/approve', body: { reason: 'Verificado con la foto', expectedStatus: 'PENDING_REVIEW' } })
  await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
})

test('rejecting needs at least five characters', async () => {
  const posts: unknown[] = []
  backend({ post: (_url, body) => (posts.push(body), jsonResponse(detail({ status: 'REJECTED' }))) })
  const user = userEvent.setup()
  await asAdmin('/admin/review')
  await user.click(await screen.findByRole('button', { name: /^Rechazar marca de Luis Vargas/ }))
  const dialog = await screen.findByRole('dialog')
  await user.type(within(dialog).getByRole('textbox', { name: /Motivo/ }), 'abcd')
  await user.click(within(dialog).getByRole('button', { name: 'Rechazar marca' }))
  expect(within(dialog).getByRole('alert').textContent).toMatch(/al menos 5 caracteres/)
  expect(posts).toHaveLength(0)
  await user.type(within(dialog).getByRole('textbox', { name: /Motivo/ }), 'e')
  await user.click(within(dialog).getByRole('button', { name: 'Rechazar marca' }))
  await waitFor(() => expect(posts).toHaveLength(1))
})

test('correcting reads the typed time in the company zone and sends the expected effective time', async () => {
  const posts: { url: string; body: any }[] = []
  backend({ post: (url, body) => (posts.push({ url, body: body as any }), jsonResponse(detail({ status: 'VALID' }))) })
  const user = userEvent.setup()
  await asAdmin('/admin/review')
  await user.click(await screen.findByRole('button', { name: /^Corregir hora marca de Luis Vargas/ }))
  const dialog = await screen.findByRole('dialog')
  const time = within(dialog).getByLabelText(/Nueva hora/) as HTMLInputElement
  // 14:01 UTC is 08:01 in Costa Rica, whatever the zone of this computer.
  expect(time.value).toBe('2026-10-07T08:01')
  expect(within(dialog).getByText(/Hora de .*America\/Costa_Rica/)).toBeDefined()
  await user.click(within(dialog).getByRole('button', { name: /Usar la hora del teléfono \(07:55\)/ }))
  expect(time.value).toBe('2026-10-07T07:55')
  await user.click(within(dialog).getByRole('button', { name: 'Hora del teléfono incorrecta' }))
  await user.click(within(dialog).getByRole('button', { name: 'Guardar corrección' }))
  await waitFor(() => expect(posts).toHaveLength(1))
  expect(posts[0].url).toBe('/api/admin/time-entries/12/correct')
  expect(posts[0].body).toEqual({
    reason: 'Hora del teléfono incorrecta',
    expectedStatus: 'PENDING_REVIEW',
    expectedEffectiveTime: '2026-10-07T14:01:00Z',
    localTime: '2026-10-07T07:55',
  })
})

test('ENTRY_CHANGED closes the dialog, reloads the queue and explains', async () => {
  const { calls } = backend({ post: () => problemResponse(409, 'ENTRY_CHANGED') })
  const user = userEvent.setup()
  await asAdmin('/admin/review')
  await user.click(await screen.findByRole('button', { name: /^Aprobar marca de Luis Vargas/ }))
  const dialog = await screen.findByRole('dialog')
  await user.click(within(dialog).getByRole('button', { name: 'Verificado con la foto' }))
  const before = calls.filter((c) => c.url.startsWith('/api/admin/review-queue?')).length
  await user.click(within(dialog).getByRole('button', { name: 'Aprobar marca' }))
  expect(await screen.findByText(/Otra persona modificó esta marca/)).toBeDefined()
  await waitFor(() => expect(calls.filter((c) => c.url.startsWith('/api/admin/review-queue?')).length).toBe(before + 1))
})

test('the queue explains an empty list and keeps one heading and a count', async () => {
  backend({ queue: () => jsonResponse({ items: [], page: 0, size: 25, totalItems: 0 }) })
  await asAdmin('/admin/review')
  expect(await screen.findByText('No hay marcas por revisar.')).toBeDefined()
  expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1)
})

test('recording a missing clock-in sends the typed time and the reason', async () => {
  const posts: { url: string; body: any }[] = []
  backend({ post: (url, body) => (posts.push({ url, body: body as any }), jsonResponse(detail({ source: 'ADMIN' }), 201)) })
  const user = userEvent.setup()
  await asAdmin('/admin')
  await user.click(await screen.findByRole('button', { name: 'Registrar entrada de Diego Soto' }))
  const dialog = await screen.findByRole('dialog')
  expect((within(dialog).getByLabelText(/Hora de la entrada/) as HTMLInputElement).value).toBe('2026-10-07T08:00')
  await user.click(within(dialog).getByRole('button', { name: 'Autorizado por la jefatura' }))
  await user.click(within(dialog).getByRole('button', { name: 'Registrar entrada' }))
  await waitFor(() => expect(posts).toHaveLength(1))
  expect(posts[0].url).toBe('/api/admin/time-entries')
  expect(posts[0].body).toMatchObject({ type: 'CLOCK_IN', workDate: '2026-10-07', localTime: '2026-10-07T08:00', reason: 'Autorizado por la jefatura' })
})

test('CLOCK_IN_REQUIRED and ENTRY_ALREADY_EXISTS are explained inside the dialog', async () => {
  backend({ post: () => problemResponse(409, 'ENTRY_ALREADY_EXISTS') })
  const user = userEvent.setup()
  await asAdmin('/admin')
  await user.click(await screen.findByRole('button', { name: 'Registrar entrada de Diego Soto' }))
  const dialog = await screen.findByRole('dialog')
  await user.click(within(dialog).getByRole('button', { name: 'Autorizado por la jefatura' }))
  await user.click(within(dialog).getByRole('button', { name: 'Registrar entrada' }))
  expect(await within(dialog).findByText('Este empleado ya tiene esa marca para el día seleccionado.')).toBeDefined()
  expect(within(dialog).getByRole('button', { name: 'Actualizar tabla' })).toBeDefined()
})

test('an expired token opens the PIN dialog over the page without replaying anything', async () => {
  const { calls } = stubFetch(
    (url) => (url.startsWith('/api/admin/dashboard') ? problemResponse(401, 'INVALID_CREDENTIALS') : undefined),
    (url) => (url === '/api/admin/branches' ? jsonResponse(branches) : undefined),
    (url) => (url === '/api/admin/review-queue/count' ? jsonResponse({ pending: 0, today: '2026-10-07', timeZone: 'America/Costa_Rica' }) : undefined),
  )
  await asAdmin('/admin')
  const dialog = await screen.findByRole('dialog')
  expect(within(dialog).getByRole('heading', { name: 'Ingrese su PIN' })).toBeDefined()
  expect(within(dialog).getByText(/repita la acción al terminar/)).toBeDefined()
  expect(calls.filter((c) => c.init?.method === 'POST')).toHaveLength(0)
})

test('the date filters are bounded by the server day and never send a value outside the allowed range', async () => {
  const { calls } = backend()
  await asAdmin('/admin/review')
  await screen.findByRole('heading', { level: 3, name: 'Luis Vargas' })
  const to = screen.getByLabelText('Hasta') as HTMLInputElement
  const from = screen.getByLabelText('Desde') as HTMLInputElement
  await waitFor(() => expect(to.max).toBe('2026-10-07'))
  expect(to.min).toBe('2021-10-07')
  expect(from.max).toBe('2026-10-07')
  const queries = () => calls.filter((c) => c.url.startsWith('/api/admin/review-queue?')).length
  const before = queries()

  // Future, too old, and a partial year typed by the browser: nothing is requested, the list stays.
  for (const bad of ['2026-10-08', '2021-10-06', '0002-10-30']) {
    fireEvent.change(to, { target: { value: bad } })
  }
  expect(queries()).toBe(before)
  expect(screen.getByRole('heading', { level: 3, name: 'Luis Vargas' })).toBeDefined()
  expect(screen.queryByRole('alert')).toBeNull()

  fireEvent.change(to, { target: { value: '2026-10-07' } })
  await waitFor(() => expect(calls.some((c) => c.url.includes('to=2026-10-07'))).toBe(true))
  fireEvent.change(from, { target: { value: '2021-10-07' } })
  await waitFor(() => expect(calls.some((c) => c.url.includes('from=2021-10-07'))).toBe(true))
})
