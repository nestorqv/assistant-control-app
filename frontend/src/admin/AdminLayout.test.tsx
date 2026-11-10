import { act, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, expect, test, vi } from 'vitest'
import { markNeedsReauth } from '../auth/session'
import { jsonResponse } from '../test/fixtures'
import { adminIdentity } from '../test/adminFixtures'
import { seedSession, stubFetch } from '../test/appHarness'
import { renderWithI18n } from '../test/renderWithI18n'
import { AdminLayout } from './AdminLayout'
import { useAdminShell } from './shell'

function Probe() {
  const shell = useAdminShell()
  return (
    <>
      <button type="button" onClick={() => shell.suspendSession(true)}>
        suspend
      </button>
      <button type="button" onClick={() => shell.suspendSession(false)}>
        resume
      </button>
    </>
  )
}

async function setup() {
  const stub = stubFetch(
    (url) => (url === '/api/admin/review-queue/count' ? jsonResponse({ pending: 0, today: '2026-10-07', timeZone: 'America/Costa_Rica' }) : undefined),
    (url) => (url === '/api/health' ? jsonResponse({ status: 'UP', service: 'x', serverTime: '2026-10-13T18:00:00Z' }) : undefined),
  )
  await seedSession({ identity: adminIdentity })
  renderWithI18n(
    <AdminLayout>
      <Probe />
    </AdminLayout>,
    { route: '/admin', providers: true },
  )
  await screen.findByRole('button', { name: 'suspend' })
  return stub
}

const countCalls = (calls: { url: string }[]) => calls.filter((c) => c.url === '/api/admin/review-queue/count').length

test('while the session is suspended the one-minute poll stays away', async () => {
  const { calls } = await setup()
  await waitFor(() => expect(countCalls(calls)).toBe(1))
  const user = userEvent.setup()
  await user.click(screen.getByRole('button', { name: 'suspend' }))
  // The interval runs the same handler as a return to the tab; both go through refreshPending.
  act(() => {
    document.dispatchEvent(new Event('visibilitychange'))
  })
  await new Promise((resolve) => setTimeout(resolve, 30))
  expect(countCalls(calls)).toBe(1)
})

test('the poll refreshes again after the session is resumed', async () => {
  const { calls } = await setup()
  const user = userEvent.setup()
  await waitFor(() => expect(countCalls(calls)).toBe(1))
  await user.click(screen.getByRole('button', { name: 'suspend' }))
  await user.click(screen.getByRole('button', { name: 'resume' }))
  act(() => {
    document.dispatchEvent(new Event('visibilitychange'))
  })
  await waitFor(() => expect(countCalls(calls)).toBe(2))
})

test('a 401 while suspended opens neither the PIN dialog nor the banner; after resuming it does', async () => {
  await setup()
  const user = userEvent.setup()
  await user.click(screen.getByRole('button', { name: 'suspend' }))
  act(() => markNeedsReauth())
  await new Promise((resolve) => setTimeout(resolve, 30))
  expect(screen.queryByRole('heading', { name: 'Ingrese su PIN' })).toBeNull()
  expect(screen.queryByText('Su sesión venció.')).toBeNull()
  await user.click(screen.getByRole('button', { name: 'resume' }))
  expect(await screen.findByRole('heading', { name: 'Ingrese su PIN' })).toBeDefined()
})

afterEach(() => vi.useRealTimers())

test('the real interval does not poll for 61 s while suspended, and polls once resumed', async () => {
  vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] })
  const { calls } = await setup()
  await waitFor(() => expect(countCalls(calls)).toBe(1))
  const user = userEvent.setup()
  await user.click(screen.getByRole('button', { name: 'suspend' }))
  await act(async () => {
    await vi.advanceTimersByTimeAsync(61_000)
  })
  expect(countCalls(calls)).toBe(1)
  await user.click(screen.getByRole('button', { name: 'resume' }))
  await act(async () => {
    await vi.advanceTimersByTimeAsync(61_000)
  })
  await waitFor(() => expect(countCalls(calls)).toBe(2))
})
