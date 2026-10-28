export type Fix = { lat: number; lng: number; accuracy: number }
export type LocationError = 'denied' | 'unavailable' | 'timeout'

export type LocationController = {
  best(): Fix | null
  error(): LocationError | null
  /** Resolves with the best fix so far when the watch ends (good fix, error or timeout). */
  done: Promise<Fix | null>
  subscribe(listener: () => void): () => void
  stop(): void
}

type Options = {
  /** stop early once the accuracy is at least this good (meters) */
  target?: number
  timeoutMs?: number
  geolocation?: Pick<Geolocation, 'watchPosition' | 'clearWatch'>
}

function toFix(position: GeolocationPosition): Fix {
  return { lat: position.coords.latitude, lng: position.coords.longitude, accuracy: position.coords.accuracy }
}

function toError(error: GeolocationPositionError): LocationError {
  if (error.code === 1) return 'denied'
  if (error.code === 3) return 'timeout'
  return 'unavailable'
}

/** Keeps the best (lowest accuracy value) fix. Stops at <= 30 m, after 20 s, or on an error. */
export function acquireLocation({
  target = 30,
  timeoutMs = 20_000,
  geolocation = navigator.geolocation,
}: Options = {}): LocationController {
  let best: Fix | null = null
  let error: LocationError | null = null
  let finished = false
  let watchId: number | null = null
  let timer: ReturnType<typeof setTimeout> | undefined
  const listeners = new Set<() => void>()
  let resolveDone: (fix: Fix | null) => void = () => undefined
  const done = new Promise<Fix | null>((resolve) => {
    resolveDone = resolve
  })

  function notify() {
    listeners.forEach((listener) => listener())
  }

  function finish() {
    if (finished) return
    finished = true
    clearTimeout(timer)
    if (watchId !== null && geolocation) geolocation.clearWatch(watchId)
    resolveDone(best)
    notify()
  }

  if (!geolocation) {
    error = 'unavailable'
    finish()
  } else {
    timer = setTimeout(() => {
      if (!best && !error) error = 'timeout'
      finish()
    }, timeoutMs)
    watchId = geolocation.watchPosition(
      (position) => {
        if (finished) return
        const fix = toFix(position)
        if (!best || fix.accuracy < best.accuracy) best = fix
        notify()
        if (best.accuracy <= target) finish()
      },
      (failure) => {
        if (finished) return
        // A late error does not discard a fix we already have.
        if (!best) error = toError(failure)
        finish()
      },
      { enableHighAccuracy: true, maximumAge: 0, timeout: timeoutMs },
    )
  }

  return {
    best: () => best,
    error: () => error,
    done,
    subscribe(listener) {
      listeners.add(listener)
      return () => {
        listeners.delete(listener)
      }
    },
    stop: finish,
  }
}

/** One-shot read used to show readiness on the main screen; never a continuous watch. */
export function peekLocation(
  geolocation: Pick<Geolocation, 'getCurrentPosition'> | undefined = navigator.geolocation,
  timeoutMs = 10_000,
): Promise<Fix> {
  return new Promise((resolve, reject) => {
    if (!geolocation) {
      reject('unavailable' satisfies LocationError)
      return
    }
    geolocation.getCurrentPosition((position) => resolve(toFix(position)), (failure) => reject(toError(failure)), {
      enableHighAccuracy: true,
      maximumAge: 30_000,
      timeout: timeoutMs,
    })
  })
}

export type PermissionKind = 'granted' | 'denied' | 'prompt' | 'unknown'

/** Permissions API, where it exists (Safari does not support the camera name). */
export async function queryPermission(name: 'geolocation' | 'camera'): Promise<PermissionKind> {
  try {
    const status = await navigator.permissions.query({ name: name as PermissionName })
    return status.state
  } catch {
    return 'unknown'
  }
}
