import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { expect, test, vi } from 'vitest'
import App from './App'

test('renders the admin placeholder on /admin', async () => {
  vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('offline')))

  render(
    <MemoryRouter initialEntries={['/admin']}>
      <App />
    </MemoryRouter>,
  )

  const heading = await screen.findByRole('heading', { name: 'Panel del administrador' })
  expect(heading).toBeDefined()
})
