/** A controllable navigator.geolocation: push positions or errors from the test. */
export function fakeGeolocation() {
  let success: PositionCallback | null = null
  let failure: PositionErrorCallback | null = null
  const cleared: number[] = []
  let nextId = 1

  const geolocation = {
    watchPosition: (ok: PositionCallback, err?: PositionErrorCallback | null) => {
      success = ok
      failure = err ?? null
      return nextId++
    },
    clearWatch: (id: number) => {
      cleared.push(id)
    },
    getCurrentPosition: (ok: PositionCallback, err?: PositionErrorCallback | null) => {
      success = ok
      failure = err ?? null
    },
  }

  return {
    geolocation: geolocation as unknown as Geolocation,
    cleared,
    position(accuracy: number, lat = 9.9325, lng = -84.0796) {
      success?.({
        coords: { latitude: lat, longitude: lng, accuracy, altitude: null, altitudeAccuracy: null, heading: null, speed: null },
        timestamp: Date.now(),
      } as GeolocationPosition)
    },
    error(code: 1 | 2 | 3) {
      failure?.({ code, message: '', PERMISSION_DENIED: 1, POSITION_UNAVAILABLE: 2, TIMEOUT: 3 } as GeolocationPositionError)
    },
  }
}
