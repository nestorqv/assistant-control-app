import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, test, vi } from 'vitest'
import { enqueue } from '../storage/outbox'
import { baselineJpegBytes, entryResponse, entrySummary, jsonResponse, today } from '../test/fixtures'
import { renderApp, seedSession, stubFetch } from '../test/appHarness'

// 2026-10-07 10:13 in Costa Rica (UTC-6). Only Date is faked so real timers keep working.
const NOW = '2026-10-07T16:13:00Z'

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'], now: new Date(NOW) })
})

async function open(over: Parameters<typeof today>[0] = {}, lang: 'es' | 'en' = 'es') {
  await seedSession()
  stubFetch((url) => (url === '/api/time-entries/today' ? jsonResponse(today(over)) : undefined))
  return renderApp('/', lang)
}

const inShift = { nextAction: 'CLOCK_IN' as const }
const afterIn = {
  nextAction: 'CLOCK_OUT' as const,
  inProgress: true,
  clockIn: entrySummary({ serverTime: '2026-10-07T13:58:00Z', lateMinutes: 8 }),
}

describe('header and clock', () => {
  test('greeting uses the first name, the date is in the company zone and the clock is the server clock', async () => {
    await open(inShift)
    expect(await screen.findByRole('heading', { level: 1, name: 'Hola, María' })).toBeDefined()
    await screen.findByText('Aún no has marcado hoy')
    expect(screen.getByText('Miércoles, 7 de octubre')).toBeDefined()
    expect(screen.getByText('Hora del servidor')).toBeDefined()
    expect(document.querySelector('time')?.textContent).toBe('10:13')
  })

  test('the date for 05:30 UTC on the 8th is still the 7th in Costa Rica', async () => {
    vi.setSystemTime(new Date('2026-10-08T05:30:00Z'))
    await open({ workDate: '2026-10-07' })
    expect(await screen.findByText('Miércoles, 7 de octubre')).toBeDefined()
    expect(screen.queryByText(/8 de octubre/)).toBeNull()
  })

  test('the clock says "(aprox.)" while it is not synced with the server', async () => {
    await open(inShift)
    expect(await screen.findByText('(aprox.)')).toBeDefined()
  })

  test('English rendering', async () => {
    await open(inShift, 'en')
    expect(await screen.findByRole('heading', { level: 1, name: 'Hello, María' })).toBeDefined()
    expect(await screen.findByText("You haven't clocked in today")).toBeDefined()
    expect(screen.getByText('Wednesday, October 7')).toBeDefined()
    expect(screen.getByRole('button', { name: 'Clock in' })).toBeDefined()
    expect(screen.getByText('Server time')).toBeDefined()
  })

  test('focus starts on the h1', async () => {
    await open(inShift)
    const h1 = await screen.findByRole('heading', { level: 1 })
    expect(document.activeElement).toBe(h1)
  })
})

describe('the three states', () => {
  test('CLOCK_IN: "Marcar entrada" and empty times', async () => {
    await open(inShift)
    expect(await screen.findByText('Aún no has marcado hoy')).toBeDefined()
    const button = screen.getByRole('button', { name: 'Marcar entrada' })
    expect(button.getAttribute('aria-disabled')).toBe('false')
    expect(screen.getAllByText('--:--')).toHaveLength(2)
  })

  test('CLOCK_OUT: on shift since, live worked time, the entry result and late minutes', async () => {
    await open(afterIn)
    expect(await screen.findByText('En turno desde 07:58')).toBeDefined()
    expect(screen.getByText('Llevas 2 h 15 min')).toBeDefined()
    expect(screen.getByRole('button', { name: 'Marcar salida' })).toBeDefined()
    expect(screen.getByText('Dentro del área · a 35 m del local')).toBeDefined()
    expect(screen.getByText('Llegada tarde: 8 min')).toBeDefined()

    // The worked time is computed locally and follows the clock.
    vi.setSystemTime(new Date('2026-10-07T17:13:00Z'))
    document.dispatchEvent(new Event('visibilitychange'))
    expect(await screen.findByText('Llevas 3 h 15 min')).toBeDefined()
  })

  test('NONE: day complete, "Listo por hoy" disabled with aria-disabled, both times and results', async () => {
    await open({
      nextAction: 'NONE',
      workedMinutes: 545,
      clockIn: entrySummary({ serverTime: '2026-10-07T13:58:00Z' }),
      clockOut: entrySummary({ id: 2, serverTime: '2026-10-07T23:03:00Z', distanceMeters: 41 }),
    })
    expect(await screen.findByText('Jornada completa · 9 h 05 min')).toBeDefined()
    const done = screen.getByRole('button', { name: 'Listo por hoy' })
    expect(done.getAttribute('aria-disabled')).toBe('true')
    expect((done as HTMLButtonElement).disabled).toBe(false)
    expect(screen.getByText('07:58')).toBeDefined()
    expect(screen.getByText('17:03')).toBeDefined()
    expect(screen.getByText('Dentro del área · a 41 m del local')).toBeDefined()
    expect(screen.queryByRole('heading', { name: 'Evidencia' })).toBeNull()
  })
})

describe('blocked and notices', () => {
  test('NO_BRANCH_ASSIGNED: message, disabled button described by it, no admin link for an employee', async () => {
    await open({ blockedReason: 'NO_BRANCH_ASSIGNED', branch: null })
    const status = await screen.findByText('No tienes sucursal asignada. Consulta a tu administrador.')
    const button = screen.getByRole('button', { name: 'Marcar entrada' })
    expect(button.getAttribute('aria-disabled')).toBe('true')
    expect(button.getAttribute('aria-describedby')).toBe(status.closest('h2')!.id)
    expect(screen.queryByRole('link', { name: 'Ir al panel del administrador' })).toBeNull()
  })

  test('NO_BRANCH_ASSIGNED for an admin shows the admin link', async () => {
    await seedSession({ identity: { role: 'ADMIN' } })
    stubFetch((url) => (url === '/api/time-entries/today' ? jsonResponse(today({ blockedReason: 'NO_BRANCH_ASSIGNED', branch: null })) : undefined))
    renderApp('/')
    const link = await screen.findByRole('link', { name: 'Ir al panel del administrador' })
    expect(link.getAttribute('href')).toBe('/admin')
  })

  test('a missing clock-out of an earlier day is a notice with that date', async () => {
    await open({ missingClockOut: true, forgottenWorkDate: '2026-10-06' })
    expect(await screen.findByText('No marcaste la salida del martes 6 de octubre. Tu administrador lo revisará.')).toBeDefined()
  })

  test('review badges translate each reason', async () => {
    await open({
      nextAction: 'CLOCK_OUT',
      clockIn: entrySummary({
        status: 'PENDING_REVIEW',
        insideGeofence: false,
        distanceMeters: 1112,
        reviewReasons: ['OUTSIDE_GEOFENCE', 'SYNCED_LATE', 'NO_GPS', 'LOW_GPS_ACCURACY', 'DEVICE_CLOCK_AHEAD', 'SHIFT_TOO_LONG'],
      }),
    })
    expect(await screen.findByText('Fuera del área · 1112 m · en revisión')).toBeDefined()
    expect(
      screen.getByText(
        'En revisión: Fuera del área, Enviada con retraso, Sin ubicación, Ubicación imprecisa, Hora del teléfono adelantada, Turno demasiado largo',
      ),
    ).toBeDefined()
  })

  test('an entry without GPS shows "Sin ubicación · en revisión"', async () => {
    await open({
      nextAction: 'CLOCK_OUT',
      clockIn: entrySummary({ status: 'PENDING_REVIEW', insideGeofence: null, distanceMeters: null, reviewReasons: ['NO_GPS'] }),
    })
    expect(await screen.findByText('Sin ubicación · en revisión')).toBeDefined()
  })

  test('the overnight "last shift" line comes from the previous CLOCK_OUT response', async () => {
    // Clock out of last night's shift is delivered; today then offers a new clock-in.
    await seedSession()
    let phase: 'out' | 'next' = 'out'
    stubFetch(
      (url) => {
        if (url === '/api/time-entries/today') {
          return jsonResponse(
            phase === 'out'
              ? today({ workDate: '2026-10-06', nextAction: 'CLOCK_OUT', clockIn: entrySummary({ serverTime: '2026-10-07T04:01:00Z' }) })
              : today({ nextAction: 'CLOCK_IN' }),
          )
        }
        return undefined
      },
      (url, init) => {
        if (url === '/api/time-entries' && init?.method === 'POST') {
          phase = 'next'
          return jsonResponse(entryResponse({ id: 11, type: 'CLOCK_OUT', workDate: '2026-10-06', serverTime: '2026-10-07T12:03:00Z' }), 201)
        }
        return undefined
      },
    )
    await enqueue({
      employeeId: 7,
      type: 'CLOCK_OUT',
      clientUuid: 'cu-1',
      deviceTime: '2026-10-07T12:03:00.000Z',
      photo: baselineJpegBytes().buffer as ArrayBuffer,
      photoType: 'image/jpeg',
      createdAt: '2026-10-07T12:03:00.000Z',
    })
    renderApp('/')
    expect(await screen.findByText(/Último turno: /)).toBeDefined()
    expect(screen.getByText(/22:01–06:03 · 8 h 02 min/)).toBeDefined()
    expect(screen.getByText('Aún no has marcado hoy')).toBeDefined()
  })
})

describe('loading and offline cold open', () => {
  test('without a snapshot and offline: asks to connect and disables the button', async () => {
    vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(false)
    await seedSession({ token: false })
    stubFetch()
    renderApp('/')
    expect(await screen.findByText('Conéctate para cargar tu estado')).toBeDefined()
    expect(screen.getByRole('button', { name: 'Marcar entrada' }).getAttribute('aria-disabled')).toBe('true')
    expect(screen.getByText('Sin señal, la marca se guarda y se envía al reconectar.')).toBeDefined()
  })

  test('with a snapshot, a cold open offline shows the last known state and allows clocking', async () => {
    vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(false)
    await seedSession({ token: false })
    const { saveSnapshot } = await import('../storage/snapshot')
    await saveSnapshot({ employeeId: 7, today: today({ nextAction: 'CLOCK_IN' }), clockOffsetMs: null, savedAt: 1 })
    stubFetch()
    renderApp('/')
    expect(await screen.findByText('Aún no has marcado hoy')).toBeDefined()
    expect(screen.getByRole('button', { name: 'Marcar entrada' }).getAttribute('aria-disabled')).toBe('false')
  })

  test('a snapshot of a finished day from yesterday offers the clock-in again', async () => {
    vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(false)
    await seedSession({ token: false })
    const { saveSnapshot } = await import('../storage/snapshot')
    await saveSnapshot({
      employeeId: 7,
      today: today({ workDate: '2026-10-06', nextAction: 'NONE', clockIn: entrySummary(), clockOut: entrySummary({ id: 2 }) }),
      clockOffsetMs: null,
      savedAt: 1,
    })
    stubFetch()
    renderApp('/')
    expect(await screen.findByRole('button', { name: 'Marcar entrada' })).toBeDefined()
    expect(screen.queryByText(/Jornada completa/)).toBeNull()
  })

  test('an app left open across local midnight stops saying "day complete" (network answer)', async () => {
    await open({
      nextAction: 'NONE',
      workedMinutes: 545,
      clockIn: entrySummary({ serverTime: '2026-10-07T13:58:00Z' }),
      clockOut: entrySummary({ id: 2, serverTime: '2026-10-07T23:03:00Z' }),
    })
    expect(await screen.findByText('Jornada completa · 9 h 05 min')).toBeDefined()

    // 00:05 on the 8th in Costa Rica; the server answer in memory still has workDate 2026-10-07.
    vi.setSystemTime(new Date('2026-10-08T06:05:00Z'))
    document.dispatchEvent(new Event('visibilitychange'))
    expect(await screen.findByRole('button', { name: 'Marcar entrada' })).toBeDefined()
    expect(screen.queryByText(/Jornada completa/)).toBeNull()
  })

  test('the footer note is shown online and replaced by the banner offline', async () => {
    await open(inShift)
    const footer = await screen.findByText('Sin señal, la marca se guarda y se envía al reconectar.')
    expect(footer.className).toContain('footer-note')
    expect(document.querySelector('.offline-banner')).toBeNull()
  })
})

describe('queued entries', () => {
  async function queue(type: 'CLOCK_IN' | 'CLOCK_OUT', over: Parameters<typeof today>[0] = {}) {
    vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(false)
    await seedSession()
    stubFetch((url) => (url === '/api/time-entries/today' ? jsonResponse(today(over)) : undefined))
    await enqueue({
      employeeId: 7,
      type,
      clientUuid: `cu-${type}`,
      deviceTime: '2026-10-07T13:58:00.000Z',
      photo: baselineJpegBytes().buffer as ArrayBuffer,
      photoType: 'image/jpeg',
      createdAt: '2026-10-07T13:58:00.000Z',
    })
    return renderApp('/')
  }

  test('a queued CLOCK_IN: saved text, "Marcar salida" enabled, pending line and counter', async () => {
    await queue('CLOCK_IN')
    expect(await screen.findByText('Entrada guardada · se enviará al reconectar')).toBeDefined()
    expect(screen.getByRole('button', { name: 'Marcar salida' }).getAttribute('aria-disabled')).toBe('false')
    expect(screen.getByText('Pendiente de envío')).toBeDefined()
    expect(screen.getByText('1 pendiente')).toBeDefined()
    expect(screen.getByText('07:58')).toBeDefined()
    expect(screen.queryByText(/del local/)).toBeNull()
  })

  test('a queued CLOCK_OUT: "Listo por hoy" disabled', async () => {
    await queue('CLOCK_OUT', { nextAction: 'CLOCK_OUT', clockIn: entrySummary() })
    expect(await screen.findByText('Salida guardada · se enviará al reconectar')).toBeDefined()
    expect(screen.getByRole('button', { name: 'Listo por hoy' }).getAttribute('aria-disabled')).toBe('true')
    expect(screen.getByText('Pendiente de envío')).toBeDefined()
    // The clock-in keeps its own server result.
    expect(screen.getByText('Dentro del área · a 35 m del local')).toBeDefined()
  })

  test('the offline banner has no role=alert and the footer note is hidden while it shows', async () => {
    await queue('CLOCK_IN')
    await screen.findByText('Entrada guardada · se enviará al reconectar')
    const banner = document.querySelector('.offline-banner')!
    expect(banner.textContent).toBe('Sin señal, la marca se guarda y se envía al reconectar.')
    expect(banner.getAttribute('role')).toBeNull()
    expect(document.querySelector('.footer-note')).toBeNull()
    expect(within(document.body).getAllByText('Sin señal, la marca se guarda y se envía al reconectar.')).toHaveLength(1)
  })

  test('"pending since" appears for an entry older than a day', async () => {
    vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(false)
    await seedSession()
    stubFetch((url) => (url === '/api/time-entries/today' ? jsonResponse(today()) : undefined))
    await enqueue({
      employeeId: 7,
      type: 'CLOCK_IN',
      clientUuid: 'old',
      deviceTime: '2026-10-05T13:58:00.000Z',
      photo: baselineJpegBytes().buffer as ArrayBuffer,
      photoType: 'image/jpeg',
      createdAt: '2026-10-05T13:58:00.000Z',
    })
    renderApp('/')
    expect(await screen.findByText('Pendiente desde lun 5 oct, 07:58')).toBeDefined()
  })
})

describe('final failures', () => {
  test('a failed item is shown as a card with the translated reason, and Entendido removes it', async () => {
    await seedSession()
    stubFetch((url) => (url === '/api/time-entries/today' ? jsonResponse(today({ nextAction: 'CLOCK_OUT', clockIn: entrySummary() })) : undefined))
    const seq = await enqueue({
      employeeId: 7,
      type: 'CLOCK_OUT',
      clientUuid: 'cu-fail',
      deviceTime: '2026-10-07T23:03:00.000Z',
      photo: baselineJpegBytes().buffer as ArrayBuffer,
      photoType: 'image/jpeg',
      createdAt: '2026-10-07T23:03:00.000Z',
    })
    const { markFailed } = await import('../storage/outbox')
    await markFailed(seq, { status: 409, code: 'NOT_CLOCKED_IN' })
    const user = userEvent.setup()
    renderApp('/')

    const card = await screen.findByRole('alert')
    expect(within(card).getByRole('heading', { name: 'No se pudo registrar tu salida' })).toBeDefined()
    expect(within(card).getByText('No tienes una entrada abierta, así que no se pudo registrar la salida.')).toBeDefined()
    expect(within(card).getByText('Contacta a tu administrador.')).toBeDefined()
    expect(within(card).getByText('Marca de las 17:03')).toBeDefined()

    await user.click(within(card).getByRole('button', { name: 'Entendido' }))
    await waitFor(() => expect(screen.queryByRole('alert')).toBeNull())
    await waitFor(() => expect(document.activeElement).toBe(screen.getByRole('heading', { name: /En turno desde/ })))
  })
})
