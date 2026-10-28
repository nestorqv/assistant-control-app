import { useI18n } from '../i18n/useI18n'
import { Icon } from './Icon'

/** A state, not an interruption: not role="alert". The Announcer says it once when it changes. */
export function OfflineBanner({ offline }: { offline: boolean }) {
  const { t } = useI18n()
  if (!offline) return null
  return (
    <p className="notice notice--warn offline-banner">
      <Icon name="wifi-off" />
      <span>{t('offline.banner')}</span>
    </p>
  )
}
