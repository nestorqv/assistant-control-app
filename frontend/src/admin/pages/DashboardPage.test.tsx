import { act, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, expect, test, vi } from 'vitest'
import { adminBackend, narrowScreen, trackObjectUrls } from '../../test/adminBackend'
import { adminIdentity, dashboard, entry, row } from '../../test/adminFixtures'
import { jsonResponse, problemResponse } from '../../test/fixtures'
import { renderApp, seedSession } from '../../test/appHarness'

beforeEach(() => {
  trackObjectUrls()
})
afterEach(() => {
  vi.useRealTimers()
})

async function asAdmin(path = '/admin', lang: 'es' | 'en' = 'es') {
  await seedSession({ identity: adminIdentity })
  return renderApp(path, lang)
}

/** What the page does when the tab becomes visible again (the minute timer calls the same function). */
function becomeVisible() {
  act(() => {
    document.dispatchEvent(new Event('visibilitychange'))
  })
}

const dashCalls = (calls: { url: string }[]) => calls.filter((c) => c.url.startsWith('/api/admin/dashboard')).length

test('every day status has its own chip text, and late shows the minutes', async () => {
  const rows = [
    row('Ana Late', { dayStatus: 'LATE', lateMinutes: 17, clockIn: entry({ id: 21 }), inProgress: true }),
    row('Beto Missing', { dayStatus: 'MISSING_CLOCK_OUT', clockIn: entry({ id: 22 }), inProgress: false }),
    row('Cris Rejected', { dayStatus: 'REJECTED', clockIn: entry({ id: 23, status: 'REJECTED' }) }),
    row('Dora Review', { dayStatus: 'NEEDS_REVIEW', clockIn: entry({ id: 24, status: 'PENDING_REVIEW' }) }),
    row('Edu Off', { dayStatus: 'DAY_OFF', schedule: null }),
    row('Fabi Unscheduled', { dayStatus: 'UNSCHEDULED', schedule: null }),
    row('Gus Due', { dayStatus: 'NOT_YET_DUE' }),
  ]
  adminBackend({ dash: () => jsonResponse(dashboard({ rows })) })
  await asAdmin()
  const table = await screen.findByRole('table')
  for (const text of ['Tarde 17 min', 'Sin salida', 'Rechazada', 'Por revisar', 'Libre', 'Sin horario', 'Aún no inicia']) {
    expect(within(table).getByText(text)).toBeDefined()
  }
})

test('the "record" shortcut appears for a missing clock-in and a missing clock-out, never while the shift is in progress', async () => {
  const rows = [
    row('Ana Absent', { dayStatus: 'NOT_CLOCKED' }),
    row('Beto Forgot', { dayStatus: 'MISSING_CLOCK_OUT', inProgress: false, clockIn: entry({ id: 22 }) }),
    row('Cris Working', { dayStatus: 'MISSING_CLOCK_OUT', inProgress: true, clockIn: entry({ id: 23 }) }),
    row('Dora Done', { dayStatus: 'COMPLETE', clockIn: entry({ id: 24 }), clockOut: entry({ id: 25, type: 'CLOCK_OUT' }), workedMinutes: 480 }),
  ]
  adminBackend({ dash: () => jsonResponse(dashboard({ rows })) })
  await asAdmin()
  await screen.findByRole('table')
  expect(screen.getByRole('button', { name: 'Registrar entrada de Ana Absent' })).toBeDefined()
  expect(screen.getByRole('button', { name: 'Registrar salida de Beto Forgot' })).toBeDefined()
  expect(screen.queryByRole('button', { name: /Registrar .* de Cris Working/ })).toBeNull()
  expect(screen.queryByRole('button', { name: /Registrar .* de Dora Done/ })).toBeNull()
})

test('the review KPI links to the queue filtered to the shown day', async () => {
  adminBackend()
  await asAdmin()
  await screen.findByRole('table')
  const link = screen.getAllByRole('link').find((a) => /Por revisar/.test(a.textContent ?? ''))
  expect(link?.getAttribute('href')).toBe('/admin/review?from=2026-10-07&to=2026-10-07')
})

test('the KPI cards show placeholders while loading and never zeros', async () => {
  adminBackend({ dash: () => problemResponse(500, 'INTERNAL_ERROR') })
  await asAdmin()
  await screen.findAllByRole('alert')
  const text = screen.getAllByRole('listitem').map((c) => c.textContent ?? '').join('|')
  expect(text).toContain('…')
  expect(text).not.toMatch(/Presentes\s*0/)
})

test('the table and the page never show the exact coordinates or a map link; only the evidence dialog does', async () => {
  adminBackend()
  const user = userEvent.setup()
  await asAdmin()
  const table = await screen.findByRole('table')
  expect(table.textContent).not.toMatch(/9\.93\d*, -84/)
  expect(document.querySelector('a[href*="google"]')).toBeNull()
  expect(document.querySelector('a[target="_blank"]')).toBeNull()

  await user.click(screen.getByRole('button', { name: /GPS a 420 m/ }))
  const dialog = await screen.findByRole('dialog')
  expect(await within(dialog).findByText('9.932500, -84.079600')).toBeDefined()
  expect(dialog.querySelectorAll('a[href^="https://www.google.com/maps/search/"]').length).toBeGreaterThanOrEqual(1)

  await user.click(within(dialog).getByRole('button', { name: 'Cerrar' }))
  await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
  expect(document.querySelector('a[href*="google"]')).toBeNull()
})

test('the auto refresh reloads today when the tab becomes visible', async () => {
  const { calls } = adminBackend()
  await asAdmin()
  await screen.findByRole('table')
  const before = dashCalls(calls)
  becomeVisible()
  await waitFor(() => expect(dashCalls(calls)).toBe(before + 1))
})

test('the real minute timer reloads today once per 60 s', async () => {
  // Only the interval is faked: findBy/waitFor and user-event keep the real timers.
  vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] })
  const { calls } = adminBackend()
  await asAdmin()
  await screen.findByRole('table')
  const before = dashCalls(calls)

  await act(async () => {
    vi.advanceTimersByTime(59_999)
  })
  expect(dashCalls(calls)).toBe(before)

  await act(async () => {
    vi.advanceTimersByTime(1)
  })
  await waitFor(() => expect(dashCalls(calls)).toBe(before + 1))

  await act(async () => {
    vi.advanceTimersByTime(60_000)
  })
  await waitFor(() => expect(dashCalls(calls)).toBe(before + 2))
})

test('the real minute timer never reloads a past day', async () => {
  vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] })
  const { calls } = adminBackend({ dash: (url) => jsonResponse(dashboard({ date: url.includes('date=2026-10-06') ? '2026-10-06' : '2026-10-07' })) })
  const user = userEvent.setup()
  await asAdmin()
  await screen.findByRole('table')
  await user.click(screen.getByRole('button', { name: 'Día anterior' }))
  await waitFor(() => expect(calls.some((c) => c.url === '/api/admin/dashboard?date=2026-10-06')).toBe(true))
  await screen.findByRole('heading', { level: 1, name: /Asistencia del/ })
  const before = dashCalls(calls)

  await act(async () => {
    vi.advanceTimersByTime(180_000)
  })
  await new Promise((resolve) => setTimeout(resolve, 30))
  expect(dashCalls(calls)).toBe(before)
})

test('with the auto refresh switched off nothing reloads by itself', async () => {
  const { calls } = adminBackend()
  const user = userEvent.setup()
  await asAdmin()
  await screen.findByRole('table')
  await user.click(screen.getByRole('checkbox', { name: 'Actualizar automáticamente' }))
  const before = dashCalls(calls)
  becomeVisible()
  await new Promise((resolve) => setTimeout(resolve, 30))
  expect(dashCalls(calls)).toBe(before)

  // Control: switched on again, the same event does reload.
  await user.click(screen.getByRole('checkbox', { name: 'Actualizar automáticamente' }))
  becomeVisible()
  await waitFor(() => expect(dashCalls(calls)).toBe(before + 1))
})

test('a past day is never refreshed automatically', async () => {
  const { calls } = adminBackend({ dash: (url) => jsonResponse(dashboard({ date: url.includes('date=2026-10-06') ? '2026-10-06' : '2026-10-07' })) })
  const user = userEvent.setup()
  await asAdmin()
  await screen.findByRole('table')
  await user.click(screen.getByRole('button', { name: 'Día anterior' }))
  await waitFor(() => expect(calls.some((c) => c.url === '/api/admin/dashboard?date=2026-10-06')).toBe(true))
  await screen.findByRole('heading', { level: 1, name: /Asistencia del/ })
  const before = dashCalls(calls)
  becomeVisible()
  await new Promise((resolve) => setTimeout(resolve, 30))
  expect(dashCalls(calls)).toBe(before)

  // Control: the manual button still reloads, so the counter sees requests.
  await user.click(screen.getByRole('button', { name: 'Actualizar' }))
  await waitFor(() => expect(dashCalls(calls)).toBe(before + 1))
})

test('the auto refresh pauses while a dialog is open and resumes when it closes', async () => {
  const { calls } = adminBackend()
  const user = userEvent.setup()
  await asAdmin()
  await user.click(await screen.findByRole('button', { name: /GPS a 420 m/ }))
  const dialog = await screen.findByRole('dialog')
  await within(dialog).findByRole('img')

  const open = dashCalls(calls)
  becomeVisible()
  await new Promise((resolve) => setTimeout(resolve, 30))
  expect(dashCalls(calls)).toBe(open)

  await user.click(within(dialog).getByRole('button', { name: 'Cerrar' }))
  await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
  becomeVisible()
  await waitFor(() => expect(dashCalls(calls)).toBe(open + 1))
})

test('the auto refresh also pauses while the record-entry dialog is open', async () => {
  const { calls } = adminBackend()
  const user = userEvent.setup()
  await asAdmin()
  await user.click(await screen.findByRole('button', { name: 'Registrar entrada de Diego Soto' }))
  await screen.findByRole('dialog')
  const open = dashCalls(calls)
  becomeVisible()
  await new Promise((resolve) => setTimeout(resolve, 30))
  expect(dashCalls(calls)).toBe(open)

  await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Cancelar' }))
  await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
  becomeVisible()
  await waitFor(() => expect(dashCalls(calls)).toBe(open + 1))
})

test('a failed refresh keeps the table, dims it and says from when the data is', async () => {
  let fail = false
  const { calls } = adminBackend({ dash: () => (fail ? problemResponse(500, 'INTERNAL_ERROR') : jsonResponse(dashboard())) })
  const user = userEvent.setup()
  await asAdmin()
  await screen.findByRole('table')
  fail = true
  await user.click(screen.getByRole('button', { name: 'Actualizar' }))
  await waitFor(() => expect(dashCalls(calls)).toBeGreaterThanOrEqual(2))
  expect(await screen.findByText(/Datos de las \d{2}:\d{2}/)).toBeDefined()
  expect(screen.getByRole('table')).toBeDefined()
  expect(screen.getByRole('button', { name: 'Reintentar' })).toBeDefined()
})

test('the branch filter asks the server for that branch', async () => {
  const { calls } = adminBackend()
  const user = userEvent.setup()
  await asAdmin()
  await screen.findByRole('table')
  await screen.findByRole('option', { name: 'Central' })
  await user.selectOptions(screen.getByLabelText('Sucursal'), 'Central')
  await waitFor(() => expect(calls.some((c) => c.url === '/api/admin/dashboard?branchId=1')).toBe(true))
})

test('a company with no active employees shows the empty message and no table', async () => {
  adminBackend({ dash: () => jsonResponse(dashboard({ rows: [] })) })
  await asAdmin()
  expect(await screen.findByText('Aún no hay empleados con sucursal asignada.')).toBeDefined()
  expect(screen.queryByRole('table')).toBeNull()
})

test('a future date explains that there are no entries yet', async () => {
  adminBackend({ dash: () => jsonResponse(dashboard({ date: '2026-10-08' })) })
  await asAdmin('/admin?date=2026-10-08')
  expect(await screen.findByText('Fecha futura: aún no hay marcas.')).toBeDefined()
})

test('a malformed date or branch in the URL is ignored', async () => {
  const { calls } = adminBackend()
  await asAdmin('/admin?date=hoy&branch=-3')
  await screen.findByRole('table')
  expect(calls.find((c) => c.url.startsWith('/api/admin/dashboard'))?.url).toBe('/api/admin/dashboard')
})

test('below 768 px the attendance is a list of cards, not a table, and keeps the actions', async () => {
  narrowScreen()
  const user = userEvent.setup()
  adminBackend()
  await asAdmin()
  const list = await screen.findByRole('list', { name: /Asistencia del Miércoles, 7 de octubre/ })
  expect(screen.queryByRole('table')).toBeNull()
  const cards = within(list).getAllByRole('article')
  expect(cards).toHaveLength(4)
  expect(within(cards[1]).getByText('Luis Vargas')).toBeDefined()
  expect(within(cards[1]).getByText('Fuera del área')).toBeDefined()
  expect(within(cards[3]).getByRole('button', { name: 'Registrar entrada de Diego Soto' })).toBeDefined()

  await user.click(within(cards[1]).getByRole('button', { name: /GPS a 420 m/ }))
  expect(await screen.findByRole('dialog')).toBeDefined()
})

test('focus returns to the button that opened the evidence dialog and the record dialog', async () => {
  adminBackend()
  const user = userEvent.setup()
  await asAdmin()

  const evidence = await screen.findByRole('button', { name: /GPS a 420 m/ })
  await user.click(evidence)
  const dialog = await screen.findByRole('dialog')
  await user.click(within(dialog).getByRole('button', { name: 'Cerrar' }))
  await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
  expect(document.activeElement).toBe(evidence)

  const record = screen.getByRole('button', { name: 'Registrar entrada de Diego Soto' })
  await user.click(record)
  const create = await screen.findByRole('dialog')
  await user.click(within(create).getByRole('button', { name: 'Cancelar' }))
  await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
  expect(document.activeElement).toBe(record)
})

test('the announcer says nothing on the first load and announces a date change', async () => {
  adminBackend({ dash: (url) => jsonResponse(dashboard({ date: url.includes('date=2026-10-06') ? '2026-10-06' : '2026-10-07' })) })
  const user = userEvent.setup()
  await asAdmin()
  await screen.findByRole('table')
  const live = () => Array.from(document.querySelectorAll('[aria-live]')).map((n) => n.textContent ?? '').join('|')
  expect(live()).not.toMatch(/cargada/)
  await user.click(screen.getByRole('button', { name: 'Día anterior' }))
  await waitFor(() => expect(live()).toMatch(/Asistencia del .* cargada\. 4 empleados\./))
})
