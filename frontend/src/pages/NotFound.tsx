import { Link } from 'react-router'
import { useI18n } from '../i18n/useI18n'

export function NotFound() {
  const { t } = useI18n()
  return (
    <main>
      <h1>{t('notFound.title')}</h1>
      <Link to="/">{t('common.backHome')}</Link>
    </main>
  )
}
