import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { expect, test } from 'vitest'
import { markNeedsReauth } from '../../auth/session'
import { jsonResponse, problemResponse } from '../../test/fixtures'
import { adminIdentity } from '../../test/adminFixtures'
import { renderApp, seedSession } from '../../test/appHarness'
import { branchView, detailView, employeeView, manageBackend } from '../../test/manageBackend'

const branches = [branchView(), branchView({ id: 2, name: 'Sucursal Dos', activeEmployees: 0 })]
const admins = [
  employeeView({ id: 9, code: '9001', name: 'Ana Mora', role: 'ADMIN', branchId: null, branchName: null, branchActive: null }),
  employeeView({ id: 20, code: 'ABC-123', name: 'Nombre Apellido' }),
]

async function open(id: number, session: Partial<typeof adminIdentity> = {}) {
  await seedSession({ identity: { ...adminIdentity, ...session } })
  return renderApp(`/admin/employees/${id}`)
}

const resetButton = () => screen.getByRole('button', { name: 'Restablecer PIN' })

test('the h1 is the name, the tab title is generic and the page has no name or code in the title', async () => {
  manageBackend({ detail: detailView(), employees: admins, branches })
  await open(20)
  expect(await screen.findByRole('heading', { level: 1, name: 'Nombre Apellido' })).toBeDefined()
  expect(document.title).toBe('Detalle del empleado · Panel del administrador')
  expect(document.title).not.toContain('Nombre')
  expect(screen.getByText('ABC-123 · Cajera · Sucursal Uno')).toBeDefined()
  expect(screen.getByText(/No se puede cambiar/)).toBeDefined()
})

test('saving sends expectedVersion and the next save uses the version that came back', async () => {
  const backend = manageBackend({
    detail: detailView({ version: 3 }),
    employees: admins,
    branches,
    routes: (url, method, body) =>
      url === '/api/admin/employees/20' && method === 'PUT'
        ? jsonResponse(employeeView({ name: String((body as { name: string }).name), version: (body as { expectedVersion: number }).expectedVersion + 1 }))
        : undefined,
  })
  const user = userEvent.setup()
  await open(20)
  const name = await screen.findByLabelText(/^Nombre completo/)
  expect(screen.getByRole('button', { name: 'Guardar cambios' }).getAttribute('aria-disabled')).toBe('true')
  await user.clear(name)
  await user.type(name, 'Nombre Nuevo')
  await user.click(screen.getByRole('button', { name: 'Guardar cambios' }))
  await waitFor(() => expect(backend.writes).toHaveLength(1))
  expect(backend.writes[0].body).toMatchObject({ name: 'Nombre Nuevo', expectedVersion: 3, role: 'EMPLOYEE', branchId: 1 })
  expect(await screen.findByRole('heading', { level: 1, name: 'Nombre Nuevo' })).toBeDefined()
  await user.type(name, ' 2')
  await user.click(screen.getByRole('button', { name: 'Guardar cambios' }))
  await waitFor(() => expect(backend.writes).toHaveLength(2))
  expect(backend.writes[1].body).toMatchObject({ expectedVersion: 4 })
})

test('a stale record shows the box and Recargar refetches and discards the edits', async () => {
  let loads = 0
  const backend = manageBackend({
    employees: admins,
    branches,
    routes: (url, method) => {
      if (url === '/api/admin/employees/20' && method === 'PUT') return problemResponse(409, 'RECORD_CHANGED')
      if (url === '/api/admin/employees/20' && method === 'GET') {
        loads += 1
        return jsonResponse(detailView({ name: loads > 1 ? 'Nombre De Otra Persona' : 'Nombre Apellido', version: loads + 2 }))
      }
      return undefined
    },
  })
  const user = userEvent.setup()
  await open(20)
  const name = await screen.findByLabelText(/^Nombre completo/)
  await user.clear(name)
  await user.type(name, 'Mi Edicion')
  await user.click(screen.getByRole('button', { name: 'Guardar cambios' }))
  expect(await screen.findByText(/Otra persona modificó este registro/)).toBeDefined()
  expect(screen.getByText(/Al recargar se descartan los cambios/)).toBeDefined()
  await user.click(screen.getByRole('button', { name: 'Recargar' }))
  await waitFor(() => expect((screen.getByLabelText(/^Nombre completo/) as HTMLInputElement).value).toBe('Nombre De Otra Persona'))
  expect(backend.writes).toHaveLength(1)
})

test('changing the role asks first and sends nothing when cancelled', async () => {
  const backend = manageBackend({ detail: detailView(), employees: admins, branches })
  const user = userEvent.setup()
  await open(20)
  await user.click(await screen.findByRole('radio', { name: 'Administrador' }))
  await user.click(screen.getByRole('button', { name: 'Guardar cambios' }))
  const dialog = await screen.findByRole('dialog', { name: '¿Dar acceso de administrador a Nombre Apellido?' })
  await user.click(within(dialog).getByRole('button', { name: 'Cancelar' }))
  expect(backend.writes).toHaveLength(0)
})

test('own account: deactivating and the employee role are blocked with the visible reason', async () => {
  manageBackend({
    detail: detailView({ id: 9, code: '9001', name: 'Ana Mora', role: 'ADMIN', branchId: null, branchName: null, branchActive: null }),
    employees: [...admins, employeeView({ id: 30, role: 'ADMIN', name: 'Otra Admin' })],
    branches,
  })
  await open(9)
  const deactivate = await screen.findByRole('button', { name: 'Desactivar empleado' })
  expect(deactivate.getAttribute('aria-disabled')).toBe('true')
  const note = screen.getByText(/Esta es su propia cuenta/)
  expect(deactivate.getAttribute('aria-describedby')).toBe(note.closest('p')?.id)
  expect(screen.getByRole('radio', { name: 'Empleado' }).getAttribute('aria-disabled')).toBe('true')
})

test('the only active administrator sees the last-admin note', async () => {
  manageBackend({
    detail: detailView({ id: 9, name: 'Ana Mora', role: 'ADMIN', branchId: null, branchName: null, branchActive: null }),
    employees: admins,
    branches,
  })
  await open(9)
  expect(await screen.findByText(/Es el único administrador activo/)).toBeDefined()
})

test('another active unlocked administrator: reset PIN is blocked with the reason, a locked one is not', async () => {
  const other = detailView({ id: 31, code: 'ADM-002', name: 'Otra Admin', role: 'ADMIN', branchId: null, branchName: null, branchActive: null })
  manageBackend({ detail: other, employees: admins, branches })
  await open(31)
  const button = await screen.findByRole('button', { name: 'Restablecer PIN' })
  expect(button.getAttribute('aria-disabled')).toBe('true')
  const note = screen.getByText(/No se puede restablecer el PIN de otro administrador activo\./)
  expect(button.getAttribute('aria-describedby')).toBe(note.closest('p')?.id)
})

test('a locked administrator can be reset', async () => {
  const other = detailView({ id: 31, name: 'Otra Admin', role: 'ADMIN', locked: true, lockedUntil: '2026-10-07T16:30:00Z' })
  manageBackend({ detail: other, employees: admins, branches })
  await open(31)
  expect((await screen.findByRole('button', { name: 'Restablecer PIN' })).getAttribute('aria-disabled')).toBeNull()
  expect(screen.queryByText(/No se puede restablecer el PIN de otro administrador activo/)).toBeNull()
})

test('the last PIN reset line shows date, who, the translated reason and the note as text', async () => {
  manageBackend({
    detail: detailView({ lastPinReset: { at: '2026-11-03T16:14:00Z', byName: 'Ana Mora', reasonCode: 'FORGOT_PIN', note: '<b>Solicitado</b> en persona' } }),
    employees: admins,
    branches,
  })
  await open(20)
  expect(await screen.findByText(/Último restablecimiento de PIN: .* por Ana Mora · Olvidó el PIN/)).toBeDefined()
  expect(screen.getByText('Nota: <b>Solicitado</b> en persona')).toBeDefined()
  expect(document.querySelector('.adm-last-reset b')).toBeNull()
})

test('unlocking moves the focus to Reset PIN', async () => {
  const locked = detailView({ locked: true, lockedUntil: '2026-10-07T16:30:00Z' })
  manageBackend({
    detail: locked,
    employees: admins,
    branches,
    routes: (url, method) => (url === '/api/admin/employees/20/unlock' && method === 'POST' ? jsonResponse(employeeView()) : undefined),
  })
  const user = userEvent.setup()
  await open(20)
  await user.click(await screen.findByRole('button', { name: 'Desbloquear' }))
  await waitFor(() => expect(screen.queryByRole('button', { name: 'Desbloquear' })).toBeNull())
  await waitFor(() => expect(document.activeElement).toBe(resetButton()))
})

test('resetting another account shows the PIN only inside the dialog and wipes it on close', async () => {
  const backend = manageBackend({
    detail: detailView(),
    employees: admins,
    branches,
    routes: (url, method) =>
      url === '/api/admin/employees/20/pin-reset' && method === 'POST'
        ? jsonResponse({ employee: detailView({ lastPinReset: { at: '2026-11-03T16:14:00Z', byName: 'Ana Mora', reasonCode: 'ACCOUNT_LOCKED', note: null } }), pin: '48207315' })
        : undefined,
  })
  const user = userEvent.setup()
  await open(20)
  await user.click(await screen.findByRole('button', { name: 'Restablecer PIN' }))
  await user.click(await screen.findByRole('radio', { name: 'Cuenta bloqueada' }))
  await user.click(within(screen.getByRole('alertdialog')).getByRole('button', { name: 'Restablecer PIN' }))
  expect(await screen.findByRole('heading', { name: 'PIN restablecido' })).toBeDefined()
  expect(backend.writes[0].body).toEqual({ reasonCode: 'ACCOUNT_LOCKED' })
  const dialog = screen.getByRole('dialog')
  expect(within(dialog).getByText('4820')).toBeDefined()
  expect(document.title.includes('4820'), 'the title carries the PIN').toBe(false)
  expect(dialog.getAttribute('aria-describedby')).toBeNull()

  await user.click(within(dialog).getByRole('button', { name: 'Ya lo anoté o memoricé' }))
  await waitFor(() => expect(screen.queryByRole('heading', { name: 'PIN restablecido' })).toBeNull())
  expect(document.body.innerHTML.includes('48207315'), 'the page still carries the PIN').toBe(false)
  expect(document.body.innerHTML.includes('4820'), 'the page still carries part of the PIN').toBe(false)
  expect(document.activeElement).toBe(resetButton())
  expect(await screen.findByText(/Último restablecimiento de PIN: .* por Ana Mora · Cuenta bloqueada/)).toBeDefined()
})

test('resetting your own PIN keeps the dialog over a 401, then signs out locally without calling logout', async () => {
  const backend = manageBackend({
    detail: detailView({ id: 9, code: '9001', name: 'Ana Mora', role: 'ADMIN', branchId: null, branchName: null, branchActive: null }),
    employees: [...admins, employeeView({ id: 30, role: 'ADMIN', name: 'Otra Admin' })],
    branches,
    routes: (url, method) =>
      url === '/api/admin/employees/9/pin-reset' && method === 'POST'
        ? jsonResponse({ employee: detailView({ id: 9, name: 'Ana Mora', role: 'ADMIN' }), pin: '48207315' })
        : undefined,
  })
  const user = userEvent.setup()
  await open(9)
  await user.click(await screen.findByRole('button', { name: 'Restablecer PIN' }))
  await user.click(within(screen.getByRole('alertdialog')).getByRole('button', { name: 'Restablecer PIN' }))
  expect(await screen.findByRole('heading', { name: 'PIN restablecido' })).toBeDefined()
  expect(screen.getByText(/al cerrar esta ventana se cerrará su sesión/)).toBeDefined()

  // The caller's token is already invalid on the server: a 401 must not open the re-auth dialog over the PIN.
  markNeedsReauth()
  await new Promise((resolve) => setTimeout(resolve, 50))
  expect(screen.queryByRole('heading', { name: 'Ingrese su PIN' })).toBeNull()
  expect(screen.getByText('4820')).toBeDefined()

  await user.click(screen.getByRole('button', { name: 'Ya lo anoté o memoricé' }))
  expect(await screen.findByRole('heading', { name: 'Iniciar sesión' })).toBeDefined()
  expect(backend.calls.some((c) => c.url === '/api/auth/logout')).toBe(false)
})

test('error boxes never repeat the code or the name', async () => {
  manageBackend({
    detail: detailView(),
    employees: admins,
    branches,
    routes: (url, method) => (url === '/api/admin/employees/20' && method === 'PUT' ? problemResponse(409, 'BRANCH_INACTIVE') : undefined),
  })
  const user = userEvent.setup()
  await open(20)
  const name = await screen.findByLabelText(/^Nombre completo/)
  fireEvent.change(name, { target: { value: 'Otro Nombre Distinto' } })
  await user.click(screen.getByRole('button', { name: 'Guardar cambios' }))
  const alert = await screen.findByText('La sucursal seleccionada está inactiva.')
  expect(alert.textContent).not.toContain('ABC-123')
  expect(alert.textContent).not.toContain('Otro Nombre')
})

test('a retired current branch is shown as an enabled selected option with the inactive suffix', async () => {
  manageBackend({
    detail: detailView({ branchId: 7, branchName: 'Sucursal Vieja', branchActive: false }),
    employees: admins,
    branches,
  })
  await open(20)
  const select = (await screen.findByLabelText(/^Sucursal/)) as HTMLSelectElement
  const option = within(select).getByRole('option', { name: 'Sucursal Vieja (inactiva)' }) as HTMLOptionElement
  expect(option.disabled).toBe(false)
  expect(select.value).toBe('7')
})

test('an unknown id shows a not-found message with a generic title', async () => {
  manageBackend({
    employees: admins,
    branches,
    routes: (url, method) => (url === '/api/admin/employees/99' && method === 'GET' ? problemResponse(404, 'NOT_FOUND') : undefined),
  })
  await open(99)
  expect(await screen.findByRole('heading', { level: 1, name: 'Empleado no encontrado' })).toBeDefined()
  expect(screen.getByText('No se encontró lo que buscaba.')).toBeDefined()
})

test('deactivating asks first, sends expectedVersion and announces the result', async () => {
  const backend = manageBackend({
    detail: detailView({ version: 5 }),
    employees: admins,
    branches,
    routes: (url, method) =>
      url === '/api/admin/employees/20/deactivate' && method === 'POST' ? jsonResponse(employeeView({ active: false, version: 6 })) : undefined,
  })
  const user = userEvent.setup()
  await open(20)
  await user.click(await screen.findByRole('button', { name: 'Desactivar empleado' }))
  const dialog = await screen.findByRole('alertdialog', { name: '¿Desactivar a Nombre Apellido?' })
  expect(backend.writes).toHaveLength(0)
  await user.click(within(dialog).getByRole('button', { name: 'Desactivar' }))
  await waitFor(() => expect(backend.writes).toHaveLength(1))
  expect(backend.writes[0]).toMatchObject({ url: '/api/admin/employees/20/deactivate', method: 'POST', body: { expectedVersion: 5 } })
  expect(await screen.findByText('Empleado desactivado. Sus sesiones se cerraron.')).toBeDefined()
  expect(await screen.findByRole('button', { name: 'Activar empleado' })).toBeDefined()
})

test('activating sends expectedVersion at once, without a confirmation', async () => {
  const backend = manageBackend({
    detail: detailView({ active: false, version: 7 }),
    employees: admins,
    branches,
    routes: (url, method) =>
      url === '/api/admin/employees/20/activate' && method === 'POST' ? jsonResponse(employeeView({ active: true, version: 8 })) : undefined,
  })
  const user = userEvent.setup()
  await open(20)
  await user.click(await screen.findByRole('button', { name: 'Activar empleado' }))
  await waitFor(() => expect(backend.writes).toHaveLength(1))
  expect(backend.writes[0]).toMatchObject({ url: '/api/admin/employees/20/activate', body: { expectedVersion: 7 } })
  expect(await screen.findByText('Empleado activado.')).toBeDefined()
})

test.each([
  ['LAST_ADMIN', 'La empresa debe conservar al menos un administrador activo.'],
  ['SELF_CHANGE_NOT_ALLOWED', 'No puede desactivarse ni quitarse el rol de administrador.'],
])('a refused deactivation (%s) shows the error box', async (code, text) => {
  manageBackend({
    detail: detailView(),
    employees: admins,
    branches,
    routes: (url, method) => (url === '/api/admin/employees/20/deactivate' && method === 'POST' ? problemResponse(409, code) : undefined),
  })
  const user = userEvent.setup()
  await open(20)
  await user.click(await screen.findByRole('button', { name: 'Desactivar empleado' }))
  await user.click(within(await screen.findByRole('alertdialog')).getByRole('button', { name: 'Desactivar' }))
  expect(await screen.findByText(text)).toBeDefined()
})
