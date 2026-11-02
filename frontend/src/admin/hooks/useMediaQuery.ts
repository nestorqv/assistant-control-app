import { useSyncExternalStore } from 'react'

/** matchMedia as a hook. Without matchMedia (old test environments) the wide layout is assumed. */
export function useMediaQuery(query: string): boolean {
  return useSyncExternalStore(
    (notify) => {
      if (typeof window.matchMedia !== 'function') return () => undefined
      const list = window.matchMedia(query)
      list.addEventListener('change', notify)
      return () => list.removeEventListener('change', notify)
    },
    () => (typeof window.matchMedia === 'function' ? window.matchMedia(query).matches : true),
    () => true,
  )
}
