import { useEffect, useState } from 'react'
import { peekLocation, queryPermission, type Fix } from '../capture/geolocation'
import { locationStatus, type LocationStatus } from '../capture/locationStatus'

export type Readiness = {
  location: { status: LocationStatus; accuracyMeters?: number }
  camera: 'unknown' | 'blocked'
}

/**
 * Shows readiness on the main screen before the user taps. One getCurrentPosition per visit and only when
 * the permission is already granted (no prompt out of context); never a continuous watch, never a distance.
 */
export function useReadiness(enabled: boolean): Readiness {
  const [location, setLocation] = useState<Readiness['location']>({ status: 'idle' })
  const [camera, setCamera] = useState<Readiness['camera']>('unknown')

  useEffect(() => {
    if (!enabled) return
    let cancelled = false

    async function check() {
      const [geo, cam] = await Promise.all([queryPermission('geolocation'), queryPermission('camera')])
      if (cancelled) return
      setCamera(cam === 'denied' ? 'blocked' : 'unknown')
      if (geo === 'denied') {
        setLocation({ status: 'denied' })
      } else if (geo === 'granted') {
        setLocation({ status: 'searching' })
        try {
          const fix: Fix = await peekLocation()
          if (!cancelled) setLocation(locationStatus(fix, null))
        } catch (error) {
          if (!cancelled) setLocation(locationStatus(null, error === 'denied' ? 'denied' : 'unavailable'))
        }
      } else {
        setLocation({ status: 'idle' })
      }
    }

    void check()
    const onVisible = () => {
      if (document.visibilityState === 'visible') void check()
    }
    document.addEventListener('visibilitychange', onVisible)
    return () => {
      cancelled = true
      document.removeEventListener('visibilitychange', onVisible)
    }
  }, [enabled])

  return { location, camera }
}
