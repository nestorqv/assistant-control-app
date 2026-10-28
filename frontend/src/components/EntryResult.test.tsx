import { screen } from '@testing-library/react'
import { describe, expect, test } from 'vitest'
import { geofenceResult } from '../capture/locationStatus'
import { entryResponse, entrySummary } from '../test/fixtures'
import { renderWithI18n } from '../test/renderWithI18n'
import { EntryResult } from './EntryResult'
import { EntryTimes, type EntryColumn } from './EntryTimes'
import { EvidenceChips, LocationChip } from './EvidenceChips'

describe('LocationChip (before sending: readiness only)', () => {
  test.each([
    ['searching', undefined, 'Buscando ubicación…', 'Finding location…'],
    ['ready', 12, 'Ubicación lista · ±12 m', 'Location ready · ±12 m'],
    ['lowAccuracy', 150, 'Ubicación imprecisa · ±150 m · la marca quedará en revisión', 'Low location accuracy · ±150 m · the entry will be reviewed'],
    ['denied', undefined, 'Ubicación bloqueada · la marca quedará en revisión', 'Location blocked · the entry will be reviewed'],
    ['unavailable', undefined, 'Ubicación no disponible · la marca quedará en revisión', 'Location unavailable · the entry will be reviewed'],
    ['idle', undefined, 'Ubicación: se pedirá al marcar', 'Location: will be requested when you clock'],
  ] as const)('%s in Spanish and English', (status, accuracyMeters, es, en) => {
    const first = renderWithI18n(<LocationChip status={status} accuracyMeters={accuracyMeters} />)
    expect(screen.getByText(es)).toBeDefined()
    first.unmount()
    renderWithI18n(<LocationChip status={status} accuracyMeters={accuracyMeters} />, { lang: 'en' })
    expect(screen.getByText(en)).toBeDefined()
  })

  test('never shows a distance before sending', () => {
    for (const status of ['searching', 'ready', 'lowAccuracy', 'denied', 'unavailable', 'idle'] as const) {
      const { container, unmount } = renderWithI18n(<LocationChip status={status} accuracyMeters={12} />)
      expect(container.textContent).not.toMatch(/del local|from the branch|Dentro del área|Fuera del área/)
      unmount()
    }
  })

  test('the evidence section has a heading, two chips and the camera warning', () => {
    renderWithI18n(<EvidenceChips camera="blocked" location={{ status: 'ready', accuracyMeters: 9 }} />)
    expect(screen.getByRole('heading', { name: 'Evidencia' })).toBeDefined()
    expect(screen.getAllByRole('listitem')).toHaveLength(2)
    expect(screen.getByText('Cámara bloqueada · no podrás marcar sin selfie')).toBeDefined()
  })
})

describe('EntryResult (after the server answered)', () => {
  test('inside the area, with the distance from the server', () => {
    const result = geofenceResult(entryResponse({ insideGeofence: true, distanceMeters: 35 }), false)!
    renderWithI18n(<EntryResult {...result} />)
    expect(screen.getByText('Dentro del área · a 35 m del local')).toBeDefined()
  })

  test('outside the area with the distance and "under review"', () => {
    const result = geofenceResult(entryResponse({ insideGeofence: false, distanceMeters: 1112 }), false)!
    renderWithI18n(<EntryResult {...result} />)
    expect(screen.getByText('Fuera del área · 1112 m · en revisión')).toBeDefined()
  })

  test('no location', () => {
    const result = geofenceResult(entryResponse({ insideGeofence: null, distanceMeters: null }), false)!
    renderWithI18n(<EntryResult {...result} />)
    expect(screen.getByText('Sin ubicación · en revisión')).toBeDefined()
  })

  test('pending send and English texts', () => {
    const first = renderWithI18n(<EntryResult kind="pending" />, { lang: 'en' })
    expect(screen.getByText('Pending send')).toBeDefined()
    first.unmount()
    const second = renderWithI18n(<EntryResult kind="inside" distanceMeters={35} />, { lang: 'en' })
    expect(screen.getByText('Inside the area · 35 m from the branch')).toBeDefined()
    second.unmount()
    renderWithI18n(<EntryResult kind="outside" distanceMeters={1112} />, { lang: 'en' })
    expect(screen.getByText('Outside the area · 1112 m · under review')).toBeDefined()
  })
})

describe('EntryTimes', () => {
  const column = (over: Partial<EntryColumn> = {}): EntryColumn => ({
    timeText: '07:58',
    result: { kind: 'outside', distanceMeters: 1112 },
    status: 'PENDING_REVIEW',
    reasonLabels: ['Fuera del área', 'Enviada con retraso'],
    lateMinutes: 8,
    ...over,
  })

  test('reads as label, time, result, review badge and late minutes (from today.clockIn after a reload)', () => {
    renderWithI18n(<EntryTimes clockIn={column()} clockOut={null} />)
    expect(screen.getByText('07:58')).toBeDefined()
    expect(screen.getByText('Fuera del área · 1112 m · en revisión')).toBeDefined()
    expect(screen.getByText('En revisión: Fuera del área, Enviada con retraso')).toBeDefined()
    expect(screen.getByText('Llegada tarde: 8 min')).toBeDefined()
    expect(screen.getByText('Sin registrar')).toBeDefined()
    expect(screen.getAllByText('--:--')).toHaveLength(1)
  })

  test('a queued entry shows "Pendiente de envío" and no review badge', () => {
    renderWithI18n(<EntryTimes clockIn={column({ result: { kind: 'pending' }, status: null, lateMinutes: null })} clockOut={null} />)
    expect(screen.getByText('Pendiente de envío')).toBeDefined()
    expect(screen.queryByText(/En revisión/)).toBeNull()
  })

  test('a rejected entry is shown as rejected', () => {
    renderWithI18n(<EntryTimes clockIn={column({ status: 'REJECTED', result: null })} clockOut={null} />)
    expect(screen.getByText('Rechazada. Contacta a tu administrador.')).toBeDefined()
  })

  test('the summary of a today entry maps to the same result line', () => {
    const summary = entrySummary({ insideGeofence: true, distanceMeters: 41 })
    expect(geofenceResult(summary, false)).toEqual({ kind: 'inside', distanceMeters: 41 })
  })
})
