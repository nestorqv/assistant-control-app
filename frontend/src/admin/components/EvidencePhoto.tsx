import { useEffect, useState } from 'react'
import { ApiProblem } from '../../api/problem'
import { Icon } from '../../components/Icon'
import { useAdminI18n } from '../adminI18n'
import { fetchPhoto } from '../api/adminApi'

type PhotoState =
  | { kind: 'loading' }
  | { kind: 'ok'; url: string }
  | { kind: 'none' }
  | { kind: 'limit'; minutes: number }
  | { kind: 'error' }

type Props = { entryId: number; alt: string; hasPhoto: boolean; manual: boolean }

/**
 * The selfie, fetched with the bearer token only when this component is on screen (never prefetched, never
 * retried by itself: every view is logged and counted against a limit). The object URL is revoked when the
 * entry changes, the dialog closes or the component unmounts.
 */
export function EvidencePhoto({ entryId, alt, hasPhoto, manual }: Props) {
  const { t } = useAdminI18n()
  const [state, setState] = useState<PhotoState>({ kind: 'loading' })
  const [attempt, setAttempt] = useState(0)

  useEffect(() => {
    if (!hasPhoto) return
    const controller = new AbortController()
    let url: string | null = null
    setState({ kind: 'loading' })
    fetchPhoto(entryId, controller.signal)
      .then(({ blob }) => {
        if (controller.signal.aborted) return
        url = URL.createObjectURL(blob)
        setState({ kind: 'ok', url })
      })
      .catch((error: unknown) => {
        if (controller.signal.aborted) return
        if (error instanceof ApiProblem && error.status === 404) setState({ kind: 'none' })
        else if (error instanceof ApiProblem && error.status === 429) {
          setState({ kind: 'limit', minutes: Math.max(1, Math.ceil((error.retryAfterSeconds ?? 60) / 60)) })
        } else setState({ kind: 'error' })
      })
    return () => {
      controller.abort()
      if (url) URL.revokeObjectURL(url)
    }
  }, [entryId, hasPhoto, attempt])

  const view: PhotoState = hasPhoto ? state : { kind: 'none' }
  return (
    <figure className="adm-photo">
      <div className="adm-photo__frame">
        {view.kind === 'loading' && (
          <p role="status" className="adm-photo__state">
            <Icon name="spinner" />
            <span>{t('admin.evidence.photoLoading')}</span>
          </p>
        )}
        {view.kind === 'ok' && <img src={view.url} alt={alt} />}
        {view.kind === 'none' && (
          <p className="adm-photo__state">
            <Icon name="camera-off" />
            <span>{manual ? t('admin.evidence.noPhotoManual') : t('admin.evidence.noPhoto')}</span>
          </p>
        )}
        {view.kind === 'limit' && (
          <p className="adm-photo__state" role="alert">
            <Icon name="alert-triangle" />
            <span>{t('admin.evidence.photoLimit', { minutes: view.minutes })}</span>
          </p>
        )}
        {view.kind === 'error' && (
          <p className="adm-photo__state" role="alert">
            <Icon name="alert-triangle" />
            <span>{t('admin.evidence.photoError')}</span>
          </p>
        )}
      </div>
      {(view.kind === 'error' || view.kind === 'limit') && (
        <button type="button" className="button button--secondary" onClick={() => setAttempt((n) => n + 1)}>
          {t('common.retry')}
        </button>
      )}
      {view.kind === 'ok' && <figcaption className="adm-hint">{t('admin.evidence.photoLogged')}</figcaption>}
    </figure>
  )
}
