import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { expect, test, vi } from 'vitest'
import App from './App'

function renderAt(path: string) {
  vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('offline')))
  return render(
    <MemoryRouter initialEntries={[path]}>
      <App />
    </MemoryRouter>,
  )
}

test('/ renders the employee home', async () => {
  renderAt('/')
  await screen.findByRole('heading', { name: 'Control de Asistencia' })
  await screen.findByText(/Pantalla del empleado/)
  expect(screen.queryByRole('heading', { name: 'Panel del administrador' })).toBeNull()
})

test('/admin renders the admin home', async () => {
  renderAt('/admin')
  await screen.findByRole('heading', { name: 'Panel del administrador' })
  expect(screen.queryByText(/Pantalla del empleado/)).toBeNull()
})

test('unknown path renders not found with a link home', async () => {
  renderAt('/no-existe/algo')
  await screen.findByText('Página no encontrada')
  const link = await screen.findByRole('link')
  expect(link.getAttribute('href')).toBe('/')
})

test('/admin/ with trailing slash still renders admin', async () => {
  renderAt('/admin/')
  await screen.findByRole('heading', { name: 'Panel del administrador' })
})

test('each page renders the health status region', async () => {
  renderAt('/')
  await screen.findByRole('status')
})
