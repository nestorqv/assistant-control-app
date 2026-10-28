import { useSyncExternalStore } from 'react'
import { getSession, subscribe, type SessionState } from './session'

export function useSession(): SessionState {
  return useSyncExternalStore(subscribe, getSession, getSession)
}
