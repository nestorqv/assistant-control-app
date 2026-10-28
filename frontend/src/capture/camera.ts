export type CameraErrorKind = 'denied' | 'notFound' | 'busy' | 'unsupported' | 'unknown'

export type OpenCamera = { stream: MediaStream; stop: () => void }

/** Thrown when the browser has no getUserMedia (old browser or an insecure origin). */
export class CameraUnsupportedError extends Error {
  constructor() {
    super('Camera not supported')
    this.name = 'NotSupportedError'
  }
}

export function classifyCameraError(error: unknown): CameraErrorKind {
  const name = error instanceof Error || error instanceof DOMException ? error.name : ''
  switch (name) {
    case 'NotAllowedError':
    case 'SecurityError':
    case 'PermissionDeniedError':
      return 'denied'
    case 'NotFoundError':
    case 'DevicesNotFoundError':
      return 'notFound'
    case 'NotReadableError':
    case 'TrackStartError':
    case 'AbortError':
      return 'busy'
    case 'NotSupportedError':
    case 'TypeError':
      return 'unsupported'
    default:
      // OverconstrainedError and anything unexpected: the file input is the way out.
      return 'unknown'
  }
}

/** Front camera, 1280x720 ideal (not the full sensor). The preview is mirrored with CSS only. */
export async function openFrontCamera(mediaDevices: MediaDevices | undefined = navigator.mediaDevices): Promise<OpenCamera> {
  if (!mediaDevices?.getUserMedia) throw new CameraUnsupportedError()
  const stream = await mediaDevices.getUserMedia({
    video: { facingMode: 'user', width: { ideal: 1280 }, height: { ideal: 720 } },
    audio: false,
  })
  return {
    stream,
    stop: () => stream.getTracks().forEach((track) => track.stop()),
  }
}
