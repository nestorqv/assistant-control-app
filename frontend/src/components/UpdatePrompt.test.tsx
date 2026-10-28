import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, test, vi } from 'vitest'
import { renderWithI18n } from '../test/renderWithI18n'
import { UpdateBanner } from './UpdateBanner'
import { UpdatePrompt } from './UpdatePrompt'

const hook = vi.hoisted(() => ({
  needRefresh: false,
  updateServiceWorker: vi.fn(async () => undefined),
}))

vi.mock('../pwa/registerSW', () => ({
  useRegisterSW: () => ({
    needRefresh: [hook.needRefresh, () => undefined],
    offlineReady: [false, () => undefined],
    updateServiceWorker: hook.updateServiceWorker,
  }),
}))

beforeEach(() => {
  hook.needRefresh = false
  hook.updateServiceWorker.mockClear()
})

describe('UpdatePrompt', () => {
  test('is hidden unless an update is waiting', () => {
    const { container } = renderWithI18n(<UpdatePrompt needRefresh={false} suppressed={false} onUpdate={() => undefined} onDismiss={() => undefined} />)
    expect(container.textContent).toBe('')
  })

  test('shows the banner without stealing focus or using role=alert', () => {
    renderWithI18n(<UpdatePrompt needRefresh suppressed={false} onUpdate={() => undefined} onDismiss={() => undefined} />)
    expect(screen.getByText('Hay una nueva versión.')).toBeDefined()
    expect(screen.queryByRole('alert')).toBeNull()
    expect(document.activeElement).toBe(document.body)
  })

  test('is hidden while suppressed (capture open or flushing)', () => {
    const { container } = renderWithI18n(<UpdatePrompt needRefresh suppressed onUpdate={() => undefined} onDismiss={() => undefined} />)
    expect(container.textContent).toBe('')
  })

  test('Update shows progress text; Later calls onDismiss', async () => {
    const user = userEvent.setup()
    const onUpdate = vi.fn()
    const onDismiss = vi.fn()
    renderWithI18n(<UpdatePrompt needRefresh suppressed={false} onUpdate={onUpdate} onDismiss={onDismiss} />)
    await user.click(screen.getByRole('button', { name: 'Luego' }))
    expect(onDismiss).toHaveBeenCalled()
    await user.click(screen.getByRole('button', { name: 'Actualizar' }))
    expect(onUpdate).toHaveBeenCalledTimes(1)
    expect(screen.getByRole('button', { name: 'Actualizando…' })).toBeDefined()
  })
})

describe('UpdateBanner (service worker hook)', () => {
  test('hidden by default', () => {
    renderWithI18n(<UpdateBanner busy={false} />)
    expect(screen.queryByText('Hay una nueva versión.')).toBeNull()
  })

  test('needRefresh shows it and "Actualizar" calls updateServiceWorker(true)', async () => {
    hook.needRefresh = true
    const user = userEvent.setup()
    renderWithI18n(<UpdateBanner busy={false} />)
    await user.click(screen.getByRole('button', { name: 'Actualizar' }))
    expect(hook.updateServiceWorker).toHaveBeenCalledWith(true)
  })

  test('hidden while a capture or a flush is busy', () => {
    hook.needRefresh = true
    renderWithI18n(<UpdateBanner busy />)
    expect(screen.queryByText('Hay una nueva versión.')).toBeNull()
  })

  test('"Luego" hides it for this page view', async () => {
    hook.needRefresh = true
    const user = userEvent.setup()
    renderWithI18n(<UpdateBanner busy={false} />)
    await user.click(screen.getByRole('button', { name: 'Luego' }))
    expect(screen.queryByText('Hay una nueva versión.')).toBeNull()
  })
})
