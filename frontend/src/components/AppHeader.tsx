import { useRef, useState } from 'react'
import { useLocation } from 'react-router'
import { useSession } from '../auth/useSession'
import { useI18n } from '../i18n/useI18n'
import { Icon } from './Icon'
import { LanguageSwitcher } from './LanguageSwitcher'
import { LogoutConfirm } from './LogoutConfirm'

/** Language switcher, plus a sign-out button whenever an identity exists (not on the login page). */
export function AppHeader() {
  const { t } = useI18n()
  const { identity } = useSession()
  const { pathname } = useLocation()
  const [confirming, setConfirming] = useState(false)
  const button = useRef<HTMLButtonElement>(null)
  const showLogout = identity !== null && pathname !== '/login'

  return (
    <header className="app-header">
      <LanguageSwitcher />
      {showLogout && (
        <>
          <button ref={button} type="button" className="header-button" onClick={() => setConfirming(true)}>
            <Icon name="log-out" />
            <span>{t('logout.button')}</span>
          </button>
          <LogoutConfirm open={confirming} onClose={() => setConfirming(false)} />
        </>
      )}
    </header>
  )
}
