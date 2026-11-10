import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useLocation } from 'react-router'
import { expect, test } from 'vitest'
import { problemResponse, jsonResponse } from '../../test/fixtures'
import { adminIdentity } from '../../test/adminFixtures'
import App from '../../App'
import { seedSession } from '../../test/appHarness'
import { renderWithI18n } from '../../test/renderWithI18n'
import { branchView, employeeView, manageBackend } from '../../test/manageBackend'

const people = [
  employeeView({ id: 20, code: 'ABC-123', name: 'Nombre Apellido' }),
  employeeView({ id: 21, code: 'XYZ-789', name: 'José Ñandú', locked: true, lockedUntil: '2026-10-07T16:30:00Z', consentGiven: false, hasSchedule: false }),
  employeeView({ id: 22, code: 'ADM-001', name: 'Persona Admin', role: 'ADMIN', branchId: null, branchName: null, branchActive: null }),
  employeeView({ id: 23, code: 'OLD-001', name: 'Persona Inactiva', active: false, branchId: 2, branchName: 'Sucursal Dos', branchActive: false }),
]

/** Shows the router's current search string, so tests can tell what reached the address bar. */
function LocationProbe() {
  return <output data-testid="router-search">{useLocation().search}</output>
}

async function asAdmin(path = '/admin/employees') {
  await seedSession({ identity: adminIdentity })
  return renderWithI18n(
    <>
      <App />
      <LocationProbe />
    </>,
    { route: path, providers: true },
  )
}

test('the list shows role, branch, state chips, the lock time in the company zone, consent and schedule', async () => {
  manageBackend({ employees: people })
  await asAdmin()
  const table = await screen.findByRole('table')
  const row = within(table).getByRole('link', { name: 'José Ñandú' }).closest('tr') as HTMLElement
  expect(within(row).getByText('Bloqueado hasta 10:30')).toBeDefined()
  expect(within(row).getByText('Consentimiento: No')).toBeDefined()
  expect(within(table).getByRole('link', { name: 'Persona Admin' }).closest('tr')?.textContent).toContain('Sin sucursal')
  expect(within(table).getByRole('link', { name: 'Persona Inactiva' }).closest('tr')?.textContent).toContain('Sucursal Dos (inactiva)')
  expect(screen.getByText('4 empleados')).toBeDefined()
})

test('the search ignores case and accents, filters by code too, and clearing shows everyone again', async () => {
  const backend = manageBackend({ employees: people })
  const user = userEvent.setup()
  await asAdmin()
  await screen.findByRole('table')
  await user.type(screen.getByLabelText('Buscar por nombre o código'), 'jose nandu')
  // The search text is filtered in the browser: it never reaches the address bar or a request URL.
  const search = screen.getByTestId('router-search').textContent ?? ''
  expect(search).toBe('')
  expect(search).not.toMatch(/q=|jose|nandu/i)
  expect(backend.calls.some((c) => /nandu|jose/i.test(decodeURIComponent(c.url)))).toBe(false)
  expect(screen.getAllByRole('link', { name: /José Ñandú|Nombre Apellido|Persona/ })).toHaveLength(1)
  await user.clear(screen.getByLabelText('Buscar por nombre o código'))
  await user.type(screen.getByLabelText('Buscar por nombre o código'), 'adm-0')
  expect(screen.getByRole('link', { name: 'Persona Admin' })).toBeDefined()
  expect(screen.queryByRole('link', { name: 'Nombre Apellido' })).toBeNull()
  await user.clear(screen.getByLabelText('Buscar por nombre o código'))
  await user.type(screen.getByLabelText('Buscar por nombre o código'), 'zzz')
  expect(await screen.findByText('Ningún empleado coincide.')).toBeDefined()
  await user.click(screen.getByRole('button', { name: 'Limpiar filtros' }))
  expect(screen.getAllByRole('link', { name: /Nombre|José|Persona/ })).toHaveLength(4)
})

test('the status and branch filters narrow the list and ?branch=<id> preselects the branch', async () => {
  manageBackend({ employees: people, branches: [branchView(), branchView({ id: 2, name: 'Sucursal Dos' })] })
  const user = userEvent.setup()
  await asAdmin('/admin/employees?branch=2')
  const table = await screen.findByRole('table')
  expect(within(table).getAllByRole('link')).toHaveLength(1)
  expect((screen.getByLabelText('Sucursal') as HTMLSelectElement).value).toBe('2')
  await user.click(screen.getByRole('button', { name: 'Limpiar filtros' }))
  await user.click(screen.getByRole('radio', { name: 'Bloqueados' }))
  expect(within(screen.getByRole('table')).getAllByRole('link')).toHaveLength(1)
  await user.click(screen.getByRole('radio', { name: 'Inactivos' }))
  expect(within(screen.getByRole('table')).getByRole('link', { name: 'Persona Inactiva' })).toBeDefined()
})

test('the nav has the employees and branches items and no reports item', async () => {
  manageBackend({ employees: people })
  await asAdmin()
  await screen.findByRole('table')
  const nav = screen.getByRole('navigation', { name: 'Navegación principal' })
  const names = within(nav).getAllByRole('link').map((a) => a.textContent)
  expect(names.some((n) => n?.includes('Empleados'))).toBe(true)
  expect(names.some((n) => n?.includes('Sucursales'))).toBe(true)
  expect(names.some((n) => n?.includes('Reportes'))).toBe(false)
  expect(within(nav).getByRole('link', { name: /Empleados/ }).getAttribute('aria-current')).toBe('page')
})

test('creating shows the PIN once in four plus four groups and wipes it from the page on close', async () => {
  const created = employeeView({ id: 30, code: 'NEW-001', name: 'Persona Nueva', version: 0 })
  const backend = manageBackend({
    employees: people,
    routes: (url, method) => (url === '/api/admin/employees' && method === 'POST' ? jsonResponse({ employee: created, pin: '48207315' }, 201) : undefined),
  })
  const user = userEvent.setup()
  await asAdmin()
  await screen.findByRole('table')
  await user.click(screen.getByRole('button', { name: 'Nuevo empleado' }))
  const code = await screen.findByLabelText(/^Código de empleado/)
  await user.type(code, 'new-001')
  fireEvent.blur(code)
  expect((code as HTMLInputElement).value).toBe('NEW-001')
  await user.type(screen.getByLabelText(/^Nombre completo/), 'Persona Nueva')
  await user.selectOptions(within(screen.getByRole('dialog')).getByLabelText(/^Sucursal/), 'Sucursal Uno')
  await user.click(screen.getByRole('button', { name: 'Crear empleado' }))

  expect(await screen.findByRole('heading', { name: 'Empleado creado' })).toBeDefined()
  expect(screen.getByText('4820')).toBeDefined()
  expect(screen.getByText('7315')).toBeDefined()
  expect(backend.writes[0].body).toMatchObject({ code: 'NEW-001', name: 'Persona Nueva', role: 'EMPLOYEE' })

  await user.click(screen.getByRole('button', { name: 'Ya lo anoté o memoricé' }))
  await waitFor(() => expect(screen.queryByRole('heading', { name: 'Empleado creado' })).toBeNull())
  for (const where of [document.body.textContent ?? '', JSON.stringify({ ...sessionStorage }), JSON.stringify({ ...localStorage }), document.title, document.body.innerHTML]) {
    expect(where.includes('48207315'), 'the PIN is still stored or shown').toBe(false)
    expect(where.includes('4820'), 'part of the PIN is still stored or shown').toBe(false)
  }
  expect(await screen.findByRole('link', { name: 'Persona Nueva' })).toBeDefined()
})

test('the code taken error appears under the field without repeating the code', async () => {
  manageBackend({
    employees: people,
    routes: (url, method) => (url === '/api/admin/employees' && method === 'POST' ? problemResponse(409, 'EMPLOYEE_CODE_TAKEN') : undefined),
  })
  const user = userEvent.setup()
  await asAdmin()
  await screen.findByRole('table')
  await user.click(screen.getByRole('button', { name: 'Nuevo empleado' }))
  await user.type(await screen.findByLabelText(/^Código de empleado/), 'abc-123')
  await user.type(screen.getByLabelText(/^Nombre completo/), 'Otra Persona')
  await user.selectOptions(within(screen.getByRole('dialog')).getByLabelText(/^Sucursal/), 'Sucursal Uno')
  await user.click(screen.getByRole('button', { name: 'Crear empleado' }))
  const alert = await screen.findByText('Otro empleado de esta empresa ya usa ese código.')
  expect(alert.textContent).not.toContain('ABC-123')
  expect(screen.getByLabelText(/^Código de empleado/).getAttribute('aria-invalid')).toBe('true')
})

test('client validation blocks invisible characters and an empty branch for an employee', async () => {
  const backend = manageBackend({ employees: people })
  const user = userEvent.setup()
  await asAdmin()
  await screen.findByRole('table')
  await user.click(screen.getByRole('button', { name: 'Nuevo empleado' }))
  await user.type(await screen.findByLabelText(/^Código de empleado/), 'ok-1')
  fireEvent.change(screen.getByLabelText(/^Nombre completo/), { target: { value: 'Con​oculto' } })
  await user.click(screen.getByRole('button', { name: 'Crear empleado' }))
  expect(await screen.findByText(/caracteres invisibles/)).toBeDefined()
  expect(await screen.findByText('Elija una sucursal para un empleado.')).toBeDefined()
  expect(backend.writes).toHaveLength(0)
})

test('a network error after submitting says the result is unknown and offers no retry', async () => {
  const backend = manageBackend({ employees: people })
  const user = userEvent.setup()
  await asAdmin()
  await screen.findByRole('table')
  // The write handler is registered first: after the list loaded, writes fail like an unreachable server.
  backend.mock.mockImplementation(async (_input: RequestInfo | URL, init?: RequestInit) => {
    if ((init?.method ?? 'GET') === 'POST') throw new TypeError('network down')
    return jsonResponse(people)
  })
  await user.click(screen.getByRole('button', { name: 'Nuevo empleado' }))
  await user.type(await screen.findByLabelText(/^Código de empleado/), 'net-1')
  await user.type(screen.getByLabelText(/^Nombre completo/), 'Persona Red')
  await user.selectOptions(within(screen.getByRole('dialog')).getByLabelText(/^Sucursal/), 'Sucursal Uno')
  await user.click(screen.getByRole('button', { name: 'Crear empleado' }))
  expect(await screen.findByText(/No se pudo confirmar si el empleado fue creado/)).toBeDefined()
  expect(screen.getByRole('button', { name: 'Crear empleado' }).getAttribute('aria-disabled')).toBe('true')
})

test('a 429 on create shows the wait time and keeps the form enabled', async () => {
  manageBackend({
    employees: people,
    routes: (url, method) =>
      url === '/api/admin/employees' && method === 'POST' ? problemResponse(429, 'RATE_LIMITED', { 'Retry-After': '600' }) : undefined,
  })
  const user = userEvent.setup()
  await asAdmin()
  await screen.findByRole('table')
  await user.click(screen.getByRole('button', { name: 'Nuevo empleado' }))
  await user.type(await screen.findByLabelText(/^Código de empleado/), 'rl-1')
  await user.type(screen.getByLabelText(/^Nombre completo/), 'Persona Limite')
  await user.selectOptions(within(screen.getByRole('dialog')).getByLabelText(/^Sucursal/), 'Sucursal Uno')
  await user.click(screen.getByRole('button', { name: 'Crear empleado' }))
  expect(await screen.findByText(/en 10 min/)).toBeDefined()
  expect(screen.getByRole('button', { name: 'Crear empleado' }).getAttribute('aria-disabled')).toBeNull()
})
