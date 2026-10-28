import { describe, expect, test } from 'vitest'
import { entrySummary } from '../test/fixtures'
import { geofenceResult, LOW_ACCURACY_METERS, locationStatus } from './locationStatus'

const fix = (accuracy: number) => ({ lat: 9.9, lng: -84, accuracy })

describe('locationStatus (readiness only)', () => {
  test('searching until there is a fix or an error', () => {
    expect(locationStatus(null, null)).toEqual({ status: 'searching' })
  })

  test('accuracy is rounded to whole meters', () => {
    expect(locationStatus(fix(12.4), null)).toEqual({ status: 'ready', accuracyMeters: 12 })
  })

  test('100 m is ready and 101 m is low accuracy', () => {
    expect(LOW_ACCURACY_METERS).toBe(100)
    expect(locationStatus(fix(100), null).status).toBe('ready')
    expect(locationStatus(fix(101), null)).toEqual({ status: 'lowAccuracy', accuracyMeters: 101 })
  })

  test('denied, unavailable and timeout', () => {
    expect(locationStatus(null, 'denied')).toEqual({ status: 'denied' })
    expect(locationStatus(null, 'unavailable')).toEqual({ status: 'unavailable' })
    expect(locationStatus(null, 'timeout')).toEqual({ status: 'unavailable' })
  })

  test('a fix wins over a later error', () => {
    expect(locationStatus(fix(20), 'timeout').status).toBe('ready')
  })
})

describe('geofenceResult (the server decides)', () => {
  test('pending beats everything', () => {
    expect(geofenceResult(entrySummary(), true)).toEqual({ kind: 'pending' })
    expect(geofenceResult(null, true)).toEqual({ kind: 'pending' })
  })

  test('inside, outside and no location', () => {
    expect(geofenceResult(entrySummary({ insideGeofence: true, distanceMeters: 35 }), false)).toEqual({ kind: 'inside', distanceMeters: 35 })
    expect(geofenceResult(entrySummary({ insideGeofence: false, distanceMeters: 1112 }), false)).toEqual({ kind: 'outside', distanceMeters: 1112 })
    expect(geofenceResult(entrySummary({ insideGeofence: null, distanceMeters: null }), false)).toEqual({ kind: 'noLocation' })
  })

  test('nothing to show without an entry', () => {
    expect(geofenceResult(null, false)).toBeNull()
  })
})
