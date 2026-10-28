import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, test, vi } from 'vitest'
import { getSession, SESSION_KEY } from '../auth/session'
import { enqueue, listAll } from '../storage/outbox'
import { baselineJpegBytes, jsonResponse, today } from '../test/fixtures'
import { renderApp, seedSession, stubFetch } from '../test/appHarness'

function backend(logout: () => Response = () => new Response(null, { status: 204 })) {
  const logouts: (string | null)[] = []
  const { mock } = stubFetch(
    (url) => (url === '/api/time-entries/today' ? jsonResponse(today()) : undefined),
    (url, init) => {
      if (url !== '/api/auth/logout') return undefined
      logouts.push(new Headers(init!.headers).get('Authorization'))
      return logout()
    },
  )
  return { logouts, mock }
}

async function queueOne() {
  await enqueue({
    employeeId: 7,
    type: 'CLOCK_IN',
    clientUuid: 'cu-1',
    deviceTime: '2026-10-07T13:58:00.000Z',
    photo: baselineJpegBytes().buffer as ArrayBuffer,
    photoType: 'image/jpeg',
    createdAt: new Date().toISOString(),
  })
}

const openConfirm = async (user: ReturnType<typeof userEvent.setup>, role: 'dialog' | 'alertdialog' = 'dialog') => {
  await user.click(await screen.findByRole('button', { name: 'Cerrar sesión' }))
  return screen.findByRole(role, { name: '¿Cerrar sesión?' })
}

describe('sign out', () => {
  test('always asks first; confirming revokes on the server (with the token) and wipes the device', async () => {
    await seedSession()
    const { logouts } = backend()
    const user = userEvent.setup()
    renderApp('/')
    const dialog = await openConfirm(user)
    expect(within(dialog).getByText('Tendrás que ingresar tu PIN de nuevo.')).toBeDefined()
    expect(logouts).toEqual([])
    expect(document.activeElement).toBe(within(dialog).getByRole('button', { name: 'Cancelar' }))

    await user.click(within(dialog).getByRole('button', { name: 'Cerrar sesión' }))

    await screen.findByRole('heading', { name: 'Iniciar sesión' })
    expect(logouts).toEqual(['Bearer token-1'])
    expect(sessionStorage.getItem(SESSION_KEY)).toBeNull()
    expect(getSession()).toMatchObject({ identity: null, token: null })
  })

  test('cancelling keeps everything and returns focus to the header button', async () => {
    await seedSession()
    const { logouts } = backend()
    const user = userEvent.setup()
    renderApp('/')
    const dialog = await openConfirm(user)
    await user.click(within(dialog).getByRole('button', { name: 'Cancelar' }))
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Cerrar sesión' }))
    expect(logouts).toEqual([])
    expect(getSession().token).toBe('token-1')
  })

  test('Esc cancels', async () => {
    await seedSession()
    backend()
    const user = userEvent.setup()
    renderApp('/')
    const dialog = await openConfirm(user)
    dialog.dispatchEvent(new Event('cancel', { cancelable: true }))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
  })

  test('with unsent entries it says they stay on the phone for their owner, keeps them after signing out, and the safe action is focused', async () => {
    vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(false)
    await seedSession()
    await queueOne()
    backend()
    const user = userEvent.setup()
    renderApp('/')
    const dialog = await openConfirm(user)
    expect(dialog.getAttribute('role')).not.toBe('alertdialog')
    expect(within(dialog).getByText('Tienes 1 marca sin enviar. Se queda guardada en este teléfono y se enviará cuando vuelvas a iniciar sesión.')).toBeDefined()
    expect(document.activeElement).toBe(within(dialog).getByRole('button', { name: 'Cancelar' }))
    expect(await listAll()).toHaveLength(1)

    await user.click(within(dialog).getByRole('button', { name: 'Cerrar sesión' }))
    await screen.findByRole('heading', { name: 'Iniciar sesión' })
    expect(await listAll()).toHaveLength(1)
    expect(getSession().identity).toBeNull()
  })

  test('a failing server call (network, 401) does not stop the local wipe', async () => {
    await seedSession()
    stubFetch((url) => (url === '/api/time-entries/today' ? jsonResponse(today()) : undefined))
    const user = userEvent.setup()
    renderApp('/')
    const dialog = await openConfirm(user)
    await user.click(within(dialog).getByRole('button', { name: 'Cerrar sesión' }))
    await screen.findByRole('heading', { name: 'Iniciar sesión' })
    expect(getSession().identity).toBeNull()
  })

  test('a 401 from the logout call is ignored', async () => {
    await seedSession()
    backend(() => new Response(null, { status: 401 }))
    const user = userEvent.setup()
    renderApp('/')
    const dialog = await openConfirm(user)
    await user.click(within(dialog).getByRole('button', { name: 'Cerrar sesión' }))
    await screen.findByRole('heading', { name: 'Iniciar sesión' })
    expect(getSession().identity).toBeNull()
  })

  test('English labels', async () => {
    await seedSession()
    backend()
    const user = userEvent.setup()
    renderApp('/', 'en')
    await user.click(await screen.findByRole('button', { name: 'Sign out' }))
    const dialog = await screen.findByRole('dialog', { name: 'Sign out?' })
    expect(within(dialog).getByText('You will need to enter your PIN again.')).toBeDefined()
  })
})
