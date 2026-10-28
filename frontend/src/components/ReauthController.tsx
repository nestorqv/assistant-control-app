import { useState } from 'react'
import { login } from '../api/auth'
import { ApiProblem } from '../api/problem'
import { reauthenticated } from '../auth/session'
import { useSession } from '../auth/useSession'
import { describeError, retryAfterOf } from '../errors/errorMessages'
import { useI18n } from '../i18n/useI18n'
import { useAnnouncer } from './announcerContext'
import { LogoutConfirm } from './LogoutConfirm'
import { ReauthDialog } from './ReauthDialog'

/** Mounted only while the PIN dialog is open. A PIN for a different employee is treated as wrong credentials. */
export function ReauthController({ onClose }: { onClose: () => void }) {
  const { t } = useI18n()
  const { identity } = useSession()
  const { announce } = useAnnouncer()
  const [busy, setBusy] = useState(false)
  const [errorText, setErrorText] = useState<string | null>(null)
  const [attempt, setAttempt] = useState(0)
  const [retryAt, setRetryAt] = useState<number | null>(null)
  const [notYou, setNotYou] = useState(false)

  if (!identity) return null

  async function submit(pin: string) {
    if (!identity) return
    setBusy(true)
    setErrorText(null)
    try {
      const response = await login(identity.companyCode, identity.employeeCode, pin)
      if (!reauthenticated(response)) throw new ApiProblem(401, 'INVALID_CREDENTIALS')
      announce(t('announce.sessionRestored'))
      onClose()
    } catch (error) {
      setErrorText(describeError(error, t))
      setAttempt((n) => n + 1)
      const seconds = retryAfterOf(error)
      setRetryAt(seconds === undefined ? null : performance.now() + seconds * 1000)
      setBusy(false)
    }
  }

  if (notYou) return <LogoutConfirm open onClose={() => setNotYou(false)} />

  return (
    <ReauthDialog
      identity={identity}
      busy={busy}
      errorText={errorText}
      attempt={attempt}
      retryAt={retryAt}
      onSubmit={(pin) => void submit(pin)}
      onLater={onClose}
      onNotYou={() => setNotYou(true)}
    />
  )
}
