import { act, render, renderHook, screen } from '@testing-library/react'
import type { ReactNode } from 'react'
import { expect, test, vi } from 'vitest'
import { renderWithI18n } from '../test/renderWithI18n'
import { I18nProvider } from './I18nProvider'
import { getLanguage, setLanguage } from './language'
import type { MessageKey } from './resources'
import { useI18n } from './useI18n'

function wrapper(lang: 'es' | 'en') {
  return ({ children }: { children: ReactNode }) => <I18nProvider initialLanguage={lang}>{children}</I18nProvider>
}

test('useI18n outside the provider throws a clear error', () => {
  vi.spyOn(console, 'error').mockImplementation(() => {})
  expect(() => renderHook(() => useI18n())).toThrow(/I18nProvider/)
})

test('an unknown key falls back to the key itself and never throws', () => {
  const { result } = renderHook(() => useI18n(), { wrapper: wrapper('en') })
  expect(result.current.t('nope.missing' as MessageKey)).toBe('nope.missing')
})

test('interpolation replaces every placeholder and keeps unknown ones visible', () => {
  const { result } = renderHook(() => useI18n(), { wrapper: wrapper('en') })
  expect(result.current.t('health.online', { status: 'UP', time: 'noon' })).toBe('Server online (UP) - noon')
  expect(result.current.t('health.online', { status: 'UP' })).toBe('Server online (UP) - {time}')
  expect(result.current.t('health.httpStatus', { status: 503 })).toBe('HTTP 503')
})

test('a value containing replacement patterns is inserted literally', () => {
  const { result } = renderHook(() => useI18n(), { wrapper: wrapper('es') })
  expect(result.current.t('health.unreachable', { reason: '{reason} $& {status}' })).toBe(
    'No se pudo contactar al servidor: {reason} $& {status}',
  )
})

test('a language change made outside React re-renders consumers and updates <html lang>', () => {
  function Title() {
    return <h1>{useI18n().t('app.title')}</h1>
  }
  renderWithI18n(<Title />)
  expect(screen.getByRole('heading').textContent).toBe('Control de Asistencia')
  act(() => setLanguage('en'))
  expect(screen.getByRole('heading').textContent).toBe('Attendance Control')
  expect(document.documentElement.lang).toBe('en')
  expect(document.title).toBe('Attendance Control')
})

test('initialLanguage is applied without persisting it', () => {
  render(<I18nProvider initialLanguage="en">{null}</I18nProvider>)
  expect(getLanguage()).toBe('en')
  expect(localStorage.getItem('attendance.lang')).toBeNull()
})
