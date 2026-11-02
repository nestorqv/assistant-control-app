import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, expect, test } from 'vitest'
import { adminBackend, trackObjectUrls } from '../../test/adminBackend'
import { adminIdentity, dashboard, detail, entry, row } from '../../test/adminFixtures'
import { jsonResponse, problemResponse } from '../../test/fixtures'
import { renderApp, seedSession } from '../../test/appHarness'

beforeEach(() => {
  trackObjectUrls()
})

type Post = { url: string; body: any }

async function openCreate(name: string) {
  const user = userEvent.setup()
  await seedSession({ identity: adminIdentity })
  renderApp('/admin')
  await user.click(await screen.findByRole('button', { name }))
  return { user, dialog: await screen.findByRole('dialog') }
}

test('the clock-in dialog starts at the scheduled start in the company zone and says who and which day', async () => {
  adminBackend()
  const { dialog } = await openCreate('Registrar entrada de Diego Soto')
  expect(within(dialog).getByRole('heading', { name: 'Registrar entrada' })).toBeDefined()
  expect(within(dialog).getByText(/Diego Soto · /)).toBeDefined()
  expect(within(dialog).getByText(/Jornada del .*7 de octubre/i)).toBeDefined()
  expect((within(dialog).getByLabelText(/Hora de la entrada/) as HTMLInputElement).value).toBe('2026-10-07T08:00')
  expect(within(dialog).getByText(/America\/Costa_Rica/)).toBeDefined()
  expect(within(dialog).getByText('El empleado podrá marcar su salida desde su teléfono.')).toBeDefined()
})

test('the reason is required before any request, and the first invalid field gets the focus', async () => {
  const posts: Post[] = []
  adminBackend({ post: (url, body) => (posts.push({ url, body: body as any }), undefined) })
  const { user, dialog } = await openCreate('Registrar entrada de Diego Soto')
  await user.click(within(dialog).getByRole('button', { name: 'Registrar entrada' }))
  expect(within(dialog).getByText('Escriba un motivo de al menos 5 caracteres.')).toBeDefined()
  expect(document.activeElement).toBe(within(dialog).getByRole('textbox', { name: /Motivo/ }))
  expect(posts).toHaveLength(0)
})

test('an empty time is refused with the time field focused', async () => {
  adminBackend()
  const { user, dialog } = await openCreate('Registrar entrada de Diego Soto')
  const time = within(dialog).getByLabelText(/Hora de la entrada/) as HTMLInputElement
  fireEvent.change(time, { target: { value: '' } })
  await user.click(within(dialog).getByRole('button', { name: 'Registrar entrada' }))
  expect(within(dialog).getByText('Indique la nueva hora.')).toBeDefined()
  expect(document.activeElement).toBe(time)
})

test('a clock-out for a forgotten exit starts at the scheduled end and shows the clock-in already recorded', async () => {
  const posts: Post[] = []
  const rows = [row('Beto Forgot', { dayStatus: 'MISSING_CLOCK_OUT', inProgress: false, clockIn: entry({ id: 22, effectiveTime: '2026-10-07T14:00:00Z' }) })]
  adminBackend({
    dash: () => jsonResponse(dashboard({ rows })),
    post: (url, body) => (posts.push({ url, body: body as any }), jsonResponse(detail({ type: 'CLOCK_OUT', source: 'ADMIN' }), 201)),
  })
  const { user, dialog } = await openCreate('Registrar salida de Beto Forgot')
  expect(within(dialog).getByText('Entrada registrada: 08:00')).toBeDefined()
  expect((within(dialog).getByLabelText(/Hora de la salida/) as HTMLInputElement).value).toBe('2026-10-07T17:00')
  await user.click(within(dialog).getByRole('button', { name: 'Olvidó marcar la salida' }))
  await user.click(within(dialog).getByRole('button', { name: 'Registrar salida' }))
  await waitFor(() => expect(posts).toHaveLength(1))
  expect(posts[0].body).toMatchObject({ type: 'CLOCK_OUT', workDate: '2026-10-07', localTime: '2026-10-07T17:00', reason: 'Olvidó marcar la salida' })
  expect(posts[0].body.employeeId).toBe(rows[0].employee.id)
  await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
})

test('after a successful save the dialog closes, the table reloads and the result is announced', async () => {
  const { calls } = adminBackend({ post: () => jsonResponse(detail({ source: 'ADMIN' }), 201) })
  const { user, dialog } = await openCreate('Registrar entrada de Diego Soto')
  const before = calls.filter((c) => c.url.startsWith('/api/admin/dashboard')).length
  await user.click(within(dialog).getByRole('button', { name: 'Autorizado por la jefatura' }))
  await user.click(within(dialog).getByRole('button', { name: 'Registrar entrada' }))
  await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
  await waitFor(() => expect(calls.filter((c) => c.url.startsWith('/api/admin/dashboard')).length).toBe(before + 1))
  expect(Array.from(document.querySelectorAll('[aria-live]')).map((n) => n.textContent).join('|')).toMatch(/Entrada registrada para Diego Soto\./)
})

test('the reason sent is the typed text with its whitespace collapsed', async () => {
  const posts: Post[] = []
  adminBackend({ post: (url, body) => (posts.push({ url, body: body as any }), jsonResponse(detail(), 201)) })
  const { user, dialog } = await openCreate('Registrar entrada de Diego Soto')
  await user.type(within(dialog).getByRole('textbox', { name: /Motivo/ }), '  Olvidó\n\n marcar  ')
  await user.click(within(dialog).getByRole('button', { name: 'Registrar entrada' }))
  await waitFor(() => expect(posts).toHaveLength(1))
  expect(posts[0].body.reason).toBe('Olvidó marcar')
})

test('INVALID_ENTRY_TIME lands on the time field; the other errors go to the box above the buttons', async () => {
  adminBackend({ post: () => problemResponse(400, 'INVALID_ENTRY_TIME') })
  const { user, dialog } = await openCreate('Registrar entrada de Diego Soto')
  await user.click(within(dialog).getByRole('button', { name: 'Autorizado por la jefatura' }))
  await user.click(within(dialog).getByRole('button', { name: 'Registrar entrada' }))
  expect(await within(dialog).findByText(/La hora está en el futuro/)).toBeDefined()
  expect(document.activeElement).toBe(within(dialog).getByLabelText(/Hora de la entrada/))
})

test('CLOCK_IN_REQUIRED is explained and the dialog stays open to try again', async () => {
  adminBackend({ post: () => problemResponse(409, 'CLOCK_IN_REQUIRED') })
  const { user, dialog } = await openCreate('Registrar entrada de Diego Soto')
  await user.click(within(dialog).getByRole('button', { name: 'Autorizado por la jefatura' }))
  await user.click(within(dialog).getByRole('button', { name: 'Registrar entrada' }))
  expect(await within(dialog).findByText('Registre la entrada de ese día antes de la salida.')).toBeDefined()
  expect(within(dialog).queryByRole('button', { name: 'Actualizar tabla' })).toBeNull()
  expect(screen.getByRole('dialog')).toBeDefined()
})

test('ENTRY_ALREADY_EXISTS offers to refresh the table, which closes the dialog and reloads', async () => {
  const { calls } = adminBackend({ post: () => problemResponse(409, 'ENTRY_ALREADY_EXISTS') })
  const { user, dialog } = await openCreate('Registrar entrada de Diego Soto')
  await user.click(within(dialog).getByRole('button', { name: 'Autorizado por la jefatura' }))
  await user.click(within(dialog).getByRole('button', { name: 'Registrar entrada' }))
  await user.click(await within(dialog).findByRole('button', { name: 'Actualizar tabla' }))
  const before = calls.filter((c) => c.url.startsWith('/api/admin/dashboard')).length
  await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
  await waitFor(() => expect(calls.filter((c) => c.url.startsWith('/api/admin/dashboard')).length).toBeGreaterThanOrEqual(before))
})

test('an employee without a branch cannot be recorded for: the box says why and nothing is sent', async () => {
  const posts: Post[] = []
  const rows = [row('Sin Sucursal', { dayStatus: 'NOT_CLOCKED', branchName: null })]
  adminBackend({ dash: () => jsonResponse(dashboard({ rows })), post: (url, body) => (posts.push({ url, body: body as any }), undefined) })
  const { user, dialog } = await openCreate('Registrar entrada de Sin Sucursal')
  expect(within(dialog).getAllByText("No tiene sucursal asignada. Contacte a su administrador.").length).toBeGreaterThan(0)
  await user.click(within(dialog).getByRole('button', { name: 'Autorizado por la jefatura' }))
  await user.click(within(dialog).getByRole('button', { name: 'Registrar entrada' }))
  expect(posts).toHaveLength(0)
})

test('an inactive employee shows the historical-correction notice', async () => {
  const rows = [row('Ex Empleado', { dayStatus: 'NOT_CLOCKED', employee: { id: 77, code: '1077', name: 'Ex Empleado', jobTitle: null, active: false } })]
  adminBackend({ dash: () => jsonResponse(dashboard({ rows })) })
  const { dialog } = await openCreate('Registrar entrada de Ex Empleado')
  expect(within(dialog).getByText('Este empleado está inactivo; la corrección es histórica.')).toBeDefined()
})

test('an employee without a schedule gets a fallback time and the hint to enter the time', async () => {
  const rows = [row('Sin Horario', { dayStatus: 'NOT_CLOCKED', schedule: null })]
  adminBackend({ dash: () => jsonResponse(dashboard({ rows })) })
  const { dialog } = await openCreate('Registrar entrada de Sin Horario')
  expect(within(dialog).getByText('No hay horario: indique la hora.')).toBeDefined()
  expect((within(dialog).getByLabelText(/Hora de la entrada/) as HTMLInputElement).value).toBe('2026-10-07T08:00')
})

test('on a past day the "employee can clock out" hint is not shown', async () => {
  adminBackend({ dash: () => jsonResponse(dashboard({ date: '2026-10-06', today: '2026-10-07', rows: [row('Diego Soto', { dayStatus: 'NOT_CLOCKED' })] })) })
  const user = userEvent.setup()
  await seedSession({ identity: adminIdentity })
  renderApp('/admin?date=2026-10-06')
  await user.click(await screen.findByRole('button', { name: 'Registrar entrada de Diego Soto' }))
  const dialog = await screen.findByRole('dialog')
  expect(within(dialog).getByText(/Jornada del .*6 de octubre/i)).toBeDefined()
  expect(within(dialog).queryByText('El empleado podrá marcar su salida desde su teléfono.')).toBeNull()
})

test('Cancel closes without sending and returns the focus to the button', async () => {
  const posts: Post[] = []
  adminBackend({ post: (url, body) => (posts.push({ url, body: body as any }), undefined) })
  const { user, dialog } = await openCreate('Registrar entrada de Diego Soto')
  await user.click(within(dialog).getByRole('button', { name: 'Cancelar' }))
  await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
  expect(posts).toHaveLength(0)
  expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Registrar entrada de Diego Soto' }))
})

test('while saving, the buttons are disabled and a second submit does not send twice', async () => {
  const posts: Post[] = []
  let release: (r: Response) => void = () => undefined
  const gate = new Promise<Response>((resolve) => (release = resolve))
  adminBackend({
    post: (url, body) => {
      posts.push({ url, body: body as any })
      return gate as unknown as Response
    },
  })
  const { user, dialog } = await openCreate('Registrar entrada de Diego Soto')
  await user.click(within(dialog).getByRole('button', { name: 'Autorizado por la jefatura' }))
  const submit = within(dialog).getByRole('button', { name: 'Registrar entrada' })
  await user.click(submit)
  await waitFor(() => expect(within(dialog).getByRole('button', { name: /Guardando/ }).getAttribute('aria-disabled')).toBe('true'))
  await user.click(within(dialog).getByRole('button', { name: /Guardando/ }))
  fireEvent.submit(dialog.querySelector('form') as HTMLFormElement)
  expect(posts).toHaveLength(1)
  release(jsonResponse(detail({ source: 'ADMIN' }), 201))
  await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
})
