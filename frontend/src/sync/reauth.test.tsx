import { screen, waitFor, within } from '@testing-library/react'
import userEvent, { type UserEvent } from '@testing-library/user-event'
import { describe, expect, test, vi } from 'vitest'
import { getSession } from '../auth/session'
import { enqueue, listAll } from '../storage/outbox'
import { baselineJpegBytes, entryResponse, jsonResponse, loginResponse, problemResponse, today } from '../test/fixtures'
import { renderApp, seedSession, stubFetch } from '../test/appHarness'

const PIN = '48207315'

async function queueClockOut(clientUuid = 'cu-out') {
  await enqueue({
    employeeId: 7,
    type: 'CLOCK_OUT',
    clientUuid,
    deviceTime: '2026-10-07T23:03:00.000Z',
    photo: baselineJpegBytes().buffer as ArrayBuffer,
    photoType: 'image/jpeg',
    createdAt: new Date().toISOString(),
  })
}

async function pressPin(user: UserEvent, pin = PIN) {
  for (const digit of pin) await user.click(screen.getByRole('button', { name: digit }))
}

type Posted = { auth: string | null; uuid: string | null }

function server(over: { login?: () => Response; post?: (auth: string | null) => Response } = {}) {
  const posts: Posted[] = []
  const logins: unknown[] = []
  const logouts: (string | null)[] = []
  stubFetch(
    (url) => (url === '/api/time-entries/today' ? jsonResponse(today({ nextAction: 'CLOCK_OUT' })) : undefined),
    (url, init) => {
      if (url !== '/api/auth/login') return undefined
      logins.push(JSON.parse(init!.body as string))
      return over.login ? over.login() : jsonResponse(loginResponse({ token: 'token-2' }))
    },
    (url, init) => {
      if (url !== '/api/time-entries' || init?.method !== 'POST') return undefined
      const headers = new Headers(init.headers)
      const form = init.body as FormData
      posts.push({ auth: headers.get('Authorization'), uuid: form.get('clientUuid') as string })
      return over.post ? over.post(headers.get('Authorization')) : jsonResponse(entryResponse({ type: 'CLOCK_OUT', clientUuid: form.get('clientUuid') as string }), 201)
    },
    (url, init) => {
      if (url !== '/api/auth/logout') return undefined
      logouts.push(new Headers(init!.headers).get('Authorization'))
      return new Response(null, { status: 204 })
    },
  )
  return { posts, logins, logouts }
}

describe('token expired with an entry waiting', () => {
  test('the PIN dialog opens with the codes read-only; the same entry is then sent with the same clientUuid', async () => {
    await seedSession({ expiresAt: Date.now() - 1000 })
    await queueClockOut('cu-keep')
    const { posts, logins } = server()
    const user = userEvent.setup()
    renderApp('/')

    const dialog = await screen.findByRole('dialog', { name: 'Ingresa tu PIN' })
    expect(within(dialog).getByText('María Pérez')).toBeDefined()
    expect(within(dialog).getByText('DEMO')).toBeDefined()
    expect(within(dialog).getByText('1001')).toBeDefined()
    expect(dialog.querySelector('input')).toBeNull()
    expect(posts).toHaveLength(0)
    expect(await listAll()).toHaveLength(1)

    await pressPin(user)
    await user.click(within(dialog).getByRole('button', { name: 'Ingresar' }))

    await waitFor(() => expect(posts).toHaveLength(1))
    expect(logins).toEqual([{ companyCode: 'DEMO', employeeCode: '1001', pin: PIN }])
    expect(posts[0]).toEqual({ auth: 'Bearer token-2', uuid: 'cu-keep' })
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(await listAll()).toEqual([])
    // Announcements are read one after another: "signed in" first, then the delivery.
    await waitFor(() => expect(screen.getByRole('status').textContent).toContain('Salida registrada'), { timeout: 4000 })
    expect(JSON.stringify({ ...sessionStorage })).not.toContain(PIN)
  })

  test('a wrong PIN keeps the dialog and the entry, clears the PIN and shows the credentials text', async () => {
    await seedSession({ expiresAt: Date.now() - 1000 })
    await queueClockOut()
    server({ login: () => problemResponse(401, 'INVALID_CREDENTIALS') })
    const user = userEvent.setup()
    renderApp('/')
    const dialog = await screen.findByRole('dialog', { name: 'Ingresa tu PIN' })
    await pressPin(user)
    await user.click(within(dialog).getByRole('button', { name: 'Ingresar' }))

    const alert = await within(dialog).findByRole('alert')
    expect(alert.textContent).toContain('Código o PIN incorrectos')
    expect(document.activeElement).toBe(alert)
    expect(dialog.querySelectorAll('.pin-dot--filled')).toHaveLength(0)
    expect(await listAll()).toHaveLength(1)
    expect(getSession().needsReauth).toBe(true)
  })

  test('a PIN that belongs to another employee is refused as wrong credentials', async () => {
    await seedSession({ expiresAt: Date.now() - 1000 })
    await queueClockOut()
    const { posts } = server({ login: () => jsonResponse(loginResponse({ token: 'intruder', employee: { ...loginResponse().employee, id: 99 } })) })
    const user = userEvent.setup()
    renderApp('/')
    const dialog = await screen.findByRole('dialog', { name: 'Ingresa tu PIN' })
    await pressPin(user)
    await user.click(within(dialog).getByRole('button', { name: 'Ingresar' }))
    expect((await within(dialog).findByRole('alert')).textContent).toContain('Código o PIN incorrectos')
    expect(posts).toHaveLength(0)
    expect(getSession().token).toBeNull()
  })

  test('"Más tarde" closes it; the counter becomes a button that reopens it', async () => {
    await seedSession({ expiresAt: Date.now() - 1000 })
    await queueClockOut()
    server()
    const user = userEvent.setup()
    renderApp('/')
    const dialog = await screen.findByRole('dialog', { name: 'Ingresa tu PIN' })
    await user.click(within(dialog).getByRole('button', { name: 'Más tarde' }))
    expect(screen.queryByRole('dialog')).toBeNull()

    await user.click(screen.getByRole('button', { name: 'Ingresa tu PIN para enviar' }))
    expect(await screen.findByRole('dialog', { name: 'Ingresa tu PIN' })).toBeDefined()
  })

  test('Escape closes it like "Más tarde" and focus returns to the page', async () => {
    await seedSession({ expiresAt: Date.now() - 1000 })
    await queueClockOut()
    server()
    renderApp('/')
    const dialog = await screen.findByRole('dialog', { name: 'Ingresa tu PIN' })
    dialog.dispatchEvent(new Event('cancel', { cancelable: true }))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(getSession().needsReauth).toBe(true)
  })

  test('"¿No eres tú?" opens the sign-out confirmation, which says the entry stays on the phone; confirming wipes the identity but keeps the entry', async () => {
    await seedSession({ expiresAt: Date.now() - 1000 })
    await queueClockOut()
    const { logouts } = server()
    const user = userEvent.setup()
    renderApp('/')
    const dialog = await screen.findByRole('dialog', { name: 'Ingresa tu PIN' })
    await user.click(within(dialog).getByRole('button', { name: '¿No eres tú?' }))

    const confirm = await screen.findByRole('dialog', { name: '¿Cerrar sesión?' })
    expect(within(confirm).getByText('Tienes 1 marca sin enviar. Se queda guardada en este teléfono y se enviará cuando vuelvas a iniciar sesión.')).toBeDefined()
    await user.click(within(confirm).getByRole('button', { name: 'Cerrar sesión' }))

    await screen.findByRole('heading', { name: 'Iniciar sesión' })
    expect(await listAll()).toHaveLength(1)
    expect(getSession().identity).toBeNull()
    // The token was already gone, so the server was not called.
    expect(logouts).toEqual([])
  })
})

describe('a 401 from the server', () => {
  test('keeps the entry, asks for the PIN and later resends with the same clientUuid', async () => {
    await seedSession()
    await queueClockOut('cu-401')
    let first = true
    const { posts } = server({
      post: () => {
        if (first) {
          first = false
          return new Response(null, { status: 401 })
        }
        return jsonResponse(entryResponse({ type: 'CLOCK_OUT', clientUuid: 'cu-401' }), 201)
      },
    })
    const user = userEvent.setup()
    renderApp('/')

    const dialog = await screen.findByRole('dialog', { name: 'Ingresa tu PIN' })
    expect(posts).toHaveLength(1)
    expect(await listAll()).toHaveLength(1)

    await pressPin(user)
    await user.click(within(dialog).getByRole('button', { name: 'Ingresar' }))
    await waitFor(() => expect(posts).toHaveLength(2))
    expect(posts.map((p) => p.uuid)).toEqual(['cu-401', 'cu-401'])
    await waitFor(async () => expect(await listAll()).toEqual([]))
  })
})

describe('cold open without a token', () => {
  test('online, the PIN dialog opens at once so the state can be loaded', async () => {
    await seedSession({ token: false })
    server()
    renderApp('/')
    expect(await screen.findByRole('dialog', { name: 'Ingresa tu PIN' })).toBeDefined()
  })

  test('offline, nothing opens and the screen works from the identity', async () => {
    vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(false)
    await seedSession({ token: false })
    server()
    renderApp('/')
    await screen.findByRole('heading', { name: 'Hola, María' })
    expect(screen.queryByRole('dialog')).toBeNull()
  })
})
