import { useEffect, useState } from 'react'

/** Whole seconds left until `retryAt` (performance.now based, so a screen lock does not stretch it). */
export function useCountdown(retryAt: number | null): number {
  const [now, setNow] = useState(() => performance.now())
  useEffect(() => {
    if (retryAt === null) return
    // oxlint-disable-next-line react/set-state-in-effect
    setNow(performance.now())
    const id = setInterval(() => setNow(performance.now()), 1000)
    return () => clearInterval(id)
  }, [retryAt])
  return retryAt === null ? 0 : Math.max(0, Math.ceil((retryAt - now) / 1000))
}

export function formatCountdown(seconds: number): string {
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`
}
