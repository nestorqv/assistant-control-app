import { useI18n } from '../i18n/useI18n'
import type { LocationStatus } from '../capture/locationStatus'
import { Icon, type IconName } from './Icon'

type LocationProps = { status: LocationStatus; accuracyMeters?: number }

const LOCATION: Record<LocationStatus, { icon: IconName; tone: 'neutral' | 'ok' | 'warn' }> = {
  idle: { icon: 'map-pin', tone: 'neutral' },
  searching: { icon: 'spinner', tone: 'neutral' },
  ready: { icon: 'check-circle', tone: 'ok' },
  lowAccuracy: { icon: 'alert-triangle', tone: 'warn' },
  denied: { icon: 'map-pin-off', tone: 'warn' },
  unavailable: { icon: 'map-pin-off', tone: 'warn' },
}

/** Readiness only. It never receives a distance: the server alone decides inside or outside. */
export function LocationChip({ status, accuracyMeters }: LocationProps) {
  const { t } = useI18n()
  const { icon, tone } = LOCATION[status]
  const accuracy = accuracyMeters ?? 0
  const text = {
    idle: t('evidence.location.idle'),
    searching: t('evidence.location.searching'),
    ready: t('evidence.location.ready', { accuracy }),
    lowAccuracy: t('evidence.location.lowAccuracy', { accuracy }),
    denied: t('evidence.location.denied'),
    unavailable: t('evidence.location.unavailable'),
  }[status]
  return (
    <span className={`chip chip--${tone}`}>
      <Icon name={icon} />
      <span>{text}</span>
    </span>
  )
}

export function SelfieChip({ camera }: { camera: 'unknown' | 'blocked' }) {
  const { t } = useI18n()
  const blocked = camera === 'blocked'
  return (
    <span className={`chip chip--${blocked ? 'warn' : 'neutral'}`}>
      <Icon name={blocked ? 'camera-off' : 'camera'} />
      <span>{blocked ? t('evidence.selfie.blocked') : t('evidence.takeSelfie')}</span>
    </span>
  )
}

type Props = { camera: 'unknown' | 'blocked'; location: LocationProps }

/** Informational chips, not buttons: the primary button is the only way to start a capture. */
export function EvidenceChips({ camera, location }: Props) {
  const { t } = useI18n()
  return (
    <section className="evidence" aria-labelledby="evidence-heading">
      <h2 id="evidence-heading" className="evidence__title">
        {t('evidence.title')}
      </h2>
      <ul className="chips">
        <li>
          <SelfieChip camera={camera} />
        </li>
        <li>
          <LocationChip {...location} />
        </li>
      </ul>
    </section>
  )
}
