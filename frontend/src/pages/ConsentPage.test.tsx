import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, test, vi } from 'vitest'
import { getSession } from '../auth/session'
import { jsonResponse, problemResponse, today } from '../test/fixtures'
import { renderApp, seedSession, stubFetch } from '../test/appHarness'

function backend(consent: () => Response = () => new Response(null, { status: 204 })) {
  return stubFetch(
    (url, init) => (url === '/api/me/consent' && init?.method === 'POST' ? consent() : undefined),
    (url) => (url === '/api/time-entries/today' ? jsonResponse(today()) : undefined),
  )
}

describe('content', () => {
  test('shows the Ley 8968 text for the company, the version and no hidden sections', async () => {
    await seedSession({ consentPending: true })
    backend()
    renderApp('/consent')
    expect(await screen.findByRole('heading', { level: 1, name: 'Tus datos y tu consentimiento' })).toBeDefined()
    expect(screen.getByText(/DEMO usa esta aplicación/)).toBeDefined()
    expect(screen.getAllByRole('heading', { level: 2 })).toHaveLength(6)
    expect(screen.getByText(/se conservan 12 meses y luego se eliminan/)).toBeDefined()
    expect(screen.getByText(/Envía tu solicitud al administrador de tu empresa/)).toBeDefined()
    expect(screen.getByText('Solo los administradores autorizados de DEMO.')).toBeDefined()
    expect(screen.getByText('Sin tu consentimiento no puedes marcar entrada ni salida.')).toBeDefined()
    expect(screen.getByText('Versión del texto: 2026-10-v1')).toBeDefined()
  })

  test('is bilingual', async () => {
    await seedSession({ consentPending: true })
    backend()
    renderApp('/consent', 'en')
    expect(await screen.findByRole('heading', { level: 1, name: 'Your data and your consent' })).toBeDefined()
    expect(screen.getByText(/kept for 12 months and then deleted/)).toBeDefined()
    expect(screen.getByRole('button', { name: 'I accept' })).toBeDefined()
    expect(screen.getByText('Text version: 2026-10-v1')).toBeDefined()
  })

  test('focus starts on the h1', async () => {
    await seedSession({ consentPending: true })
    backend()
    renderApp('/consent')
    const h1 = await screen.findByRole('heading', { level: 1 })
    expect(document.activeElement).toBe(h1)
  })
})

describe('accepting', () => {
  test('posts to /api/me/consent with the token and lands on the main screen', async () => {
    await seedSession({ consentPending: true })
    const { mock } = backend()
    const user = userEvent.setup()
    renderApp('/consent')
    await user.click(await screen.findByRole('button', { name: 'Acepto' }))

    await screen.findByRole('heading', { name: 'Hola, María' })
    const call = mock.mock.calls.find(([url]) => String(url) === '/api/me/consent')!
    expect(call[1]!.method).toBe('POST')
    expect(new Headers(call[1]!.headers).get('Authorization')).toBe('Bearer token-1')
    expect(getSession().consentPending).toBe(false)
  })

  test('a server error is shown as an alert and the buttons come back', async () => {
    await seedSession({ consentPending: true })
    backend(() => problemResponse(500, 'INTERNAL_ERROR'))
    const user = userEvent.setup()
    renderApp('/consent')
    await user.click(await screen.findByRole('button', { name: 'Acepto' }))
    const alert = await screen.findByRole('alert')
    expect(alert.textContent).toContain('Ocurrió un error inesperado')
    expect(document.activeElement).toBe(alert)
    expect(screen.getByRole('button', { name: 'Acepto' }).getAttribute('aria-disabled')).toBe('false')
  })

  test('offline: accept is disabled with a reason, decline still works', async () => {
    vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(false)
    await seedSession({ consentPending: true })
    const { mock } = backend()
    const user = userEvent.setup()
    renderApp('/consent')
    const accept = await screen.findByRole('button', { name: 'Acepto' })
    expect(accept.getAttribute('aria-disabled')).toBe('true')
    expect(accept.getAttribute('aria-describedby')).toBe('consent-offline')
    expect(screen.getByText(/Necesitas conexión para aceptar/)).toBeDefined()
    await user.click(accept)
    expect(mock.mock.calls.some(([url]) => String(url) === '/api/me/consent')).toBe(false)
    await user.click(screen.getByRole('button', { name: 'No acepto' }))
    expect(screen.getByRole('heading', { name: 'No se puede continuar sin tu consentimiento' })).toBeDefined()
  })
})

describe('declining', () => {
  test('explains, offers sign-out and back, sends nothing, and moves focus to the explanation', async () => {
    await seedSession({ consentPending: true })
    const { mock } = backend()
    const user = userEvent.setup()
    renderApp('/consent')
    await user.click(await screen.findByRole('button', { name: 'No acepto' }))

    const heading = screen.getByRole('heading', { name: 'No se puede continuar sin tu consentimiento' })
    expect(document.activeElement).toBe(heading)
    expect(screen.getByRole('region', { name: 'No se puede continuar sin tu consentimiento' })).toBeDefined()
    expect(mock.mock.calls.some(([url]) => String(url) === '/api/me/consent')).toBe(false)
    expect(screen.queryByRole('button', { name: 'Acepto' })).toBeNull()

    await user.click(screen.getByRole('button', { name: 'Volver' }))
    expect(screen.getByRole('button', { name: 'Acepto' })).toBeDefined()
  })

  test('sign-out from the declined panel asks to confirm and returns to the login page', async () => {
    await seedSession({ consentPending: true })
    backend()
    const user = userEvent.setup()
    renderApp('/consent')
    await user.click(await screen.findByRole('button', { name: 'No acepto' }))
    const panel = screen.getByRole('region')
    await user.click(within(panel).getByRole('button', { name: 'Cerrar sesión' }))
    const dialog = await screen.findByRole('dialog', { name: '¿Cerrar sesión?' })
    await user.click(within(dialog).getByRole('button', { name: 'Cerrar sesión' }))
    await screen.findByRole('heading', { name: 'Iniciar sesión' })
    expect(getSession().identity).toBeNull()
  })
})
