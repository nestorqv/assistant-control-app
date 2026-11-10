import { screen, waitFor, within } from '@testing-library/react'
import userEvent, { type UserEvent } from '@testing-library/user-event'
import { describe, expect, test, vi } from 'vitest'
import { getSession } from '../auth/session'
import { enqueue, listAll } from '../storage/outbox'
import { baselineJpegBytes, entrySummary, jsonResponse, loginResponse, problemResponse, today } from '../test/fixtures'
import { adminBackend } from '../test/adminBackend'
import { renderApp, seedSession, stubFetch } from '../test/appHarness'

const PIN = '48207315'

async function pressPin(user: UserEvent, pin: string) {
  for (const digit of pin) await user.click(screen.getByRole('button', { name: digit }))
}

async function fillCodes(user: UserEvent, company = 'demo', employee = '1001') {
  const companyField = screen.getByLabelText('Código de empresa')
  await user.clear(companyField)
  await user.type(companyField, company)
  await user.type(screen.getByLabelText('Código de empleado'), employee)
}

const submit = () => screen.getByRole('button', { name: 'Ingresar' })
const filledDots = () => document.querySelectorAll('.pin-dot--filled').length

function backend(login: () => Response | Promise<Response>) {
  return stubFetch(
    (url) => (url === '/api/auth/login' ? login() : undefined),
    (url) => (url === '/api/time-entries/today' ? jsonResponse(today({ clockIn: entrySummary() })) : undefined),
  )
}

describe('the form', () => {
  test('starts empty with 8 empty dots, a disabled-looking submit and no field hints', async () => {
    backend(() => jsonResponse(loginResponse()))
    renderApp('/login')
    await screen.findByRole('heading', { name: 'Iniciar sesión' })
    expect(filledDots()).toBe(0)
    expect(submit().getAttribute('aria-disabled')).toBe('true')
    expect(screen.getByLabelText('Código de empresa').getAttribute('autocapitalize')).toBe('characters')
    expect(screen.getByLabelText('Código de empleado').getAttribute('autocomplete')).toBe('off')
  })

  test('the company code is pre-filled from the last login', async () => {
    localStorage.setItem('attendance.lastCompanyCode', 'ACME')
    backend(() => jsonResponse(loginResponse()))
    renderApp('/login')
    expect((await screen.findByLabelText('Código de empresa') as HTMLInputElement).value).toBe('ACME')
  })

  test('the submit button becomes enabled only at 8 digits and nothing is auto-submitted', async () => {
    const { mock } = backend(() => jsonResponse(loginResponse()))
    const user = userEvent.setup()
    renderApp('/login')
    await fillCodes(user)
    await pressPin(user, PIN.slice(0, 7))
    expect(submit().getAttribute('aria-disabled')).toBe('true')
    await pressPin(user, PIN.slice(7))
    expect(submit().getAttribute('aria-disabled')).toBe('false')
    expect(mock.mock.calls.some(([url]) => String(url) === '/api/auth/login')).toBe(false)
  })

  test('empty or malformed codes show an inline error and focus the first invalid field', async () => {
    const { mock } = backend(() => jsonResponse(loginResponse()))
    const user = userEvent.setup()
    renderApp('/login')
    await screen.findByRole('heading', { name: 'Iniciar sesión' })
    await user.type(screen.getByLabelText('Código de empleado'), 'a b!')
    await pressPin(user, PIN)
    await user.click(submit())

    const company = screen.getByLabelText('Código de empresa')
    expect(document.activeElement).toBe(company)
    expect(company.getAttribute('aria-invalid')).toBe('true')
    expect(screen.getByText('Escriba su código de empresa.')).toBeDefined()
    expect(screen.getByText('Use solo letras, números y guiones (máximo 20).')).toBeDefined()
    expect(mock.mock.calls.some(([url]) => String(url) === '/api/auth/login')).toBe(false)
  })
})

describe('signing in', () => {
  test('sends the codes uppercased with the PIN, then goes to the main screen', async () => {
    const { mock } = backend(() => jsonResponse(loginResponse()))
    const user = userEvent.setup()
    renderApp('/login')
    await fillCodes(user, 'demo', 'e-1001')
    await pressPin(user, PIN)
    await user.click(submit())

    await screen.findByRole('heading', { name: 'Hola, María' })
    const call = mock.mock.calls.find(([url]) => String(url) === '/api/auth/login')!
    const sent = JSON.parse(call[1]!.body as string)
    expect({ ...sent, pin: sent.pin === PIN }).toEqual({ companyCode: 'DEMO', employeeCode: 'E-1001', pin: true })
    expect(new Headers(call[1]!.headers).get('Content-Type')).toBe('application/json')
    expect(new Headers(call[1]!.headers).has('Authorization')).toBe(false)
    expect(getSession().token).toBe('token-1')
  })

  test('the login request carries the language chosen in the app', async () => {
    const { mock } = backend(() => jsonResponse(loginResponse()))
    const user = userEvent.setup()
    renderApp('/login', 'en')
    await user.type(await screen.findByLabelText('Company code'), 'demo')
    await user.type(screen.getByLabelText('Employee code'), '1001')
    await pressPin(user, PIN)
    await user.click(screen.getByRole('button', { name: 'Sign in' }))
    await screen.findByRole('heading', { name: 'Hello, María' })
    const call = mock.mock.calls.find(([url]) => String(url) === '/api/auth/login')!
    expect(new Headers(call[1]!.headers).get('Accept-Language')).toBe('en')
  })

  test('a pending consent goes to /consent', async () => {
    backend(() => jsonResponse(loginResponse({ employee: { ...loginResponse().employee, consentPending: true } })))
    const user = userEvent.setup()
    renderApp('/login')
    await fillCodes(user)
    await pressPin(user, PIN)
    await user.click(submit())
    await screen.findByRole('heading', { name: 'Sus datos y su consentimiento' })
  })

  test('the PIN is not in any storage after signing in', async () => {
    backend(() => jsonResponse(loginResponse()))
    const user = userEvent.setup()
    renderApp('/login')
    await fillCodes(user)
    await pressPin(user, PIN)
    await user.click(submit())
    await screen.findByRole('heading', { name: 'Hola, María' })
    expect(JSON.stringify({ ...sessionStorage, ...localStorage }).includes(PIN), 'the storage carries the PIN').toBe(false)
  })
})

describe('failures', () => {
  test('401 shows the generic credentials text, clears the PIN, keeps the codes and focuses the alert', async () => {
    backend(() => problemResponse(401, 'INVALID_CREDENTIALS'))
    const user = userEvent.setup()
    renderApp('/login')
    await fillCodes(user)
    await pressPin(user, PIN)
    await user.click(submit())

    const alert = await screen.findByRole('alert')
    expect(alert.textContent).toBe('Código o PIN incorrectos. Si falló varias veces, espere 15 minutos o contacte a su administrador.')
    expect(document.activeElement).toBe(alert)
    expect(filledDots()).toBe(0)
    expect((screen.getByLabelText('Código de empresa') as HTMLInputElement).value).toBe('DEMO')
    expect((screen.getByLabelText('Código de empleado') as HTMLInputElement).value).toBe('1001')
    // It never says which field was wrong.
    expect(alert.textContent).not.toMatch(/empleado|empresa/i)
  })

  test('401 in English', async () => {
    backend(() => problemResponse(401, 'INVALID_CREDENTIALS'))
    const user = userEvent.setup()
    renderApp('/login', 'en')
    await user.type(await screen.findByLabelText('Company code'), 'demo')
    await user.type(screen.getByLabelText('Employee code'), '1001')
    await pressPin(user, PIN)
    await user.click(screen.getByRole('button', { name: 'Sign in' }))
    expect((await screen.findByRole('alert')).textContent).toMatch(/^Incorrect code or PIN/)
  })

  test('429 with Retry-After 300 says "5 min" and disables the keypad', async () => {
    backend(() => problemResponse(429, 'TOO_MANY_ATTEMPTS', { 'Retry-After': '300' }))
    const user = userEvent.setup()
    renderApp('/login')
    await fillCodes(user)
    await pressPin(user, PIN)
    await user.click(submit())

    expect((await screen.findByRole('alert')).textContent).toContain('5 min')
    expect(screen.getByRole('button', { name: '1' }).getAttribute('aria-disabled')).toBe('true')
    expect(document.querySelector('.countdown')?.textContent).toBe('5:00')
  })

  test('a network error shows the connection message and keeps the codes', async () => {
    stubFetch()
    const user = userEvent.setup()
    renderApp('/login')
    await fillCodes(user)
    await pressPin(user, PIN)
    await user.click(submit())
    expect((await screen.findByRole('alert')).textContent).toBe('No se pudo conectar con el servidor. Revise su conexión e inténtelo de nuevo.')
    expect(filledDots()).toBe(0)
  })

  test('while the request runs the form is busy and the button shows the progress text', async () => {
    let release!: (response: Response) => void
    backend(() => new Promise<Response>((resolve) => (release = resolve)))
    const user = userEvent.setup()
    renderApp('/login')
    await fillCodes(user)
    await pressPin(user, PIN)
    await user.click(submit())
    const busy = await screen.findByRole('button', { name: 'Ingresando…' })
    expect(busy.getAttribute('aria-disabled')).toBe('true')
    expect(document.querySelector('form')?.getAttribute('aria-busy')).toBe('true')
    release(jsonResponse(loginResponse()))
    await screen.findByRole('heading', { name: 'Hola, María' })
  })
})

describe('offline', () => {
  test('without a connection a note explains it and the submit stays disabled', async () => {
    vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(false)
    backend(() => jsonResponse(loginResponse()))
    const user = userEvent.setup()
    renderApp('/login')
    expect(await screen.findByText('Necesita conexión a internet para iniciar sesión.')).toBeDefined()
    await fillCodes(user)
    await pressPin(user, PIN)
    expect(submit().getAttribute('aria-disabled')).toBe('true')
    expect(submit().getAttribute('aria-describedby')).toBe('login-offline')
  })
})

describe('one identity per device', () => {
  test('signing in as another employee says the first one has unsent entries that stay on the phone, and does not delete them', async () => {
    await seedSession({ token: false })
    await enqueue({
      employeeId: 7,
      type: 'CLOCK_IN',
      clientUuid: 'u1',
      deviceTime: '2026-10-07T13:58:00Z',
      photo: baselineJpegBytes().buffer as ArrayBuffer,
      photoType: 'image/jpeg',
      createdAt: new Date().toISOString(),
    })
    backend(() => jsonResponse(loginResponse({ employee: { ...loginResponse().employee, id: 8, name: 'Luis Mora' } })))
    const user = userEvent.setup()
    renderApp('/login')
    await fillCodes(user, 'demo', '1002')
    await pressPin(user, PIN)
    await user.click(submit())

    const dialog = await screen.findByRole('dialog', { name: '¿Cambiar de empleado?' })
    expect(within(dialog).getByText(/Se quedan guardadas y se enviarán cuando esa persona inicie sesión de nuevo/)).toBeDefined()
    await user.click(within(dialog).getByRole('button', { name: 'Continuar' }))
    await screen.findByRole('heading', { name: 'Hola, Luis' })
    await waitFor(() => expect(getSession().identity?.employeeId).toBe(8))
    const remaining = await listAll()
    expect(remaining).toHaveLength(1)
    expect(remaining[0]).toMatchObject({ employeeId: 7, clientUuid: 'u1', state: 'pending' })
    expect(remaining[0].photo).not.toBeNull()
  })

  test('cancelling keeps the first employee and their queue', async () => {
    await seedSession({ token: false })
    await enqueue({
      employeeId: 7,
      type: 'CLOCK_IN',
      clientUuid: 'u1',
      deviceTime: '2026-10-07T13:58:00Z',
      photo: baselineJpegBytes().buffer as ArrayBuffer,
      photoType: 'image/jpeg',
      createdAt: new Date().toISOString(),
    })
    backend(() => jsonResponse(loginResponse({ employee: { ...loginResponse().employee, id: 8 } })))
    const user = userEvent.setup()
    renderApp('/login')
    await fillCodes(user, 'demo', '1002')
    await pressPin(user, PIN)
    await user.click(submit())
    const dialog = await screen.findByRole('dialog', { name: '¿Cambiar de empleado?' })
    await user.click(within(dialog).getByRole('button', { name: 'Cancelar' }))
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(getSession().identity?.employeeId).toBe(7)
  })
})

describe('an administrator signing in', () => {
  const adminLogin = () =>
    jsonResponse(
      loginResponse({
        employee: { id: 9, name: 'Ana Mora', jobTitle: 'Administración', role: 'ADMIN', consentPending: true },
      }),
    )

  async function signInAsAdmin(path: string) {
    const user = userEvent.setup()
    renderApp(path)
    await fillCodes(user, 'demo', '9001')
    await pressPin(user, PIN)
    await user.click(submit())
  }

  test('lands on the page in ?next even when the consent is pending, and never on /consent', async () => {
    adminBackend({ login: adminLogin })
    await signInAsAdmin('/login?next=/admin/review')
    expect(await screen.findByRole('heading', { level: 1, name: 'Revisión de marcas' })).toBeDefined()
    expect(screen.queryByRole('heading', { name: 'Sus datos y su consentimiento' })).toBeNull()
  })

  test('an external or protocol-relative ?next is ignored and the dashboard opens', async () => {
    adminBackend({ login: adminLogin })
    await signInAsAdmin('/login?next=' + encodeURIComponent('//evil.example'))
    expect(await screen.findByRole('heading', { level: 1, name: 'Asistencia de hoy' })).toBeDefined()
  })

  test('without ?next the dashboard opens', async () => {
    adminBackend({ login: adminLogin })
    await signInAsAdmin('/login')
    expect(await screen.findByRole('heading', { level: 1, name: 'Asistencia de hoy' })).toBeDefined()
  })
})

test('safeAdminNext only accepts internal admin paths', async () => {
  const { safeAdminNext } = await import('./LoginPage')
  expect(safeAdminNext('/admin/review')).toBe('/admin/review')
  expect(safeAdminNext('/admin')).toBe('/admin')
  expect(safeAdminNext('/admin?date=2026-10-07')).toBe('/admin?date=2026-10-07')
  for (const bad of ['//evil.example', 'https://evil.example/admin', '/', '/administrator', '/admin/\\evil.example', '/admin\\\\evil','/employees', null, '']) {
    expect(safeAdminNext(bad)).toBe('/admin')
  }
})
