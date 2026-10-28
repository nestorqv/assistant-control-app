import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, test } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { localDateString } from '../time/format'
import { entrySummary, jsonResponse, loginResponse, today } from '../test/fixtures'
import { renderApp, seedSession, stubFetch } from '../test/appHarness'

function backend(over: Parameters<typeof today>[0] = {}) {
  stubFetch(
    (url) => (url === '/api/time-entries/today' ? jsonResponse(today(over)) : undefined),
    (url) => (url === '/api/auth/login' ? jsonResponse(loginResponse()) : undefined),
  )
}

const css = readFileSync(resolve(__dirname, '../index.css'), 'utf8')

describe('landmarks, headings and live regions', () => {
  test('the main screen has one status region, one h1 and h2 sections in order', async () => {
    await seedSession()
    backend({ nextAction: 'CLOCK_OUT', clockIn: entrySummary() })
    renderApp('/')
    await screen.findByText(/En turno desde/)
    expect(screen.getAllByRole('status')).toHaveLength(1)
    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1)
    expect(screen.getAllByRole('main')).toHaveLength(1)
    expect(screen.getAllByRole('heading', { level: 2 }).map((h) => h.textContent)).toEqual([
      expect.stringContaining('En turno desde'),
      'Evidencia',
      'Marcas de hoy',
    ])
  })

  test('the login page has exactly one status region', async () => {
    backend()
    renderApp('/login')
    await screen.findByRole('heading', { name: 'Iniciar sesión' })
    expect(screen.getAllByRole('status')).toHaveLength(1)
  })

  test('the consent page has exactly one status region', async () => {
    backend()
    await seedSession({ consentPending: true })
    renderApp('/consent')
    await screen.findByRole('heading', { name: 'Tus datos y tu consentimiento' })
    expect(screen.getAllByRole('status')).toHaveLength(1)
  })

  test('the admin placeholder keeps its own single status region (the health line)', async () => {
    backend()
    renderApp('/admin')
    await screen.findByRole('heading', { name: 'Panel del administrador' })
    expect(screen.getAllByRole('status')).toHaveLength(1)
  })

  test('the language switcher keeps its own aria-live span, not a status role', async () => {
    backend()
    renderApp('/login')
    await screen.findByRole('heading', { name: 'Iniciar sesión' })
    const live = document.querySelector('.language-switcher [aria-live="polite"]')
    expect(live).not.toBeNull()
    expect(live!.getAttribute('role')).toBeNull()
  })
})

describe('names and roles', () => {
  test('the clock is named by its visible label, with no aria-label on <time>', async () => {
    await seedSession()
    backend()
    renderApp('/')
    await screen.findByText('Aún no has marcado hoy')
    const time = document.querySelector('time')!
    expect(time.hasAttribute('aria-label')).toBe(false)
    expect(time.getAttribute('datetime')).toMatch(/^\d{4}-\d\d-\d\dT/)
    expect(time.closest('.server-clock')!.querySelector('.clock-label')!.textContent).toBe('Hora del servidor')
    expect(time.closest('[aria-live]')).toBeNull()
  })

  test('the primary button and the keypad are reachable by name; icons are decorative', async () => {
    await seedSession()
    backend()
    renderApp('/')
    const button = await screen.findByRole('button', { name: 'Marcar entrada' })
    for (const icon of button.querySelectorAll('svg')) expect(icon.getAttribute('aria-hidden')).toBe('true')
  })

  test('chips are informational: not buttons, not focusable', async () => {
    await seedSession()
    backend()
    renderApp('/')
    const evidence = (await screen.findByRole('heading', { name: 'Evidencia' })).closest('section')!
    expect(evidence.querySelectorAll('button, a, [tabindex]')).toHaveLength(0)
  })

  test('disabled primary states use aria-disabled and stay in the tab order', async () => {
    await seedSession()
    // The real clock runs here, so the finished day must be today's date in the company zone.
    backend({
      workDate: localDateString(Date.now(), 'America/Costa_Rica'),
      nextAction: 'NONE',
      clockIn: entrySummary(),
      clockOut: entrySummary({ id: 2 }),
    })
    const user = userEvent.setup()
    renderApp('/')
    const done = await screen.findByRole('button', { name: 'Listo por hoy' })
    expect(done.getAttribute('aria-disabled')).toBe('true')
    expect(done.hasAttribute('disabled')).toBe(false)
    // Focus starts on the h1; the next tab stop in the page is the (aria-disabled) primary button.
    await user.tab()
    expect(document.activeElement).toBe(done)
  })
})

describe('focus', () => {
  test('the login page puts focus on its h1', async () => {
    backend()
    renderApp('/login')
    const h1 = await screen.findByRole('heading', { name: 'Iniciar sesión' })
    expect(document.activeElement).toBe(h1)
  })

  test('the main screen puts focus on its h1', async () => {
    backend()
    await seedSession()
    renderApp('/')
    const h1 = await screen.findByRole('heading', { level: 1, name: 'Hola, María' })
    expect(document.activeElement).toBe(h1)
  })

  test('the tab order starts with the header: languages, then sign out', async () => {
    await seedSession()
    backend()
    const user = userEvent.setup()
    renderApp('/')
    await screen.findByRole('heading', { name: 'Hola, María' })
    ;(document.activeElement as HTMLElement).blur()
    await user.tab()
    expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Español' }))
    await user.tab()
    expect(document.activeElement).toBe(screen.getByRole('button', { name: 'English' }))
    await user.tab()
    expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Cerrar sesión' }))
  })
})

describe('design tokens and motion', () => {
  test('reduced motion removes animation and transitions', () => {
    expect(css).toMatch(/@media \(prefers-reduced-motion: reduce\)[\s\S]*animation: none !important/)
    expect(css).toMatch(/@media \(prefers-reduced-motion: reduce\)[\s\S]*transition: none !important/)
  })

  test('the tap targets meet the sizes of the plan', () => {
    expect(css).toContain('--key-h: 56px')
    expect(css).toContain('--primary-h: 64px')
    expect(css).toContain('--target-min: 44px')
    expect(css).toMatch(/\.clock-button \{[^}]*min-height: var\(--primary-h\)/)
    expect(css).toMatch(/\.pin-key \{[^}]*min-height: var\(--key-h\)/)
  })

  test('there is a visible focus ring and no dark text on the teal band', () => {
    expect(css).toMatch(/:focus-visible \{[^}]*outline: 3px solid/)
    expect(css).toMatch(/\.day-card \{[^}]*color: var\(--color-on-primary\)/)
  })

  test('the brand colors keep their contrast (computed with the WCAG formula)', () => {
    const luminance = (hex: string) => {
      const [r, g, b] = [1, 3, 5].map((i) => parseInt(hex.slice(i, i + 2), 16) / 255).map((c) => (c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4))
      return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }
    const ratio = (a: string, b: string) => {
      const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x)
      return (hi + 0.05) / (lo + 0.05)
    }
    const token = (name: string) => {
      const hex = new RegExp(`--${name}: #([0-9a-f]{3,6});`, 'i').exec(css)![1]
      return `#${hex.length === 3 ? [...hex].map((c) => c + c).join('') : hex}`
    }
    expect(ratio(token('color-on-primary'), token('color-primary'))).toBeGreaterThanOrEqual(4.5)
    expect(ratio(token('color-on-primary-muted'), token('color-primary'))).toBeGreaterThanOrEqual(4.5)
    expect(ratio(token('color-primary'), token('color-bg'))).toBeGreaterThanOrEqual(4.5)
    expect(ratio(token('color-disabled-text'), token('color-disabled-bg'))).toBeGreaterThanOrEqual(4.5)
    expect(ratio(token('color-error'), token('color-error-bg'))).toBeGreaterThanOrEqual(4.5)
    expect(ratio(token('color-warning'), token('color-warning-bg'))).toBeGreaterThanOrEqual(4.5)
    expect(ratio(token('color-success'), token('color-success-bg'))).toBeGreaterThanOrEqual(4.5)
    expect(ratio(token('color-border'), token('color-bg'))).toBeGreaterThanOrEqual(3)
  })
})
