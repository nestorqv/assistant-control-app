import { Link } from 'react-router'
import { HealthStatus } from '../components/HealthStatus'
import { useI18n } from '../i18n/useI18n'

export function EmployeeHome() {
  const { t } = useI18n()
  return (
    <main>
      <h1>{t('app.title')}</h1>
      <p>{t('employeeHome.subtitle')}</p>
      <HealthStatus />
      <Link to="/admin">{t('employeeHome.toAdmin')}</Link>
    </main>
  )
}
