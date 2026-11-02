import { useCallback, useEffect, useRef, useState } from 'react'

export type AdminQuery<T> = {
  data: T | null
  error: unknown
  /** First load, or a new key (date, branch, filter) while the old data is still shown */
  loading: boolean
  /** A reload of the same key: the old data stays, nothing flashes */
  refreshing: boolean
  /** epoch ms of the last successful load */
  updatedAt: number | null
  reload: () => void
}

/**
 * Tiny load / reload / error state, no library. `key` identifies the request: a new key starts a new load and
 * aborts the old one. The previous data stays visible until the new data arrives (the page dims it).
 */
export function useAdminQuery<T>(fetcher: (signal: AbortSignal) => Promise<T>, key: string): AdminQuery<T> {
  const [data, setData] = useState<T | null>(null)
  const [error, setError] = useState<unknown>(null)
  const [loading, setLoading] = useState(true)
  const [refreshing, setRefreshing] = useState(false)
  const [updatedAt, setUpdatedAt] = useState<number | null>(null)
  const fetcherRef = useRef(fetcher)
  const abortRef = useRef<AbortController | null>(null)

  useEffect(() => {
    fetcherRef.current = fetcher
  })

  const run = useCallback((isNewKey: boolean) => {
    abortRef.current?.abort()
    const controller = new AbortController()
    abortRef.current = controller
    if (isNewKey) setLoading(true)
    else setRefreshing(true)
    setError(null)
    fetcherRef
      .current(controller.signal)
      .then((value) => {
        if (controller.signal.aborted) return
        setData(value)
        setUpdatedAt(Date.now())
      })
      .catch((e: unknown) => {
        if (!controller.signal.aborted) setError(e)
      })
      .finally(() => {
        if (controller.signal.aborted) return
        setLoading(false)
        setRefreshing(false)
      })
  }, [])

  useEffect(() => {
    run(true)
    return () => abortRef.current?.abort()
  }, [key, run])

  const reload = useCallback(() => run(false), [run])
  return { data, error, loading, refreshing, updatedAt, reload }
}
