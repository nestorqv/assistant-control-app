import type { EntryOutcome } from '../api/types'
import type { Fix, LocationError } from './geolocation'

/** Mirrors the server default app.time-entry.max-gps-accuracy-meters. Keep the two in sync; this one is advisory. */
export const LOW_ACCURACY_METERS = 100

export type LocationStatus = 'idle' | 'searching' | 'ready' | 'lowAccuracy' | 'denied' | 'unavailable'

export function locationStatus(
  fix: Fix | null,
  error: LocationError | null,
): { status: Exclude<LocationStatus, 'idle'>; accuracyMeters?: number } {
  if (fix) {
    const accuracyMeters = Math.round(fix.accuracy)
    return { status: accuracyMeters > LOW_ACCURACY_METERS ? 'lowAccuracy' : 'ready', accuracyMeters }
  }
  if (error === 'denied') return { status: 'denied' }
  if (error) return { status: 'unavailable' }
  return { status: 'searching' }
}

export type GeofenceResult =
  | { kind: 'pending' }
  | { kind: 'inside'; distanceMeters: number }
  | { kind: 'outside'; distanceMeters: number }
  | { kind: 'noLocation' }

/**
 * The server is the only source of "inside / outside": this only reads its answer. No distance is
 * ever computed on the client.
 */
export function geofenceResult(
  entry: Pick<EntryOutcome, 'insideGeofence' | 'distanceMeters'> | null,
  pending: boolean,
): GeofenceResult | null {
  if (pending) return { kind: 'pending' }
  if (!entry) return null
  if (entry.insideGeofence === null) return { kind: 'noLocation' }
  if (entry.distanceMeters === null) return { kind: 'noLocation' }
  const distanceMeters = Math.round(entry.distanceMeters)
  return entry.insideGeofence ? { kind: 'inside', distanceMeters } : { kind: 'outside', distanceMeters }
}
