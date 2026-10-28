import { createContext, useContext } from 'react'

export type AnnouncerApi = { announce: (message: string, options?: { dedupeKey?: string }) => void }

export const AnnouncerContext = createContext<AnnouncerApi>({ announce: () => undefined })

export function useAnnouncer(): AnnouncerApi {
  return useContext(AnnouncerContext)
}
