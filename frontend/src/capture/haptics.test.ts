import { afterEach, describe, expect, test, vi } from 'vitest'
import { haptics } from './haptics'

afterEach(() => {
  delete (navigator as { vibrate?: unknown }).vibrate
})

describe('haptics', () => {
  test('vibrates with the documented patterns when supported', () => {
    const vibrate = vi.fn()
    Object.defineProperty(navigator, 'vibrate', { configurable: true, value: vibrate })
    haptics.tap()
    haptics.success()
    haptics.error()
    expect(vibrate.mock.calls).toEqual([[15], [25], [[40, 60, 40]]])
  })

  test('does nothing where vibration is not supported', () => {
    expect(() => haptics.success()).not.toThrow()
  })

  test('does nothing when the user prefers reduced motion', () => {
    const vibrate = vi.fn()
    Object.defineProperty(navigator, 'vibrate', { configurable: true, value: vibrate })
    vi.stubGlobal('matchMedia', () => ({ matches: true }))
    haptics.success()
    expect(vibrate).not.toHaveBeenCalled()
  })
})
