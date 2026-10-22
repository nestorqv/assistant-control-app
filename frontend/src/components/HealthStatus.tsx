import { useEffect, useState } from 'react'
import { ApiError } from '../api/client'
import { fetchHealth, type HealthResponse } from '../api/health'
import { useI18n } from '../i18n/useI18n'

// The error is stored raw (not as text) so it is re-translated when the language changes.
type State = { kind: 'loading' } | { kind: 'ok'; health: HealthResponse } | { kind: 'error'; error: unknown }

export function HealthStatus() {
  const { lang, t } = useI18n()
  const [state, setState] = useState<State>({ kind: 'loading' })

  useEffect(() => {
    const controller = new AbortController()
    fetchHealth(controller.signal)
      .then((health) => setState({ kind: 'ok', health }))
      .catch((error: unknown) => {
        if (controller.signal.aborted) return
        setState({ kind: 'error', error })
      })
    return () => controller.abort()
  }, [])

  function errorReason(error: unknown) {
    if (error instanceof ApiError) {
      return error.kind === 'http'
        ? t('health.httpStatus', { status: error.status ?? 0 })
        : t('health.unexpectedResponse')
    }
    return error instanceof Error ? error.message : String(error)
  }

  return (
    <p role="status" aria-live="polite">
      {state.kind === 'loading' && t('health.checking')}
      {state.kind === 'ok' &&
        t('health.online', {
          status: state.health.status,
          time: new Date(state.health.serverTime).toLocaleString(lang === 'en' ? 'en-US' : 'es-CR', {
            timeZone: 'America/Costa_Rica',
          }),
        })}
      {state.kind === 'error' && t('health.unreachable', { reason: errorReason(state.error) })}
    </p>
  )
}
