import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, test, vi } from 'vitest'
import { acquireLocation } from '../capture/geolocation'
import { listAll, enqueue } from '../storage/outbox'
import { fakeCamera } from '../test/fakeMediaDevices'
import { fakeGeolocation } from '../test/fakeGeolocation'
import { baselineJpegBytes, entryResponse, entrySummary, jsonResponse, problemResponse, today } from '../test/fixtures'
import { renderApp, seedSession, stubFetch } from '../test/appHarness'

const holder = vi.hoisted(() => ({ deps: undefined as unknown }))

// SelfieCapture reads its browser access from here, so the tests hand it fakes.
vi.mock('../capture/captureDeps', () => ({
  get realCaptureDeps() {
    return holder.deps
  },
}))

const NOW = '2026-10-07T14:00:00Z' // 08:00 in Costa Rica

function makeDeps(over: { permission?: 'granted' | 'prompt' | 'denied' | 'unknown'; openCamera?: () => Promise<unknown> } = {}) {
  const geo = fakeGeolocation()
  const cam = fakeCamera()
  const compress = vi.fn(async () => ({ bytes: baselineJpegBytes().buffer as ArrayBuffer, type: 'image/jpeg' as const, width: 1024, height: 576 }))
  const deps = {
    openCamera: over.openCamera ?? (async () => cam.camera),
    acquire: () => acquireLocation({ geolocation: geo.geolocation }),
    compress,
    permission: async () => over.permission ?? 'granted',
  }
  holder.deps = deps
  return { geo, cam, compress }
}

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'], now: new Date(NOW) })
})

type Phase = 'before' | 'after'

/** today follows the POSTs: after one CLOCK_IN is accepted the server says CLOCK_OUT. */
function server(options: { post?: () => Response | Promise<Response>; status?: 200 | 201 } = {}) {
  let phase: Phase = 'before'
  const posts: FormData[] = []
  const { mock, calls } = stubFetch(
    (url) => {
      if (url !== '/api/time-entries/today') return undefined
      return jsonResponse(
        phase === 'before'
          ? today()
          : today({ nextAction: 'CLOCK_OUT', inProgress: true, clockIn: entrySummary({ serverTime: '2026-10-07T14:00:00Z', insideGeofence: true, distanceMeters: 35 }) }),
      )
    },
    async (url, init) => {
      if (url !== '/api/time-entries' || init?.method !== 'POST') return undefined
      posts.push(init.body as FormData)
      const response = options.post ? await options.post() : jsonResponse(entryResponse({ serverTime: '2026-10-07T14:00:00Z' }), options.status ?? 201)
      if (response.ok) phase = 'after'
      return response
    },
  )
  return { mock, calls, posts }
}

const dialog = () => screen.getByRole('dialog', { name: 'Tu selfie' })

async function openCapture(user: ReturnType<typeof userEvent.setup>) {
  await user.click(await screen.findByRole('button', { name: 'Marcar entrada' }))
  return screen.findByRole('dialog')
}

describe('online clock-in', () => {
  test('selfie + GPS go through the outbox to POST /api/time-entries and the server result is shown and announced', async () => {
    await seedSession()
    const { geo, cam, compress } = makeDeps()
    const { posts } = server()
    const user = userEvent.setup()
    renderApp('/')

    await openCapture(user)
    const shutter = await screen.findByRole('button', { name: 'Tomar foto' })
    geo.position(12)
    expect(await within(dialog()).findByText('Ubicación lista · ±12 m')).toBeDefined()
    expect(within(dialog()).queryByText(/del local/)).toBeNull()

    await user.click(shutter)
    await user.click(await screen.findByRole('button', { name: 'Usar foto' }))

    await waitFor(() => expect(posts).toHaveLength(1))
    const form = posts[0]
    expect(form.get('type')).toBe('CLOCK_IN')
    expect(form.get('clientUuid')).toMatch(/^[0-9a-f-]{36}$/)
    expect(form.get('lat')).toBe('9.9325')
    expect(form.get('lng')).toBe('-84.0796')
    expect(form.get('accuracy')).toBe('12')
    expect((form.get('photo') as File).name).toBe('selfie.jpg')
    expect(form.get('deviceTime')).toBe(NOW.replace('Z', '.000Z'))
    expect(compress).toHaveBeenCalledTimes(1)

    // Result line from the server, status moves on, resources are released.
    expect(await screen.findByText('En turno desde 08:00')).toBeDefined()
    expect(await screen.findByText('Dentro del área · a 35 m del local')).toBeDefined()
    expect(screen.queryByText('Pendiente de envío')).toBeNull()
    expect(cam.track.stopped).toBe(true)
    expect(geo.cleared.length).toBeGreaterThan(0)
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(await listAll()).toEqual([])
    await waitFor(() => expect(screen.getByRole('status').textContent).toBe('Entrada registrada a las 08:00, Dentro del área, a 35 m del local'))
    expect(document.activeElement).toBe(screen.getByRole('heading', { name: 'En turno desde 08:00' }))
  })

  test('a 200 replay counts as success', async () => {
    await seedSession()
    const { geo } = makeDeps()
    const { posts } = server({ status: 200 })
    const user = userEvent.setup()
    renderApp('/')
    await openCapture(user)
    await user.click(await screen.findByRole('button', { name: 'Tomar foto' }))
    geo.position(10)
    await user.click(await screen.findByRole('button', { name: 'Usar foto' }))
    await waitFor(() => expect(posts).toHaveLength(1))
    expect(await screen.findByText('En turno desde 08:00')).toBeDefined()
    expect(await listAll()).toEqual([])
  })

  test('the main screen chip shows "se pedirá al marcar" before the first tap and never a distance', async () => {
    await seedSession()
    makeDeps()
    server()
    renderApp('/')
    expect(await screen.findByText('Ubicación: se pedirá al marcar')).toBeDefined()
    expect(screen.queryByText(/del local/)).toBeNull()
  })

  test('a server rejection becomes a failure card with the photo already dropped', async () => {
    await seedSession()
    const { geo } = makeDeps()
    server({ post: () => problemResponse(409, 'ALREADY_CLOCKED_IN') })
    const user = userEvent.setup()
    renderApp('/')
    await openCapture(user)
    await user.click(await screen.findByRole('button', { name: 'Tomar foto' }))
    geo.position(10)
    await user.click(await screen.findByRole('button', { name: 'Usar foto' }))

    const card = await screen.findByRole('alert')
    expect(within(card).getByText('Ya tenías una entrada registrada, así que esta marca no se guardó.')).toBeDefined()
    const [stored] = await listAll()
    expect(stored).toMatchObject({ state: 'failed', photo: null })
  })
})

describe('cancel and release', () => {
  test('Esc closes the dialog, stops the camera tracks and the GPS watch, and refocuses the button', async () => {
    await seedSession()
    const { cam, geo } = makeDeps()
    server()
    const user = userEvent.setup()
    renderApp('/')
    await openCapture(user)
    await screen.findByRole('button', { name: 'Tomar foto' })

    fireEvent(screen.getByRole('dialog'), new Event('cancel', { cancelable: true }))

    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(cam.track.stopped).toBe(true)
    expect(geo.cleared.length).toBeGreaterThan(0)
    await waitFor(() => expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Marcar entrada' })))
    expect(await listAll()).toEqual([])
  })

  test('Retake releases the review URL and reopens the camera', async () => {
    await seedSession()
    makeDeps()
    server()
    const revoke = vi.spyOn(URL, 'revokeObjectURL')
    const user = userEvent.setup()
    renderApp('/')
    await openCapture(user)
    await user.click(await screen.findByRole('button', { name: 'Tomar foto' }))
    await user.click(await screen.findByRole('button', { name: 'Repetir' }))
    expect(revoke).toHaveBeenCalled()
    expect(await screen.findByRole('button', { name: 'Tomar foto' })).toBeDefined()
  })

  test('the preview is mirrored by CSS only and the review image says it is saved unmirrored', async () => {
    await seedSession()
    makeDeps()
    server()
    const user = userEvent.setup()
    renderApp('/')
    await openCapture(user)
    await screen.findByRole('button', { name: 'Tomar foto' })
    expect(screen.getByLabelText('Vista previa de la cámara').className).toContain('preview-video')
    await user.click(screen.getByRole('button', { name: 'Tomar foto' }))
    expect(await screen.findByText('La foto se guarda sin efecto espejo.')).toBeDefined()
    expect(screen.getByRole('img', { name: 'Vista previa de tu selfie' })).toBeDefined()
    expect(screen.getByText('Al usar la foto se registra tu entrada.')).toBeDefined()
  })
})

describe('GPS cases', () => {
  test('denied GPS is allowed: the chip warns and the entry is sent without coordinates', async () => {
    await seedSession()
    const { geo } = makeDeps()
    const { posts } = server()
    const user = userEvent.setup()
    renderApp('/')
    await openCapture(user)
    await screen.findByRole('button', { name: 'Tomar foto' })
    geo.error(1)
    expect(await within(dialog()).findByText('Ubicación bloqueada · la marca quedará en revisión')).toBeDefined()
    await user.click(screen.getByRole('button', { name: 'Tomar foto' }))
    await user.click(await screen.findByRole('button', { name: 'Usar foto' }))
    await waitFor(() => expect(posts).toHaveLength(1))
    expect(posts[0].has('lat')).toBe(false)
    expect(posts[0].has('accuracy')).toBe(false)
  })

  test('low accuracy warns but is sent', async () => {
    await seedSession()
    const { geo } = makeDeps()
    const { posts } = server()
    const user = userEvent.setup()
    renderApp('/')
    await openCapture(user)
    await screen.findByRole('button', { name: 'Tomar foto' })
    geo.position(150)
    expect(await within(dialog()).findByText('Ubicación imprecisa · ±150 m · la marca quedará en revisión')).toBeDefined()
    await user.click(screen.getByRole('button', { name: 'Tomar foto' }))
    await user.click(await screen.findByRole('button', { name: 'Usar foto' }))
    await waitFor(() => expect(posts).toHaveLength(1))
    expect(posts[0].get('accuracy')).toBe('150')
  })

  test('with no fix yet "Usar foto" waits for the location and finishes when a good fix arrives', async () => {
    await seedSession()
    const { geo } = makeDeps()
    const { posts } = server()
    const user = userEvent.setup()
    renderApp('/')
    await openCapture(user)
    await user.click(await screen.findByRole('button', { name: 'Tomar foto' }))
    await user.click(await screen.findByRole('button', { name: 'Usar foto' }))

    expect(await within(dialog()).findAllByText('Obteniendo ubicación…')).not.toHaveLength(0)
    expect(posts).toHaveLength(0)
    geo.position(20)
    await waitFor(() => expect(posts).toHaveLength(1))
    expect(posts[0].get('accuracy')).toBe('20')
  })
})

describe('camera problems', () => {
  test('denied: instructions, retry, the phone-camera fallback, and the file path still clocks', async () => {
    await seedSession()
    const { geo, compress } = makeDeps({ openCamera: async () => Promise.reject(new DOMException('no', 'NotAllowedError')) })
    const { posts } = server()
    const user = userEvent.setup()
    renderApp('/')
    await openCapture(user)

    const alert = await screen.findByRole('alert')
    expect(within(alert).getByText('No tenemos permiso para usar la cámara.')).toBeDefined()
    expect(within(alert).getByText(/Android \(Chrome\)/)).toBeDefined()
    expect(within(alert).getByText(/iPhone \(Safari\)/)).toBeDefined()
    expect(screen.getByText(/Sin selfie no se puede marcar/)).toBeDefined()
    expect(screen.getByRole('button', { name: 'Reintentar' })).toBeDefined()

    await user.click(screen.getByRole('button', { name: 'Usar la cámara del teléfono' }))
    const file = new File([baselineJpegBytes() as BlobPart], 'gallery.jpg', { type: 'image/jpeg' })
    await user.upload(screen.getByTestId('selfie-file-input'), file)
    expect(compress).toHaveBeenCalledWith(file)
    geo.position(15)
    await user.click(await screen.findByRole('button', { name: 'Usar foto' }))
    await waitFor(() => expect(posts).toHaveLength(1))
  })

  test('no camera at all offers only the phone camera and cancel', async () => {
    await seedSession()
    makeDeps({ openCamera: async () => Promise.reject(new DOMException('none', 'NotFoundError')) })
    server()
    const user = userEvent.setup()
    renderApp('/')
    await openCapture(user)
    expect(await screen.findByText('No se encontró una cámara en este teléfono.')).toBeDefined()
    expect(screen.queryByRole('button', { name: 'Reintentar' })).toBeNull()
    expect(screen.getByRole('button', { name: 'Usar la cámara del teléfono' })).toBeDefined()
  })

  test('a camera used by another app can be retried', async () => {
    await seedSession()
    makeDeps({ openCamera: async () => Promise.reject(new DOMException('busy', 'NotReadableError')) })
    server()
    const user = userEvent.setup()
    renderApp('/')
    await openCapture(user)
    expect(await screen.findByText(/La cámara está en uso por otra aplicación/)).toBeDefined()
    expect(screen.getByRole('button', { name: 'Reintentar' })).toBeDefined()
  })

  test('two unusable photos end in "No se puede marcar sin selfie"', async () => {
    await seedSession()
    const { compress } = makeDeps({ openCamera: async () => Promise.reject(new DOMException('no', 'NotAllowedError')) })
    compress.mockRejectedValue(Object.assign(new Error('x'), { reason: 'photoUnsupported' }))
    server()
    const user = userEvent.setup()
    renderApp('/')
    await openCapture(user)
    await user.click(await screen.findByRole('button', { name: 'Usar la cámara del teléfono' }))
    const input = screen.getByTestId('selfie-file-input')
    await user.upload(input, new File(['x'], 'a.jpg', { type: 'image/jpeg' }))
    expect(await screen.findByText('No se pudo preparar la foto. Vuelve a tomarla.')).toBeDefined()
    await user.click(screen.getByRole('button', { name: 'Repetir' }))
    await user.upload(input, new File(['y'], 'b.jpg', { type: 'image/jpeg' }))
    expect(await screen.findByText('No se puede marcar sin selfie. Contacta a tu administrador.')).toBeDefined()
    expect(screen.queryByRole('button', { name: 'Usar la cámara del teléfono' })).toBeNull()
  })

  test('the first time, an explainer comes before the permission prompts', async () => {
    await seedSession()
    const { cam } = makeDeps({ permission: 'prompt' })
    server()
    const user = userEvent.setup()
    renderApp('/')
    await openCapture(user)
    expect(await screen.findByText(/Tu teléfono te pedirá permiso para usar la cámara y la ubicación/)).toBeDefined()
    expect(cam.track.stopped).toBe(false)
    await user.click(screen.getByRole('button', { name: 'Continuar' }))
    expect(await screen.findByRole('button', { name: 'Tomar foto' })).toBeDefined()
  })
})

describe('offline and limits', () => {
  test('offline: the entry is stored with its photo, announced as saved, and sent after reconnecting with the same clientUuid', async () => {
    const online = vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(false)
    await seedSession()
    const { geo } = makeDeps()
    const { posts, mock } = server()
    const user = userEvent.setup()
    renderApp('/')
    await openCapture(user)
    await user.click(await screen.findByRole('button', { name: 'Tomar foto' }))
    geo.position(18)
    await user.click(await screen.findByRole('button', { name: 'Usar foto' }))

    expect(await screen.findByText('Entrada guardada · se enviará al reconectar')).toBeDefined()
    expect(screen.getByText('Pendiente de envío')).toBeDefined()
    expect(screen.getByText('1 pendiente')).toBeDefined()
    expect(posts).toHaveLength(0)
    const [stored] = await listAll()
    expect(new Uint8Array(stored.photo!)).toEqual(baselineJpegBytes())
    await waitFor(() => expect(screen.getByRole('status').textContent).toContain('Entrada guardada; se enviará al reconectar'))
    expect(document.activeElement).toBe(screen.getByRole('heading', { name: 'Entrada guardada · se enviará al reconectar' }))

    online.mockReturnValue(true)
    window.dispatchEvent(new Event('online'))

    await waitFor(() => expect(posts).toHaveLength(1))
    expect(posts[0].get('clientUuid')).toBe(stored.clientUuid)
    expect(posts[0].get('deviceTime')).toBe(stored.deviceTime)
    await waitFor(() => expect(screen.queryByText('Pendiente de envío')).toBeNull())
    expect(await screen.findByText('En turno desde 08:00')).toBeDefined()
    expect(await listAll()).toEqual([])
    expect(mock).toHaveBeenCalled()
  })

  test('a network failure keeps the item and shows the offline banner even though the browser says online', async () => {
    await seedSession()
    const { geo } = makeDeps()
    stubFetch(
      (url) => (url === '/api/time-entries/today' ? jsonResponse(today()) : undefined),
      (url, init) => {
        if (url === '/api/time-entries' && init?.method === 'POST') throw new TypeError('network down')
        return undefined
      },
    )
    const user = userEvent.setup()
    renderApp('/')
    await openCapture(user)
    await user.click(await screen.findByRole('button', { name: 'Tomar foto' }))
    geo.position(18)
    await user.click(await screen.findByRole('button', { name: 'Usar foto' }))

    await waitFor(() => expect(document.querySelector('.offline-banner')).not.toBeNull())
    expect(await screen.findByText('Entrada guardada · se enviará al reconectar')).toBeDefined()
    expect(await listAll()).toHaveLength(1)
  })

  test('with 8 unsent entries a new capture is refused with a message and no dialog', async () => {
    vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(false)
    await seedSession()
    makeDeps()
    server()
    // Eight clock-ins in a row is not a real day, but it keeps the button enabled so the guard is reachable.
    for (let i = 0; i < 8; i++) {
      await enqueue({
        employeeId: 7,
        type: 'CLOCK_IN',
        clientUuid: `cu-${i}`,
        deviceTime: '2026-10-07T13:58:00.000Z',
        photo: baselineJpegBytes().buffer as ArrayBuffer,
        photoType: 'image/jpeg',
        createdAt: '2026-10-07T13:58:00.000Z',
      })
    }
    const user = userEvent.setup()
    renderApp('/')
    await screen.findByText('8 pendientes')
    await user.click(screen.getByRole('button', { name: 'Marcar salida' }))

    const alert = await screen.findByRole('alert')
    expect(alert.textContent).toBe('Tienes demasiadas marcas sin enviar. Conéctate a internet para enviarlas.')
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(await listAll()).toHaveLength(8)
  })

  test('when the device is out of space the entry is refused with a message and nothing is kept', async () => {
    await seedSession()
    const { geo } = makeDeps()
    server()
    Object.defineProperty(navigator, 'storage', { configurable: true, value: { estimate: async () => ({ quota: 100, usage: 100 }) } })
    const user = userEvent.setup()
    renderApp('/')
    await openCapture(user)
    await user.click(await screen.findByRole('button', { name: 'Tomar foto' }))
    geo.position(10)
    await user.click(await screen.findByRole('button', { name: 'Usar foto' }))

    const alert = await screen.findByRole('alert')
    expect(alert.textContent).toContain('No hay espacio para guardar la marca')
    expect(await listAll()).toEqual([])
    expect(screen.getByRole('button', { name: 'Marcar entrada' })).toBeDefined()
    delete (navigator as { storage?: unknown }).storage
  })

  test('a double tap on the button opens one dialog and one item', async () => {
    await seedSession()
    makeDeps()
    server()
    const user = userEvent.setup()
    renderApp('/')
    const button = await screen.findByRole('button', { name: 'Marcar entrada' })
    await user.dblClick(button)
    expect(screen.getAllByRole('dialog')).toHaveLength(1)
  })
})
