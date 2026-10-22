import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { expect, test } from 'vitest'
import { STORAGE_KEY } from '../i18n/language'
import { renderWithI18n } from '../test/renderWithI18n'
import { LanguageSwitcher } from './LanguageSwitcher'
import { useI18n } from '../i18n/useI18n'

function Page() {
  const { t } = useI18n()
  return (
    <>
      <LanguageSwitcher />
      <h1>{t('app.title')}</h1>
    </>
  )
}

test('renders a group named Idioma with Español and English buttons', () => {
  renderWithI18n(<Page />)
  expect(screen.getByRole('group', { name: 'Idioma' })).toBeDefined()
  expect(screen.getByRole('button', { name: 'Español' }).getAttribute('lang')).toBe('es')
  expect(screen.getByRole('button', { name: 'English' }).getAttribute('lang')).toBe('en')
})

test('aria-pressed reflects the current language', () => {
  renderWithI18n(<Page />)
  expect(screen.getByRole('button', { name: 'Español' }).getAttribute('aria-pressed')).toBe('true')
  expect(screen.getByRole('button', { name: 'English' }).getAttribute('aria-pressed')).toBe('false')
})

test('clicking English switches text, html lang, storage and announces the change', async () => {
  const user = userEvent.setup()
  const { container } = renderWithI18n(<Page />)
  await user.click(screen.getByRole('button', { name: 'English' }))

  expect(screen.getByRole('heading', { name: 'Attendance Control' })).toBeDefined()
  expect(screen.getByRole('group', { name: 'Language' })).toBeDefined()
  expect(document.documentElement.lang).toBe('en')
  expect(localStorage.getItem(STORAGE_KEY)).toBe('en')
  expect(screen.getByRole('button', { name: 'English' }).getAttribute('aria-pressed')).toBe('true')
  expect(container.querySelector('[aria-live="polite"]')?.textContent).toBe('Language: English')
})

test('nothing is announced on first render', () => {
  const { container } = renderWithI18n(<Page />)
  expect(container.querySelector('[aria-live="polite"]')?.textContent).toBe('')
})

test('works from the keyboard with Tab, Enter and Space, and keeps focus', async () => {
  const user = userEvent.setup()
  renderWithI18n(<Page />)

  await user.tab()
  expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Español' }))
  await user.tab()
  const english = screen.getByRole('button', { name: 'English' })
  expect(document.activeElement).toBe(english)

  await user.keyboard('{Enter}')
  expect(screen.getByRole('heading', { name: 'Attendance Control' })).toBeDefined()
  expect(document.activeElement).toBe(english)

  await user.tab({ shift: true })
  await user.keyboard(' ')
  expect(screen.getByRole('heading', { name: 'Control de Asistencia' })).toBeDefined()
  expect(document.documentElement.lang).toBe('es')
})

test('does not use the status role, which belongs to the health message', () => {
  renderWithI18n(<Page />)
  expect(screen.queryByRole('status')).toBeNull()
})
