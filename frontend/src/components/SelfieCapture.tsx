import { useCallback, useEffect, useId, useRef, useState } from 'react'
import { classifyCameraError, type CameraErrorKind, type OpenCamera } from '../capture/camera'
import { realCaptureDeps, type CaptureDeps } from '../capture/captureDeps'
import { CompressError, type Compressed } from '../capture/compressImage'
import type { Fix, LocationController, LocationError } from '../capture/geolocation'
import { haptics } from '../capture/haptics'
import { locationStatus } from '../capture/locationStatus'
import { useI18n } from '../i18n/useI18n'
import { Icon } from './Icon'
import { LocationChip } from './EvidenceChips'
import { ModalDialog } from './ModalDialog'

export type CaptureResult = { photo: { bytes: ArrayBuffer; type: 'image/jpeg' }; fix: Fix | null }

const EXPLAINED_KEY = 'attendance.captureExplained'
const SKIP_AFTER_MS = 5_000

type Step = 'checking' | 'explainer' | 'opening' | 'preview' | 'processing' | 'review' | 'waitingLocation' | 'error'
type ErrorKind = CameraErrorKind | 'photoTooLarge' | 'photoUnsupported' | 'cannotClock'

type Props = {
  action: 'CLOCK_IN' | 'CLOCK_OUT'
  onConfirm: (result: CaptureResult) => void
  onCancel: () => void
  deps?: CaptureDeps
}

function alreadyExplained(): boolean {
  try {
    return localStorage.getItem(EXPLAINED_KEY) === '1'
  } catch {
    return false
  }
}

/**
 * The selfie dialog: optional explainer, front camera (file input as the fallback), compression on the
 * device, and the best GPS fix of up to 20 s in parallel. Camera tracks, the GPS watch and the review
 * object URL are released on every exit path. The photo is handed to the parent as bytes only.
 */
export default function SelfieCapture({ action, onConfirm, onCancel, deps = realCaptureDeps }: Props) {
  const { t } = useI18n()
  const titleId = useId()
  const [step, setStep] = useState<Step>('checking')
  const [errorKind, setErrorKind] = useState<ErrorKind>('unknown')
  const [photo, setPhoto] = useState<{ result: Compressed; url: string } | null>(null)
  const [canSkip, setCanSkip] = useState(false)
  const [live, setLive] = useState('')
  const [locationSnapshot, setLocationSnapshot] = useState<{ fix: Fix | null; error: LocationError | null }>({
    fix: null,
    error: null,
  })

  const video = useRef<HTMLVideoElement>(null)
  const fileInput = useRef<HTMLInputElement>(null)
  const focusTarget = useRef<HTMLElement | null>(null)
  const camera = useRef<OpenCamera | null>(null)
  const location = useRef<LocationController | null>(null)
  const photoUrl = useRef<string | null>(null)
  const alive = useRef(true)
  const fileFailures = useRef(0)
  const viaFile = useRef(false)

  const releaseCamera = useCallback(() => {
    camera.current?.stop()
    camera.current = null
  }, [])

  const releaseLocation = useCallback(() => {
    location.current?.stop()
  }, [])

  const releasePhotoUrl = useCallback(() => {
    if (photoUrl.current) URL.revokeObjectURL(photoUrl.current)
    photoUrl.current = null
  }, [])

  const startLocation = useCallback(() => {
    if (location.current) return
    const controller = deps.acquire()
    location.current = controller
    controller.subscribe(() => setLocationSnapshot({ fix: controller.best(), error: controller.error() }))
  }, [deps])

  const startCamera = useCallback(async () => {
    releaseCamera()
    setStep('opening')
    setLive(t('camera.opening'))
    try {
      const opened = await deps.openCamera()
      if (!alive.current) {
        opened.stop()
        return
      }
      camera.current = opened
      setStep('preview')
      setLive(t('camera.ready'))
    } catch (error) {
      if (!alive.current) return
      setErrorKind(classifyCameraError(error))
      setStep('error')
    }
  }, [deps, t, releaseCamera])

  const begin = useCallback(() => {
    startLocation()
    void startCamera()
  }, [startLocation, startCamera])

  // Decide between the explainer and opening the camera right away.
  useEffect(() => {
    alive.current = true
    void (async () => {
      const [cameraPermission, locationPermission] = await Promise.all([
        deps.permission('camera'),
        deps.permission('geolocation'),
      ])
      if (!alive.current) return
      const undecided =
        cameraPermission === 'prompt' ||
        locationPermission === 'prompt' ||
        (cameraPermission === 'unknown' && locationPermission === 'unknown' && !alreadyExplained())
      if (undecided) {
        setStep('explainer')
      } else {
        begin()
      }
    })()
    return () => {
      alive.current = false
      releaseCamera()
      releaseLocation()
      releasePhotoUrl()
    }
    // Runs once on mount: the dependencies are stable for the lifetime of the dialog.
    // oxlint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  // Attach the stream to the preview.
  useEffect(() => {
    if (step === 'preview' && video.current && camera.current) {
      video.current.srcObject = camera.current.stream
      void Promise.resolve(video.current.play?.()).catch(() => undefined)
    }
  }, [step])

  // Focus per step (see the design spec, 3.4).
  useEffect(() => {
    focusTarget.current?.focus()
  }, [step, errorKind])

  // "Mark without location" appears after 5 s of waiting.
  useEffect(() => {
    if (step !== 'waitingLocation') return
    const id = setTimeout(() => setCanSkip(true), SKIP_AFTER_MS)
    return () => clearTimeout(id)
  }, [step])

  const finish = useCallback(
    (result: Compressed, fix: Fix | null) => {
      releaseLocation()
      releaseCamera()
      releasePhotoUrl()
      onConfirm({ photo: { bytes: result.bytes, type: result.type }, fix })
    },
    [onConfirm, releaseCamera, releaseLocation, releasePhotoUrl],
  )

  async function process(source: HTMLVideoElement | Blob) {
    setStep('processing')
    setLive(t('camera.processing'))
    try {
      const result = await deps.compress(source)
      if (!alive.current) return
      releaseCamera()
      releasePhotoUrl()
      const url = URL.createObjectURL(new Blob([result.bytes], { type: result.type }))
      photoUrl.current = url
      setPhoto({ result, url })
      setStep('review')
      setLive(t('camera.captured'))
    } catch (error) {
      if (!alive.current) return
      fileFailures.current += viaFile.current ? 1 : 0
      const reason = error instanceof CompressError ? error.reason : 'photoUnsupported'
      setErrorKind(fileFailures.current >= 2 ? 'cannotClock' : reason)
      setStep('error')
    }
  }

  function confirmPhoto() {
    if (!photo) return
    const controller = location.current
    const fix = controller?.best() ?? null
    // A fix, or a final answer without one (denied, unavailable, timeout), lets us go on at once.
    if (!controller || fix || controller.error()) {
      finish(photo.result, fix)
      return
    }
    setStep('waitingLocation')
    setLive(t('camera.waitingLocation'))
    void controller.done.then((best) => {
      if (alive.current) finish(photo.result, best)
    })
  }

  function cancel() {
    releaseCamera()
    releaseLocation()
    releasePhotoUrl()
    onCancel()
  }

  function retake() {
    releasePhotoUrl()
    setPhoto(null)
    if (viaFile.current) fileInput.current?.click()
    else void startCamera()
  }

  function pickNative() {
    viaFile.current = true
    startLocation()
    releaseCamera()
    fileInput.current?.click()
  }

  const status = locationStatus(locationSnapshot.fix, locationSnapshot.error)
  const subtitle = action === 'CLOCK_IN' ? t('camera.forIn') : t('camera.forOut')

  return (
    <ModalDialog labelledBy={titleId} onCancel={cancel} className="modal--capture">
      <div className="modal__header">
        <h2 id={titleId} tabIndex={-1} ref={step === 'opening' || step === 'processing' ? (el) => void (focusTarget.current = el) : undefined}>
          {step === 'review' ? t('camera.reviewTitle') : t('camera.title')}
        </h2>
        <button type="button" className="icon-button" aria-label={t('common.cancel')} onClick={cancel}>
          <Icon name="x-circle" />
        </button>
      </div>
      <p className="modal__subtitle">{subtitle}</p>
      <span className="visually-hidden" aria-live="polite">
        {live}
      </span>
      <input
        ref={fileInput}
        type="file"
        accept="image/*"
        capture="user"
        className="visually-hidden"
        tabIndex={-1}
        aria-hidden="true"
        data-testid="selfie-file-input"
        onChange={(event) => {
          const file = event.target.files?.[0]
          event.target.value = ''
          if (file) void process(file)
          else if (viaFile.current) {
            fileFailures.current += 1
            setErrorKind(fileFailures.current >= 2 ? 'cannotClock' : 'photoUnsupported')
            setStep('error')
          }
        }}
      />

      {step !== 'checking' && step !== 'explainer' && step !== 'error' && (
        <LocationChip status={status.status} accuracyMeters={status.accuracyMeters} />
      )}

      {step === 'explainer' && (
        <>
          <p>{t('camera.explain')}</p>
          <div className="modal__actions">
            <button
              type="button"
              className="button button--primary"
              ref={(el) => void (focusTarget.current = el)}
              onClick={() => {
                try {
                  localStorage.setItem(EXPLAINED_KEY, '1')
                } catch {
                  // Only decides whether the explainer is shown again.
                }
                begin()
              }}
            >
              {t('common.continue')}
            </button>
            <button type="button" className="button button--secondary" onClick={cancel}>
              {t('common.cancel')}
            </button>
          </div>
        </>
      )}

      {(step === 'opening' || step === 'processing') && (
        <p className="busy-line">
          <Icon name="spinner" />
          <span>{step === 'opening' ? t('camera.opening') : t('camera.processing')}</span>
        </p>
      )}

      {step === 'preview' && (
        <>
          <video
            ref={video}
            className="preview-video"
            aria-label={t('camera.preview')}
            playsInline
            muted
            autoPlay
          />
          <p className="hint">{t('camera.framing')}</p>
          <div className="modal__actions">
            <button
              type="button"
              className="button button--primary shutter"
              ref={(el) => void (focusTarget.current = el)}
              onClick={() => {
                haptics.tap()
                if (video.current) void process(video.current)
              }}
            >
              <Icon name="camera" />
              <span>{t('camera.capture')}</span>
            </button>
            <button type="button" className="button button--secondary" onClick={cancel}>
              {t('common.cancel')}
            </button>
          </div>
        </>
      )}

      {(step === 'review' || step === 'waitingLocation') && photo && (
        <>
          <img className="review-image" src={photo.url} alt={t('camera.reviewAlt')} />
          <p className="hint">{t('camera.notMirrored')}</p>
          {step === 'review' && (
            <>
              <p className="hint">{action === 'CLOCK_IN' ? t('camera.useHintIn') : t('camera.useHintOut')}</p>
              <div className="modal__actions">
                <button
                  type="button"
                  className="button button--primary"
                  ref={(el) => void (focusTarget.current = el)}
                  onClick={confirmPhoto}
                >
                  {t('camera.use')}
                </button>
                <button type="button" className="button button--secondary" onClick={retake}>
                  {t('camera.retake')}
                </button>
              </div>
            </>
          )}
          {step === 'waitingLocation' && (
            <>
              <p className="busy-line">
                <Icon name="spinner" />
                <span>{t('camera.waitingLocation')}</span>
              </p>
              <div className="modal__actions">
                {canSkip && (
                  <button type="button" className="button button--secondary" onClick={() => location.current?.stop()}>
                    {t('evidence.location.skip')}
                  </button>
                )}
                <button
                  type="button"
                  className="button button--secondary"
                  ref={(el) => void (focusTarget.current = el)}
                  onClick={cancel}
                >
                  {t('common.cancel')}
                </button>
              </div>
            </>
          )}
        </>
      )}

      {step === 'error' && (
        <ErrorPanel
          kind={errorKind}
          setFocus={(el) => void (focusTarget.current = el)}
          onRetry={() => (viaFile.current ? retake() : begin())}
          onNative={pickNative}
          onCancel={cancel}
        />
      )}
    </ModalDialog>
  )
}

type PanelProps = {
  kind: ErrorKind
  setFocus: (el: HTMLElement | null) => void
  onRetry: () => void
  onNative: () => void
  onCancel: () => void
}

function ErrorPanel({ kind, setFocus, onRetry, onNative, onCancel }: PanelProps) {
  const { t } = useI18n()
  const message = {
    denied: t('camera.denied'),
    notFound: t('camera.notFound'),
    busy: t('camera.busy'),
    unsupported: t('camera.unsupported'),
    unknown: t('camera.unsupported'),
    photoTooLarge: t('camera.photoTooLarge'),
    photoUnsupported: t('camera.photoUnsupported'),
    cannotClock: t('camera.cannotClock'),
  }[kind]
  const canRetryCamera = kind === 'denied' || kind === 'busy'
  const photoProblem = kind === 'photoTooLarge' || kind === 'photoUnsupported'
  return (
    <>
      <div ref={setFocus} role="alert" tabIndex={-1} className="notice notice--error">
        <Icon name="x-circle" />
        <div>
          <p>{message}</p>
          {kind === 'denied' && (
            <>
              <p>{t('camera.deniedHelp')}</p>
              <ul>
                <li>{t('camera.deniedHelpAndroid')}</li>
                <li>{t('camera.deniedHelpIos')}</li>
              </ul>
            </>
          )}
        </div>
      </div>
      {kind !== 'cannotClock' && !photoProblem && <p className="hint">{t('camera.contactAdmin')}</p>}
      <div className="modal__actions">
        {(canRetryCamera || photoProblem) && (
          <button type="button" className="button button--primary" onClick={onRetry}>
            {photoProblem ? t('camera.retake') : t('common.retry')}
          </button>
        )}
        {kind !== 'cannotClock' && !photoProblem && (
          <button type="button" className="button button--secondary" onClick={onNative}>
            {t('camera.useNativeCamera')}
          </button>
        )}
        <button type="button" className="button button--secondary" onClick={onCancel}>
          {t('common.cancel')}
        </button>
      </div>
    </>
  )
}
