export type HealthResponse = { status: string; service: string; serverTime: string }

export async function fetchHealth(signal?: AbortSignal): Promise<HealthResponse> {
  const response = await fetch('/api/health', { signal })
  if (!response.ok) {
    throw new Error(`HTTP ${response.status}`)
  }
  // A down backend behind the dev proxy can answer with HTML; avoid a JSON parse crash.
  if (!(response.headers.get('content-type') ?? '').includes('json')) {
    throw new Error('Respuesta inesperada del servidor')
  }
  return (await response.json()) as HealthResponse
}
