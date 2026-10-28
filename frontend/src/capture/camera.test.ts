import { describe, expect, test } from 'vitest'
import { fakeMediaDevices } from '../test/fakeMediaDevices'
import { CameraUnsupportedError, classifyCameraError, openFrontCamera } from './camera'
import { newClientUuid } from './uuid'

describe('classifyCameraError', () => {
  test.each([
    ['NotAllowedError', 'denied'],
    ['SecurityError', 'denied'],
    ['NotFoundError', 'notFound'],
    ['NotReadableError', 'busy'],
    ['OverconstrainedError', 'unknown'],
    ['NotSupportedError', 'unsupported'],
    ['Whatever', 'unknown'],
  ])('%s is %s', (name, kind) => {
    expect(classifyCameraError(new DOMException('x', name))).toBe(kind)
  })

  test('a CameraUnsupportedError is "unsupported"', () => {
    expect(classifyCameraError(new CameraUnsupportedError())).toBe('unsupported')
  })
})

describe('openFrontCamera', () => {
  test('asks for the front camera at 1280x720 ideal, without audio, and stop() ends the tracks', async () => {
    const { mediaDevices, calls, track } = fakeMediaDevices('ok')
    const camera = await openFrontCamera(mediaDevices)
    expect(calls[0]).toEqual({
      video: { facingMode: 'user', width: { ideal: 1280 }, height: { ideal: 720 } },
      audio: false,
    })
    camera.stop()
    expect(track.stopped).toBe(true)
  })

  test('without getUserMedia it throws the unsupported error', async () => {
    await expect(openFrontCamera(undefined)).rejects.toBeInstanceOf(CameraUnsupportedError)
  })
})

describe('newClientUuid', () => {
  test('uses crypto.randomUUID and produces a v4 shape', () => {
    expect(newClientUuid()).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/)
    expect(newClientUuid()).not.toBe(newClientUuid())
  })
})
