import { Link } from 'react-router'
import { HealthStatus } from '../components/HealthStatus'
import { useI18n } from '../i18n/useI18n'

export function AdminHome() {
  const { t } = useI18n()
  return (
    <main>
      <h1>{t('adminHome.title')}</h1>
      <p>{t('adminHome.subtitle')}</p>
      <HealthStatus />
      <Link to="/">{t('common.backHome')}</Link>
    </main>
  )
}
