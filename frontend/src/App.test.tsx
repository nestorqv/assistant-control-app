import { screen } from '@testing-library/react'
import { expect, test } from 'vitest'
import App from './App'
import { es } from './i18n/resources'
import { renderWithI18n } from './test/renderWithI18n'
import { stubFetch } from './test/appHarness'

test('renders the admin placeholder on /admin', async () => {
  stubFetch()

  renderWithI18n(<App />, { route: '/admin' })

  const heading = await screen.findByRole('heading', { name: es['adminHome.title'] })
  expect(heading).toBeDefined()
})
