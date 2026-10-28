import { apiRequest, apiRequestWithStatus } from './client'
import type { TimeEntryResponse, TimeEntryType, TimeResponse, TodayResponse } from './types'

/** One queued clock action, as stored in the outbox. */
export type SendableEntry = {
  type: TimeEntryType
  clientUuid: string
  deviceTime: string
  lat?: number
  lng?: number
  accuracy?: number
  photo: ArrayBuffer | null
  photoType: string
}

export function getTime(): Promise<TimeResponse> {
  return apiRequest<TimeResponse>('/api/time', { timeoutMs: 15_000 })
}

export function getToday(): Promise<TodayResponse> {
  return apiRequest<TodayResponse>('/api/time-entries/today', { auth: true, timeoutMs: 15_000 })
}

/**
 * Builds the multipart form. deviceSentAt is set here, at send time, never when the entry was captured.
 * `sentAt` is the phone clock normally, or the server-clock estimate when the phone clock is absurd.
 */
export function buildEntryForm(item: SendableEntry, sentAt: Date = new Date()): FormData {
  const form = new FormData()
  form.set('type', item.type)
  form.set('clientUuid', item.clientUuid)
  form.set('deviceTime', item.deviceTime)
  form.set('deviceSentAt', sentAt.toISOString())
  // String(number) always uses dot decimals; toLocaleString would break es-CR.
  if (item.lat !== undefined && item.lng !== undefined) {
    form.set('lat', String(item.lat))
    form.set('lng', String(item.lng))
    if (item.accuracy !== undefined) form.set('accuracy', String(item.accuracy))
  }
  if (item.photo) form.set('photo', new Blob([item.photo], { type: item.photoType }), 'selfie.jpg')
  return form
}

/** 201 (new) or 200 (replay of the same clientUuid); both mean the entry exists on the server. */
export async function postTimeEntry(
  item: SendableEntry,
  sentAt: Date = new Date(),
): Promise<{ status: 200 | 201; body: TimeEntryResponse }> {
  const result = await apiRequestWithStatus<TimeEntryResponse>('/api/time-entries', {
    method: 'POST',
    body: buildEntryForm(item, sentAt),
    auth: true,
    timeoutMs: 45_000,
  })
  return { status: result.status as 200 | 201, body: result.body }
}
