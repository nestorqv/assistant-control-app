import { useEffect, useState } from 'react'
import { fetchHealth, type HealthResponse } from '../api/health'

type State =
  | { kind: 'loading' }
  | { kind: 'ok'; health: HealthResponse }
  | { kind: 'error'; message: string }

export function HealthStatus() {
  const [state, setState] = useState<State>({ kind: 'loading' })

  useEffect(() => {
    const controller = new AbortController()
    fetchHealth(controller.signal)
      .then((health) => setState({ kind: 'ok', health }))
      .catch((error: unknown) => {
        if (controller.signal.aborted) return
        setState({ kind: 'error', message: error instanceof Error ? error.message : String(error) })
      })
    return () => controller.abort()
  }, [])

  return (
    <p role="status" aria-live="polite">
      {state.kind === 'loading' && 'Verificando servidor...'}
      {state.kind === 'ok' &&
        `Servidor en línea (${state.health.status}) - ${new Date(state.health.serverTime).toLocaleString('es-CR', {
          timeZone: 'America/Costa_Rica',
        })}`}
      {state.kind === 'error' && `No se pudo contactar al servidor: ${state.message}`}
    </p>
  )
}
