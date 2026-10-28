import { apiRequest } from './client'

export function giveConsent(): Promise<void> {
  return apiRequest<void>('/api/me/consent', { method: 'POST', auth: true })
}
