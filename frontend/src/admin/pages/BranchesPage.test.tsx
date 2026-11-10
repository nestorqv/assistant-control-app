import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { expect, test } from 'vitest'
import { jsonResponse, problemResponse } from '../../test/fixtures'
import { adminIdentity } from '../../test/adminFixtures'
import { renderApp, seedSession } from '../../test/appHarness'
import { branchView, manageBackend, type ManageOptions } from '../../test/manageBackend'

const branches = [
  branchView({ id: 1, name: 'Sucursal Uno', activeEmployees: 3, version: 2 }),
  branchView({ id: 2, name: 'Sucursal Dos', activeEmployees: 0, active: false, version: 5 }),
  branchView({ id: 3, name: 'Sucursal Tres', activeEmployees: 0, version: 0 }),
]

async function open(options: ManageOptions = {}) {
  const backend = manageBackend({ branches, ...options })
  await seedSession({ identity: adminIdentity })
  renderApp('/admin/branches')
  await screen.findByRole('table')
  return backend
}

test('the list has no coordinates and links the active employee count to the filtered employees', async () => {
  await open()
  expect(document.body.textContent).not.toMatch(/9\.9325|-84\.0796/)
  const link = screen.getByRole('link', { name: /^3/ })
  expect(link.getAttribute('href')).toBe('/admin/employees?branch=1')
  expect(link.textContent).toContain('empleados activos de esta sucursal')
  expect(screen.getByText('3 sucursales')).toBeDefined()
})

test('a branch with active employees cannot be deactivated and says why', async () => {
  const backend = await open()
  const user = userEvent.setup()
  const button = screen.getByRole('button', { name: 'Desactivar, Sucursal Uno' })
  expect(button.getAttribute('aria-disabled')).toBe('true')
  const note = document.getElementById(button.getAttribute('aria-describedby') ?? '')
  expect(note?.textContent).toBe('Tiene 3 empleados activos. Trasládelos o desactívelos primero.')
  await user.click(button)
  expect(screen.queryByRole('alertdialog')).toBeNull()
  expect(backend.writes).toHaveLength(0)
})

test('deactivating asks to confirm and sends the version of the row; activating needs no confirm', async () => {
  const backend = await open({
    routes: (url, method) => {
      if (method !== 'POST') return undefined
      if (url === '/api/admin/branches/3/deactivate') return jsonResponse(branchView({ id: 3, name: 'Sucursal Tres', active: false, version: 1 }))
      if (url === '/api/admin/branches/2/activate') return jsonResponse(branchView({ id: 2, name: 'Sucursal Dos', active: true, version: 6 }))
      return undefined
    },
  })
  const user = userEvent.setup()
  await user.click(screen.getByRole('button', { name: 'Desactivar, Sucursal Tres' }))
  const dialog = await screen.findByRole('alertdialog')
  await user.click(within(dialog).getByRole('button', { name: 'Desactivar' }))
  await waitFor(() => expect(backend.writes).toHaveLength(1))
  expect(backend.writes[0]).toMatchObject({ url: '/api/admin/branches/3/deactivate', body: { expectedVersion: 0 } })
  expect(await screen.findByRole('button', { name: 'Activar, Sucursal Tres' })).toBeDefined()

  await user.click(screen.getByRole('button', { name: 'Activar, Sucursal Dos' }))
  await waitFor(() => expect(backend.writes).toHaveLength(2))
  expect(backend.writes[1]).toMatchObject({ url: '/api/admin/branches/2/activate', body: { expectedVersion: 5 } })
})

test('a stale list is refetched by itself with a note and no Recargar button; in-use is explained', async () => {
  let reads = 0
  await open({
    routes: (url, method) => {
      if (url === '/api/admin/branches' && method === 'GET') reads += 1
      if (url === '/api/admin/branches/3/deactivate') return problemResponse(409, 'RECORD_CHANGED')
      return undefined
    },
  })
  const user = userEvent.setup()
  await user.click(screen.getByRole('button', { name: 'Desactivar, Sucursal Tres' }))
  await user.click(within(await screen.findByRole('alertdialog')).getByRole('button', { name: 'Desactivar' }))
  expect(await screen.findByText(/Se actualizó la lista/)).toBeDefined()
  expect(screen.queryByRole('button', { name: 'Recargar' })).toBeNull()
  await waitFor(() => expect(reads).toBeGreaterThan(1))
})

test('creating pastes the coordinates, builds the maps link from numbers and sends the cleaned values', async () => {
  const backend = await open({
    routes: (url, method) =>
      url === '/api/admin/branches' && method === 'POST' ? jsonResponse(branchView({ id: 9, name: 'Heredia', version: 0 }), 201) : undefined,
  })
  const user = userEvent.setup()
  await user.click(screen.getByRole('button', { name: 'Nueva sucursal' }))
  const dialog = await screen.findByRole('dialog')
  const link = () => within(dialog).getByRole('link', { name: /Revisar ubicación en Google Maps/ })
  expect(link().getAttribute('aria-disabled')).toBe('true')
  expect(link().getAttribute('href')).toBeNull()
  await user.type(within(dialog).getByLabelText(/^Nombre/), '  Heredia  ')
  const paste = within(dialog).getByLabelText('Pegar coordenadas')
  await user.click(paste)
  await user.paste('10.0024, -84.1165')
  expect((within(dialog).getByLabelText(/^Latitud/) as HTMLInputElement).value).toBe('10.0024')
  expect((within(dialog).getByLabelText(/^Longitud/) as HTMLInputElement).value).toBe('-84.1165')
  expect((paste as HTMLInputElement).value).toBe('')
  const href = link().getAttribute('href') ?? ''
  expect(href).toContain('query=10.002400,-84.116500')
  expect(link().getAttribute('rel')).toBe('noopener noreferrer')
  expect(link().getAttribute('target')).toBe('_blank')
  await user.click(within(dialog).getByRole('button', { name: 'Crear sucursal' }))
  await waitFor(() => expect(backend.writes).toHaveLength(1))
  expect(backend.writes[0].body).toEqual({ name: 'Heredia', latitude: 10.0024, longitude: -84.1165, radiusMeters: 100 })
  expect(await screen.findByRole('rowheader', { name: /Heredia/ })).toBeDefined()
})

test('a decimal comma shows the hint and fills nothing; radius and range errors are inline', async () => {
  const backend = await open()
  const user = userEvent.setup()
  await user.click(screen.getByRole('button', { name: 'Nueva sucursal' }))
  const dialog = await screen.findByRole('dialog')
  fireEvent.change(within(dialog).getByLabelText('Pegar coordenadas'), { target: { value: '9,9325' } })
  expect(within(dialog).getByText('Use punto como separador decimal, por ejemplo 9.9325.')).toBeDefined()
  expect((within(dialog).getByLabelText(/^Latitud/) as HTMLInputElement).value).toBe('')
  await user.type(within(dialog).getByLabelText(/^Nombre/), 'Otra')
  await user.type(within(dialog).getByLabelText(/^Latitud/), '95')
  await user.type(within(dialog).getByLabelText(/^Longitud/), '-84')
  fireEvent.change(within(dialog).getByLabelText(/^Radio permitido/), { target: { value: '9' } })
  await user.click(within(dialog).getByRole('button', { name: 'Crear sucursal' }))
  expect(await within(dialog).findByText('La latitud debe estar entre -90 y 90.')).toBeDefined()
  expect(within(dialog).getByText('El radio debe ser un número entero entre 10 y 5000.')).toBeDefined()
  expect(backend.writes).toHaveLength(0)
})

test('a duplicate name is reported under the name field', async () => {
  await open({
    routes: (url, method) => (url === '/api/admin/branches' && method === 'POST' ? problemResponse(409, 'BRANCH_NAME_TAKEN') : undefined),
  })
  const user = userEvent.setup()
  await user.click(screen.getByRole('button', { name: 'Nueva sucursal' }))
  const dialog = await screen.findByRole('dialog')
  await user.type(within(dialog).getByLabelText(/^Nombre/), 'Sucursal Uno')
  await user.type(within(dialog).getByLabelText(/^Latitud/), '9.9')
  await user.type(within(dialog).getByLabelText(/^Longitud/), '-84.0')
  await user.click(within(dialog).getByRole('button', { name: 'Crear sucursal' }))
  expect(await within(dialog).findByText('Otra sucursal ya tiene ese nombre.')).toBeDefined()
  expect(within(dialog).getByLabelText(/^Nombre/).getAttribute('aria-invalid')).toBe('true')
})
