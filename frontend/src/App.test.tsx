import { screen } from '@testing-library/react'
import { expect, test } from 'vitest'
import App from './App'
import { es } from './i18n/resources'
import { renderWithI18n } from './test/renderWithI18n'
import { stubFetch } from './test/appHarness'

test('the admin route is guarded: no session goes to the login page', async () => {
  stubFetch()

  renderWithI18n(<App />, { route: '/admin', providers: true })

  const heading = await screen.findByRole('heading', { name: es['login.title'] })
  expect(heading).toBeDefined()
})
