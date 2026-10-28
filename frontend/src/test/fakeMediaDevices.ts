import type { OpenCamera } from '../capture/camera'

/** A fake camera whose tracks record stop(), so tests can check the stream was released. */
export function fakeCamera() {
  const track = { stopped: false, stop() { this.stopped = true } }
  const stream = { getTracks: () => [track] } as unknown as MediaStream
  const camera: OpenCamera = { stream, stop: () => track.stop() }
  return { camera, track }
}

export function fakeMediaDevices(result: 'ok' | DOMException) {
  const { camera, track } = fakeCamera()
  const calls: MediaStreamConstraints[] = []
  const mediaDevices = {
    getUserMedia: async (constraints: MediaStreamConstraints) => {
      calls.push(constraints)
      if (result !== 'ok') throw result
      return camera.stream
    },
  } as unknown as MediaDevices
  return { mediaDevices, calls, track }
}
