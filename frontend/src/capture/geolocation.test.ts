import { afterEach, describe, expect, test, vi } from 'vitest'
import { fakeGeolocation } from '../test/fakeGeolocation'
import { acquireLocation, peekLocation } from './geolocation'

afterEach(() => {
  vi.useRealTimers()
})

describe('acquireLocation', () => {
  test('keeps the best fix (lowest accuracy value)', () => {
    const geo = fakeGeolocation()
    const controller = acquireLocation({ geolocation: geo.geolocation, target: 5 })
    geo.position(80)
    geo.position(40)
    geo.position(60)
    expect(controller.best()?.accuracy).toBe(40)
  })

  test('stops early once the accuracy is at most 30 m', async () => {
    const geo = fakeGeolocation()
    const controller = acquireLocation({ geolocation: geo.geolocation })
    geo.position(50)
    geo.position(25)
    await expect(controller.done).resolves.toMatchObject({ accuracy: 25 })
    expect(geo.cleared).toHaveLength(1)
  })

  test('after 20 s it returns the best fix so far', async () => {
    vi.useFakeTimers()
    const geo = fakeGeolocation()
    const controller = acquireLocation({ geolocation: geo.geolocation })
    geo.position(90)
    vi.advanceTimersByTime(20_000)
    await expect(controller.done).resolves.toMatchObject({ accuracy: 90 })
    expect(controller.error()).toBeNull()
  })

  test('after 20 s without any fix the error is "timeout"', async () => {
    vi.useFakeTimers()
    const geo = fakeGeolocation()
    const controller = acquireLocation({ geolocation: geo.geolocation })
    vi.advanceTimersByTime(20_000)
    await expect(controller.done).resolves.toBeNull()
    expect(controller.error()).toBe('timeout')
  })

  test('PERMISSION_DENIED gives "denied"', async () => {
    const geo = fakeGeolocation()
    const controller = acquireLocation({ geolocation: geo.geolocation })
    geo.error(1)
    await expect(controller.done).resolves.toBeNull()
    expect(controller.error()).toBe('denied')
  })

  test('POSITION_UNAVAILABLE gives "unavailable"', async () => {
    const geo = fakeGeolocation()
    const controller = acquireLocation({ geolocation: geo.geolocation })
    geo.error(2)
    await controller.done
    expect(controller.error()).toBe('unavailable')
  })

  test('an error after a fix keeps the fix', async () => {
    const geo = fakeGeolocation()
    const controller = acquireLocation({ geolocation: geo.geolocation })
    geo.position(60)
    geo.error(3)
    await expect(controller.done).resolves.toMatchObject({ accuracy: 60 })
    expect(controller.error()).toBeNull()
  })

  test('stop() clears the watch once and resolves with what exists', async () => {
    const geo = fakeGeolocation()
    const controller = acquireLocation({ geolocation: geo.geolocation })
    geo.position(70)
    controller.stop()
    controller.stop()
    await expect(controller.done).resolves.toMatchObject({ accuracy: 70 })
    expect(geo.cleared).toHaveLength(1)
  })

  test('subscribers are told about each new fix', () => {
    const geo = fakeGeolocation()
    const controller = acquireLocation({ geolocation: geo.geolocation, target: 1 })
    const listener = vi.fn()
    controller.subscribe(listener)
    geo.position(80)
    expect(listener).toHaveBeenCalled()
  })

  test('no geolocation API means "unavailable" at once', async () => {
    const controller = acquireLocation({ geolocation: undefined as unknown as Geolocation })
    await expect(controller.done).resolves.toBeNull()
    expect(controller.error()).toBe('unavailable')
  })
})

describe('peekLocation', () => {
  test('resolves with one fix and rejects with the error kind', async () => {
    const ok = fakeGeolocation()
    const promise = peekLocation(ok.geolocation)
    ok.position(12)
    await expect(promise).resolves.toMatchObject({ accuracy: 12 })

    const denied = fakeGeolocation()
    const failing = peekLocation(denied.geolocation)
    denied.error(1)
    await expect(failing).rejects.toBe('denied')
  })
})
