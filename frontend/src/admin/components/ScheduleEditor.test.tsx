import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { expect, test, vi } from 'vitest'
import { jsonResponse, problemResponse } from '../../test/fixtures'
import { adminIdentity } from '../../test/adminFixtures'
import { renderApp, seedSession } from '../../test/appHarness'
import { branchView, detailView, employeeView, manageBackend, monday, type ManageOptions } from '../../test/manageBackend'

const people = [
  employeeView({ id: 9, code: '9001', name: 'Ana Mora', role: 'ADMIN', branchId: null, branchName: null, branchActive: null }),
  employeeView({ id: 20, code: 'ABC-123', name: 'Nombre Apellido' }),
  employeeView({ id: 21, code: 'XYZ-789', name: 'Persona Dos' }),
]

async function open(lang: 'es' | 'en' = 'es', routes?: ManageOptions['routes']) {
  const backend = manageBackend({ detail: detailView(), employees: people, branches: [branchView()], schedule: [monday], routes })
  await seedSession({ identity: adminIdentity })
  renderApp('/admin/employees/20', lang)
  await screen.findByRole('heading', { level: 2, name: lang === 'es' ? 'Horario semanal' : 'Weekly schedule' })
  await screen.findByLabelText(lang === 'es' ? 'Trabaja el lunes' : 'Works on monday')
  return backend
}

const works = (day: string) => screen.getByLabelText(`Trabaja el ${day}`) as HTMLInputElement
const saveButton = () => screen.getByRole('button', { name: 'Guardar horario' })

test('weekday names come from Intl in Spanish and English, Monday first', async () => {
  await open('es')
  const rows = within(screen.getByRole('table', { name: 'Horario semanal' })).getAllByRole('rowheader')
  expect(rows.map((r) => r.textContent)).toEqual(['Lunes', 'Martes', 'Miércoles', 'Jueves', 'Viernes', 'Sábado', 'Domingo'])
})

test('English names', async () => {
  await open('en')
  const rows = within(screen.getByRole('table', { name: 'Weekly schedule' })).getAllByRole('rowheader')
  expect(rows[0].textContent).toBe('Monday')
  expect(rows[6].textContent).toBe('Sunday')
})

test('unchecked days are disabled, checking fills the defaults and the overnight hint appears', async () => {
  const user = userEvent.setup()
  await open()
  expect((screen.getByLabelText('Hora de entrada del martes') as HTMLInputElement).disabled).toBe(true)
  expect(works('martes').checked).toBe(false)
  await user.click(works('martes'))
  const start = screen.getByLabelText('Hora de entrada del martes') as HTMLInputElement
  expect(start.disabled).toBe(false)
  expect(start.value).toBe('08:00')
  expect((screen.getByLabelText('Hora de salida del martes') as HTMLInputElement).value).toBe('17:00')
  expect((screen.getByLabelText('Tolerancia del martes en minutos') as HTMLInputElement).value).toBe('5')
  fireEvent.change(start, { target: { value: '22:00' } })
  fireEvent.change(screen.getByLabelText('Hora de salida del martes'), { target: { value: '06:00' } })
  expect(screen.getByText('Turno nocturno')).toBeDefined()
})

test('save is disabled until there is a change and sends the loaded week with the new one', async () => {
  const backend = await open(undefined, (url, method) =>
    url === '/api/admin/employees/20/schedule' && method === 'PUT'
      ? jsonResponse([monday, { dayOfWeek: 2, startTime: '08:00', endTime: '17:00', graceMinutes: 5 }])
      : undefined,
  )
  const user = userEvent.setup()
  expect(saveButton().getAttribute('aria-disabled')).toBe('true')
  await user.click(works('martes'))
  expect(saveButton().getAttribute('aria-disabled')).toBeNull()
  expect(screen.getByText('Cambios sin guardar')).toBeDefined()
  await user.click(saveButton())
  await waitFor(() => expect(backend.writes).toHaveLength(1))
  expect(backend.writes[0].body).toEqual({
    expected: [monday],
    days: [monday, { dayOfWeek: 2, startTime: '08:00', endTime: '17:00', graceMinutes: 5 }],
  })
  await waitFor(() => expect(saveButton().getAttribute('aria-disabled')).toBe('true'))
  expect(screen.queryByText('Cambios sin guardar')).toBeNull()
})

test('equal times and a grace over 240 are listed in an error summary with links and nothing is sent', async () => {
  const backend = await open()
  const user = userEvent.setup()
  fireEvent.change(screen.getByLabelText('Hora de salida del lunes'), { target: { value: '08:00' } })
  await user.click(works('martes'))
  fireEvent.change(screen.getByLabelText('Tolerancia del martes en minutos'), { target: { value: '241' } })
  await user.click(saveButton())
  await waitFor(() => expect(document.querySelector('.adm-schedule-summary')).not.toBeNull())
  expect(document.activeElement).toBe(document.querySelector('.adm-schedule-summary'))
  expect(await screen.findByRole('button', { name: 'Lunes: La entrada y la salida no pueden ser iguales.' })).toBeDefined()
  expect(screen.getByRole('button', { name: 'Martes: La tolerancia debe estar entre 0 y 240 minutos.' })).toBeDefined()
  expect(backend.writes).toHaveLength(0)
})

test('copying from another employee fills the grid without saving', async () => {
  const backend = await open(undefined, (url, method) =>
    url === '/api/admin/employees/21/schedule' && method === 'GET'
      ? jsonResponse([{ dayOfWeek: 3, startTime: '09:00', endTime: '18:00', graceMinutes: 10 }])
      : undefined,
  )
  const user = userEvent.setup()
  expect(screen.getByRole('button', { name: 'Copiar' }).getAttribute('aria-disabled')).toBe('true')
  await user.selectOptions(screen.getByLabelText('Copiar horario de…'), 'Persona Dos')
  await user.click(screen.getByRole('button', { name: 'Copiar' }))
  await waitFor(() => expect(works('miércoles').checked).toBe(true))
  expect(works('lunes').checked).toBe(false)
  expect(backend.writes).toHaveLength(0)
  expect(saveButton().getAttribute('aria-disabled')).toBeNull()
})

test('an empty week shows the unscheduled note and a stale week offers Recargar', async () => {
  const backend = await open(undefined, (url, method) =>
    url === '/api/admin/employees/20/schedule' && method === 'PUT' ? problemResponse(409, 'RECORD_CHANGED') : undefined,
  )
  const user = userEvent.setup()
  await user.click(works('lunes'))
  expect(screen.getByText(/Sin días de trabajo/)).toBeDefined()
  await user.click(saveButton())
  expect(await screen.findByText(/Otra persona modificó este registro/)).toBeDefined()
  expect(backend.writes[0].body).toEqual({ expected: [monday], days: [] })
  expect(screen.getByRole('button', { name: 'Recargar' })).toBeDefined()
})

test('leaving with unsaved edits asks first: stay keeps the page, leave navigates; the unload prompt is only active while dirty', async () => {
  const add = vi.spyOn(window, 'addEventListener')
  await open()
  const user = userEvent.setup()
  const beforeUnloadAdded = () => add.mock.calls.filter(([type]) => type === 'beforeunload').length
  expect(beforeUnloadAdded()).toBe(0)
  await user.click(works('martes'))
  await waitFor(() => expect(beforeUnloadAdded()).toBe(1))

  const nav = screen.getByRole('navigation', { name: 'Navegación principal' })
  await user.click(within(nav).getByRole('link', { name: /Sucursales/ }))
  const dialog = await screen.findByRole('dialog', { name: '¿Salir sin guardar?' })
  expect(document.activeElement).toBe(within(dialog).getByRole('button', { name: 'Seguir editando' }))
  await user.click(within(dialog).getByRole('button', { name: 'Seguir editando' }))
  await waitFor(() => expect(screen.queryByRole('dialog', { name: '¿Salir sin guardar?' })).toBeNull())
  expect(screen.getByRole('heading', { level: 1, name: 'Nombre Apellido' })).toBeDefined()

  await user.click(within(nav).getByRole('link', { name: /Sucursales/ }))
  await user.click(within(await screen.findByRole('dialog', { name: '¿Salir sin guardar?' })).getByRole('button', { name: 'Salir' }))
  expect(await screen.findByRole('heading', { level: 1, name: 'Sucursales' })).toBeDefined()
  add.mockRestore()
})

test('the unload prompt is removed again after saving and after discarding', async () => {
  const add = vi.spyOn(window, 'addEventListener')
  const remove = vi.spyOn(window, 'removeEventListener')
  const count = (spy: typeof add) => spy.mock.calls.filter(([type]) => type === 'beforeunload').length
  await open(undefined, (url, method) =>
    url === '/api/admin/employees/20/schedule' && method === 'PUT'
      ? jsonResponse([monday, { dayOfWeek: 2, startTime: '08:00', endTime: '17:00', graceMinutes: 5 }])
      : undefined,
  )
  const user = userEvent.setup()
  await user.click(works('martes'))
  await waitFor(() => expect(count(add)).toBe(1))
  await user.click(saveButton())
  await waitFor(() => expect(count(remove)).toBe(1))

  await user.click(works('miércoles'))
  await waitFor(() => expect(count(add)).toBe(2))
  await user.click(screen.getByRole('button', { name: 'Descartar cambios' }))
  await waitFor(() => expect(count(remove)).toBe(2))
  add.mockRestore()
  remove.mockRestore()
})

test('the back link to Empleados asks first while there are unsaved edits', async () => {
  await open()
  const user = userEvent.setup()
  await user.click(works('martes'))
  const back = screen.getAllByRole('link', { name: /Empleados/ }).find((a) => a.classList.contains('adm-link-button'))!
  await user.click(back)
  const dialog = await screen.findByRole('dialog', { name: '¿Salir sin guardar?' })
  await user.click(within(dialog).getByRole('button', { name: 'Seguir editando' }))
  expect(screen.getByRole('heading', { level: 1, name: 'Nombre Apellido' })).toBeDefined()
})
